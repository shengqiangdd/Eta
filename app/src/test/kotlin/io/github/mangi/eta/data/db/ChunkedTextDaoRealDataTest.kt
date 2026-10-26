package io.github.mangi.eta.data.db

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用**真机库副本**跑真实的 `storeText` / `restoreText`（只写副本，不碰原库）。
 *
 * 为什么还要这一层：内存库测试证明的是「算法自洽」，证明不了「跟真机上已经存在的那 73 MB
 * 分块数据合得来」—— 块下标是否连续、引用长度是否按 UTF-16 记、代理对有没有被拆开，
 * 只有在真数据上跑一遍才知道。副本缺失时整类跳过（`assumeTrue`），不放水成绿。
 *
 * 副本位置（Linux 侧，来自 `/data/local/tmp` 的映射）：`/workspace/dbreal/eta.db`。
 */
class ChunkedTextDaoRealDataTest {

    private val databaseFile = File("/workspace/dbreal/eta.db")

    private fun openRealDao(owner: String, field: String = "journal"): JdbcChunkDao = JdbcChunkDao(
        url = "jdbc:sqlite:${databaseFile.absolutePath}",
        table = TABLE,
        owner = owner,
        field = field,
        createTable = false,
    )

    /** 从真库里挑两个极端的会话：块最少的多块会话 + 块最多且总长可控的大会话。 */
    private fun pickOwners(): List<String> {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            val rows = ArrayList<Triple<String, Int, Long>>()
            connection.prepareStatement(
                "SELECT owner_id, COUNT(*) AS n, SUM(LENGTH(content)) AS chars FROM agent_text_chunks " +
                    "WHERE owner_table='conversation_context_checkpoints' AND field='journal' " +
                    "GROUP BY owner_id ORDER BY n",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) rows += Triple(result.getString(1), result.getInt(2), result.getLong(3))
                }
            }
            val multiChunk = rows.filter { it.second >= 5 }
            if (multiChunk.isEmpty()) return emptyList()
            // 上限 800 万字符：够验证「真数据上的大 journal」，又不会把测试 JVM 堆撑爆
            val bounded = multiChunk.filter { it.third <= 8_000_000 }
            val biggest = bounded.lastOrNull() ?: multiChunk.first()
            return listOf(multiChunk.first().first, biggest.first).distinct()
        }
    }

    private fun storedReference(owner: String): String {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.prepareStatement(
                "SELECT journal_json FROM conversation_context_checkpoints WHERE conversation_id=?",
            ).use { statement ->
                statement.setString(1, owner)
                statement.executeQuery().use { result ->
                    assertTrue("真库里找不到该会话的 checkpoint", result.next())
                    return result.getString(1)
                }
            }
        }
    }

    @Test
    fun `真机数据上还原与增量写都正确`() {
        assumeTrue("没有真机库副本，跳过：${databaseFile.absolutePath}", databaseFile.isFile)
        val owners = pickOwners()
        assumeTrue("真库里没有可分块的多块会话", owners.isNotEmpty())

        owners.forEach { owner ->
            val reference = storedReference(owner)
            val dao = openRealDao(owner)
            try {
                val original = dao.restore(reference)
                assertTrue("$owner 还原出的内容为空", original.isNotEmpty())
                assertTrue("$owner 引用长度与内容不符",
                    reference.endsWith(":${original.length}"))
                val before = dao.chunkCount()
                println("真机副本 $owner: ${before} 块 / ${original.length} 字符")

                // 1) 内容没变 —— 一次都不该写
                dao.resetCounters()
                val same = dao.store(original)
                println("  内容未变 → inserts=${dao.inserts} deletes=${dao.deletes} tail=${dao.tailDeletes}")
                assertEquals("$owner 内容未变却写了库", 0, dao.inserts + dao.deletes + dao.tailDeletes)
                assertEquals(reference, same)
                assertEquals(before, dao.chunkCount())

                // 2) 纯追加 —— 只碰尾部
                dao.resetCounters()
                val grown = original + "\n{\"role\":\"user\",\"content\":\"真机副本追加测试\"}"
                val grownStored = dao.store(grown)
                println("  纯追加 → inserts=${dao.inserts} deletes=${dao.deletes} tail=${dao.tailDeletes}")
                assertEquals("$owner 纯追加不应整表删除", 0, dao.deletes)
                assertTrue("$owner 应走尾部删除", dao.tailDeletes >= 1)
                assertTrue("$owner 增量写块数异常：${dao.inserts}", dao.inserts <= 3)
                assertEquals(grown, dao.restore(grownStored))
                assertEquals(grown, dao.storedText())

                // 3) 中间改一个字 —— 退回整段重写，内容仍要正确
                dao.resetCounters()
                val pivot = original.length / 2
                val edited = original.substring(0, pivot) + "改" + original.substring(pivot + 1)
                val editedStored = dao.store(edited)
                println("  中间改写 → inserts=${dao.inserts} deletes=${dao.deletes} tail=${dao.tailDeletes}")
                assertTrue("$owner 中间修改应整段重写", dao.deletes >= 1)
                assertEquals(edited, dao.restore(editedStored))
                assertEquals(edited, dao.storedText())

                // 复原，便于同一副本重复跑
                dao.store(original)
                assertEquals("$owner 复原后内容不一致", original, dao.storedText())
            } finally {
                dao.close()
            }
        }
    }

    private companion object {
        const val TABLE = "conversation_context_checkpoints"
    }
}
