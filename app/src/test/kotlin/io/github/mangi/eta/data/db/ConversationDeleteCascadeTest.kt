package io.github.mangi.eta.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 删除会话必须真的把它的数据删掉。
 *
 * 背景：实体上写着 `ON DELETE CASCADE`。2026-10-21 曾用 sqlite3 查库副本的
 * `PRAGMA foreign_keys` 得到 OFF，据此判断「Room 不开外键、CASCADE 不生效」——
 * **这个判断在 2026-10-24 被推翻**：PRAGMA 是连接级设置，sqlite3 测的是它自己的连接；
 * 装机 APK 里 Room 2.8.5 生成的 `EtaDatabase_Impl.onOpen` 明确执行 `PRAGMA foreign_keys = ON`。
 *
 * 结论：真机上外键是开的，CASCADE 会生效。但 `deleteConversationCascade` 仍显式「先子后父」，
 * 让语义不依赖 PRAGMA 开关。本测试因此在两种设置下都跑：无论开关，删完都不能有残留。
 */
class ConversationDeleteCascadeTest {

    private val source = File(System.getProperty("eta.realDb") ?: "/workspace/dbreal/rt.db")
    private val target = File("/workspace/dbreal/rt-delete.db")

    @Test fun `外键关闭时应用层级联删除不留残留`() = deleteWith(foreignKeys = false)

    @Test fun `外键开启时级联删除与应用层删除不冲突`() = deleteWith(foreignKeys = true)

    private fun deleteWith(foreignKeys: Boolean) {
        assumeTrue("没有真机库副本，跳过", source.isFile)
        source.copyTo(target, overwrite = true)
        File("${source.absolutePath}-wal").takeIf { it.isFile }?.copyTo(File("${target.absolutePath}-wal"), overwrite = true)
        File("${source.absolutePath}-shm").takeIf { it.isFile }?.copyTo(File("${target.absolutePath}-shm"), overwrite = true)

        DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { c ->
            c.createStatement().use { it.execute("PRAGMA foreign_keys=${if (foreignKeys) "ON" else "OFF"}") }
            val enabled = c.createStatement().use { s ->
                s.executeQuery("PRAGMA foreign_keys").use { r -> r.next() && r.getInt(1) == 1 }
            }
            assumeTrue("PRAGMA 没生效，环境不支持这条用例", enabled == foreignKeys)

            val conversationId = c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT conversation_id FROM conversation_messages GROUP BY conversation_id " +
                        "ORDER BY COUNT(*) DESC LIMIT 1",
                ).use { r -> if (r.next()) r.getString(1) else "" }
            }
            assumeTrue("库里没有消息，跳过", conversationId.isNotEmpty())

            val messageIds = c.prepareStatement("SELECT id FROM conversation_messages WHERE conversation_id=?")
                .use { s ->
                    s.setString(1, conversationId)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                }
            val before = snapshot(c, conversationId, messageIds)
            assumeTrue("这个会话没有数据，换个用例没意义", before.messages > 0)

            // 与 ConversationDao.deleteConversationCascade 完全相同的顺序。
            delete(c, "DELETE FROM conversation_messages WHERE conversation_id=?", conversationId)
            delete(c, "DELETE FROM conversation_context_checkpoints WHERE conversation_id=?", conversationId)
            delete(c, "DELETE FROM conversations WHERE id=?", conversationId)

            val after = snapshot(c, conversationId, messageIds)
            println(
                "DELETE-CASCADE fk=$foreignKeys conversations=${conversationId.take(13)} " +
                    "messages ${before.messages}->${after.messages} " +
                    "checkpoint ${before.checkpoint}->${after.checkpoint} " +
                    "chunks ${before.chunks}->${after.chunks}",
            )
            assertEquals("消息必须删干净", 0, after.messages)
            assertEquals("上下文快照必须删干净", 0, after.checkpoint)
            assertEquals("消息与快照的分块必须删干净", 0, after.chunks)
        }
    }

    private fun delete(connection: Connection, sql: String, id: String) {
        connection.prepareStatement(sql).use { s ->
            s.setString(1, id)
            s.executeUpdate()
        }
    }

    private data class Counts(val messages: Int, val checkpoint: Int, val chunks: Int)

    private fun snapshot(c: Connection, conversationId: String, messageIds: List<String>): Counts {
        val messages = c.prepareStatement("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id=?")
            .use { s -> s.setString(1, conversationId); s.executeQuery().use { r -> r.next(); r.getInt(1) } }
        val checkpoint = c.prepareStatement("SELECT COUNT(*) FROM conversation_context_checkpoints WHERE conversation_id=?")
            .use { s -> s.setString(1, conversationId); s.executeQuery().use { r -> r.next(); r.getInt(1) } }
        var chunks = 0
        if (messageIds.isNotEmpty()) {
            val placeholders = messageIds.joinToString(",") { "?" }
            chunks += c.prepareStatement(
                "SELECT COUNT(*) FROM agent_text_chunks WHERE owner_table='conversation_messages' " +
                    "AND owner_id IN ($placeholders)",
            ).use { s ->
                messageIds.forEachIndexed { index, id -> s.setString(index + 1, id) }
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }
        chunks += c.prepareStatement(
            "SELECT COUNT(*) FROM agent_text_chunks WHERE owner_table='conversation_context_checkpoints' AND owner_id=?",
        ).use { s -> s.setString(1, conversationId); s.executeQuery().use { r -> r.next(); r.getInt(1) } }
        return Counts(messages, checkpoint, chunks)
    }
}
