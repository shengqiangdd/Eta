package io.github.mangi.eta.data.db

import io.github.mangi.eta.agent.model.AgentConversationCodec
import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 探针：存储里的 journal 能不能「解码再编码」还原成同一份文本。
 *
 * 为什么重要：`storeText` 的增量写（kind=tail）**完全依赖前缀比对**。
 * 只要 `encode(decode(stored)) != stored`，每次保存都会在第一个不同处判定「内容变了」，
 * 退回 `kind=full` 整段重写 —— 优化等于没做，而且日志里看不出来。
 *
 * 这个探针在真机库副本上直接回答问题，并打印第一处差异的位置与上下文。
 */
class JournalRoundTripProbeTest {

    private val databaseFile =
        File(System.getProperty("eta.realDb") ?: "/workspace/dbreal/rt.db")

    @Test fun storedJournalSurvivesDecodeThenEncodeUnchanged() {
        assumeTrue("没有真机库副本，跳过", databaseFile.isFile)
        val rows = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { c ->
            c.prepareStatement(
                "SELECT owner_id, COUNT(*) FROM agent_text_chunks " +
                    "WHERE owner_table='conversation_context_checkpoints' AND field='journal' " +
                    "GROUP BY owner_id ORDER BY 2 DESC",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString(1) to result.getInt(2)) }
                }
            }
        }
        assumeTrue("库里没有分块 journal", rows.isNotEmpty())
        rows.forEach { (owner, chunks) ->
            val dao = JdbcChunkDao(
                url = "jdbc:sqlite:${databaseFile.absolutePath}",
                owner = owner,
                field = "journal",
                createTable = false,
            )
            try {
                val stored = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { c ->
                    c.prepareStatement("SELECT journal_json FROM conversation_context_checkpoints WHERE conversation_id=?")
                        .use { s ->
                            s.setString(1, owner)
                            s.executeQuery().use { r -> if (r.next()) r.getString(1) else "" }
                        }
                }
                val text = dao.restore(stored)
                val messages = AgentConversationCodec.decodeTranscript(text)
                val encoded = AgentConversationCodec.encodeTranscriptForStorage(messages)
                val same = encoded == text
                val firstDiff = if (same) -1 else text.indices.firstOrNull { it >= encoded.length || text[it] != encoded[it] }
                    ?: minOf(text.length, encoded.length)
                println(
                    "ROUNDTRIP ${owner.take(13)} chunks=$chunks chars=${text.length} " +
                        "messages=${messages.size} encodedChars=${encoded.length} same=$same firstDiff=$firstDiff" +
                        if (!same) " ctxStored=${text.substring(maxOf(0, firstDiff - 40), minOf(text.length, firstDiff + 60))}" +
                            " ctxEncoded=${encoded.substring(maxOf(0, firstDiff - 40), minOf(encoded.length, firstDiff + 60))}"
                        else "",
                )
                assertTrue(
                    "journal 解码再编码后不一致（增量写会永远退化成整段重写）：$owner firstDiff=$firstDiff",
                    same,
                )
            } finally {
                dao.close()
            }
        }
    }
}
