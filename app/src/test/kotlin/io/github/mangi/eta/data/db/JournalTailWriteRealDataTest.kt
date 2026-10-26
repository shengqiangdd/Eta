package io.github.mangi.eta.data.db

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 探针：在**真机库副本**上验证「解码 → 追加一条 → 编码 → storeText」是否只重写尾部。
 *
 * 为什么需要：真机日志只出现过 `kind=full`（10:18 安装后第一次保存）。若这条链路在真实数据上
 * 能给出 `kind=tail`，说明 `storeText` 本身没问题，真机的 full 属于一次性归一化或载荷变化；
 * 若这里也退化，则缺陷在比对/写入逻辑，必须修。
 *
 * 会写库，所以先复制到独立文件，绝不动原始副本。
 */
class JournalTailWriteRealDataTest {

    private val source = File(System.getProperty("eta.realDb") ?: "/workspace/dbreal/rt.db")
    private val target = File("/workspace/dbreal/rt-tail.db")

    @Test fun appendingOneMessageRewritesOnlyTail() = runBlocking {
        assumeTrue("没有真机库副本，跳过", source.isFile)
        assumeTrue("JDBC SQLite 不可用（离线依赖缺失），跳过", runCatching {
            Class.forName("org.sqlite.JDBC")
        }.isSuccess)
        source.copyTo(target, overwrite = true)

        val url = "jdbc:sqlite:${target.absolutePath}"
        val owner = DriverManager.getConnection(url).use { c ->
            c.prepareStatement(
                "SELECT owner_id FROM agent_text_chunks " +
                    "WHERE owner_table='conversation_context_checkpoints' AND field='journal' " +
                    "GROUP BY owner_id ORDER BY COUNT(*) DESC LIMIT 1",
            ).use { s -> s.executeQuery().use { r -> if (r.next()) r.getString(1) else "" } }
        }
        assumeTrue("库里没有分块 journal", owner.isNotEmpty())

        val stored = DriverManager.getConnection(url).use { c ->
            c.prepareStatement("SELECT journal_json FROM conversation_context_checkpoints WHERE conversation_id=?")
                .use { s ->
                    s.setString(1, owner)
                    s.executeQuery().use { r -> if (r.next()) r.getString(1) else "" }
                }
        }
        val dao = JdbcChunkDao(url = url, owner = owner, field = "journal", createTable = false)
        try {
            val text = dao.restoreText("conversation_context_checkpoints", owner, "journal", stored)
            val messages = AgentConversationCodec.decodeTranscript(text)
            assumeTrue("journal 太短，不是本轮关心的场景", text.length > 500_000)

            val appended = messages + AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "尾部追加探针：${"x".repeat(200)}",
            )
            val newText = AgentConversationCodec.encodeTranscriptForStorage(appended)
            assertTrue("新文本必须比旧文本长", newText.length > text.length)

            val before = dao.inserts
            val reference = dao.storeText("conversation_context_checkpoints", owner, "journal", newText, stored = stored)
            val written = dao.inserts - before
            val restored = dao.restoreText("conversation_context_checkpoints", owner, "journal", reference)

            println(
                "TAILWRITE owner=${owner.take(13)} oldChars=${text.length} newChars=${newText.length} " +
                    "tailDeletes=${dao.tailDeletes} fullDeletes=${dao.deletes} inserts=$written",
            )

            assertEquals("追加后按引用还原必须与写入文本逐字符一致", newText, restored)
            assertEquals("整段重写退化了：应只走 tailDeletes", 0, dao.deletes)
            assertEquals("应恰好一次尾部删除", 1, dao.tailDeletes)
            assertTrue("应只重写尾部若干块，实际写了 $written 块", written <= 3)

            // 再存一次同样的文本：应当 unchanged，一个块都不写。
            val beforeSecond = dao.inserts
            val again = dao.storeText("conversation_context_checkpoints", owner, "journal", newText, stored = reference)
            assertEquals("内容未变时不应写库", beforeSecond, dao.inserts)
            assertEquals("引用应保持一致", reference, again)
        } finally {
            dao.close()
        }
    }
}
