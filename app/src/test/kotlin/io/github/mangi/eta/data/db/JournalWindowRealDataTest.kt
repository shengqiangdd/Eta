package io.github.mangi.eta.data.db

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.tool.JsonArrayElementScanner
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 在**真机库副本**上验证「按块窗口读」与「整段还原」给出完全相同的文本，
 * 并用它跑一遍真实的 3100 万字符 journal 扫描。
 *
 * 为什么必须上真数据：块下标是否连续、引用长度是否按 UTF-16 记、代理对有没有被拆开，
 * 这些问题在内存库里都碰不到。副本缺失时整类跳过（`assumeTrue`），不放水成绿。
 *
 * 只读：本测试不写库（写库路径由 [ChunkedTextDaoRealDataTest] 覆盖）。
 */
class JournalWindowRealDataTest {

    private val databaseFile =
        File(System.getProperty("eta.realDb") ?: "/workspace/dbreal/eta-journal.db")

    private fun biggestJournalOwner(): String? =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.prepareStatement(
                "SELECT owner_id, COUNT(*) AS n FROM agent_text_chunks " +
                    "WHERE owner_table='conversation_context_checkpoints' AND field='journal' " +
                    "GROUP BY owner_id ORDER BY n DESC LIMIT 1",
            ).use { statement ->
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    private fun storedReference(owner: String): String =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.prepareStatement(
                "SELECT journal_json FROM conversation_context_checkpoints WHERE conversation_id=?",
            ).use { statement ->
                statement.setString(1, owner)
                statement.executeQuery().use { result ->
                    assertTrue("真库里找不到该会话的 checkpoint", result.next())
                    result.getString(1)
                }
            }
        }

    @Test fun windowedReadEqualsFullRestoreAndScansTheRealJournal() = runBlocking {
        assumeTrue("没有真机库副本，跳过：${databaseFile.absolutePath}", databaseFile.isFile)
        val owner = biggestJournalOwner()
        assumeTrue("真库里没有分块 journal", owner != null)
        val reference = storedReference(owner!!)
        val dao = JdbcChunkDao(
            url = "jdbc:sqlite:${databaseFile.absolutePath}",
            owner = owner,
            field = "journal",
            createTable = false,
        )
        try {
            val full = dao.restore(reference)
            assertTrue("真数据太小，说明取错了会话：${full.length}", full.length > 5_000_000)
            println("真机副本 journal: ${dao.chunkCount()} 块 / ${full.length} 字符")

            // 1) 逐窗读拼回来必须逐字符一致（窗口大小故意取 8，与扫描器一致）
            val rebuilt = StringBuilder(full.length)
            var from = 0
            while (true) {
                val page = dao.readStoredChunks(TABLE, owner, FIELD, reference, from, 8)
                if (page.isEmpty()) break
                page.forEach(rebuilt::append)
                from += page.size
            }
            assertEquals("窗口读与整段还原不一致", full.length, rebuilt.length)
            assertEquals("窗口读与整段还原内容不一致", full, rebuilt.toString())

            // 2) 真数据上扫描：条数要同时对上「独立口径」和「整体解析」
            val scanner = JsonArrayElementScanner { start, limit ->
                dao.readStoredChunks(TABLE, owner, FIELD, reference, start, limit)
            }
            val page = scanner.scan(0, 8_000, null)
            val independent = AgentConversationCodec.decodeTranscript(full).size
            assertEquals("与整体解析的条数不一致", independent, page.totalMessages)
            assertEquals("与 Python json.loads 独立数出的条数不一致", 6450, page.totalMessages)
            assertTrue(page.captured.isNotEmpty())
            assertTrue(
                "捕获字符数 ${page.captured.sumOf { it.second.length }} 应贴着预算",
                page.captured.sumOf { it.second.length } < 8_000 + 200_000,
            )

            // 3) 深处翻页：下标必须原样落在请求位置
            val deep = scanner.scan(6_000, 8_000, null)
            assertEquals(6_000, deep.captured.first().first)
            assertEquals(independent, deep.totalMessages)
        } finally {
            dao.close()
        }
    }

    private companion object {
        const val TABLE = "conversation_context_checkpoints"
        const val FIELD = "journal"
    }
}
