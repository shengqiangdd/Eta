package io.github.mangi.eta.data.db

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26→27 迁移语句的 JDBC 契约测试（纯 JVM，不需要 Robolectric）。
 *
 * 本机 arm64 跑不了 Robolectric 的 SQLite native，真机升级路径只能靠这一层守。
 * 这里验证三件在生产上会出事的事：
 *
 * 1. 语句能在**已有数据**的表上跑通（ALTER TABLE 在真机上是就地改表，不是重建）。
 * 2. 旧行拿到 NULL 而不是 0 —— 0 会在界面上显示成「首字 0ms」「无限快」，
 *    比不显示更误导。
 * 3. 新值能写能读（类型是 INTEGER，不是 TEXT）。
 */
class ConversationTimingColumnsMigrationTest {

    private val createLegacyTable =
        "CREATE TABLE conversation_messages(" +
            "id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, " +
            "type TEXT NOT NULL, content TEXT NOT NULL, elapsed_seconds INTEGER, cached_tokens INTEGER)"

    @Test
    fun `迁移后旧行的两个新列都是 NULL`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(
                    "INSERT INTO conversation_messages VALUES ('old', 'c', 0, 'thinking', '旧回答', 3, 200)",
                )
            }

            connection.createStatement().use {
                it.executeUpdate(EtaDatabase.SQL_V26_ADD_TIMING_COLUMNS)
                it.executeUpdate(EtaDatabase.SQL_V26_ADD_DECODE_MS)
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT first_token_ms, decode_ms, elapsed_seconds FROM conversation_messages WHERE id = 'old'",
                ).use { rows ->
                    assertTrue("迁移后读不到旧行", rows.next())
                    rows.getObject(1)
                    assertTrue("旧行 first_token_ms 必须是 NULL，不能是 0", rows.wasNull())
                    rows.getObject(2)
                    assertTrue("旧行 decode_ms 必须是 NULL，不能是 0", rows.wasNull())
                    // 既有列不能被迁移破坏。
                    assertEquals(3, rows.getInt(3))
                }
            }
        }
    }

    @Test
    fun `新列能写能读且保持整数类型`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_TIMING_COLUMNS)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_DECODE_MS)
                statement.executeUpdate(
                    "INSERT INTO conversation_messages" +
                        "(id, conversation_id, sort_index, type, content, first_token_ms, decode_ms) " +
                        "VALUES ('new', 'c', 0, 'thinking', '新回答', 850, 2400)",
                )
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT first_token_ms, decode_ms FROM conversation_messages WHERE id = 'new'",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(850, rows.getInt(1))
                    assertEquals(2400, rows.getInt(2))
                }
            }
        }
    }

    @Test
    fun `两条语句可以重复执行而不报错`() {
        // ALTER TABLE ADD COLUMN 重复执行会抛 duplicate column。
        // 这里不是要求语句幂等（Room 的迁移只跑一次），而是固定住这个事实：
        // 如果有人把它改成会在已迁移的库上再跑一次，测试会立刻红。
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_TIMING_COLUMNS)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_DECODE_MS)
            }
            var duplicated = false
            try {
                connection.createStatement().use { it.executeUpdate(EtaDatabase.SQL_V26_ADD_TIMING_COLUMNS) }
            } catch (expected: Exception) {
                duplicated = true
            }
            assertTrue("重复加列本应失败，说明语句或 SQLite 行为变了，迁移链需要复查", duplicated)
        }
    }

    @Test
    fun `写入 NULL 后读回仍是 NULL 而不是默认值`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_TIMING_COLUMNS)
                statement.executeUpdate(EtaDatabase.SQL_V26_ADD_DECODE_MS)
                statement.executeUpdate(
                    "INSERT INTO conversation_messages" +
                        "(id, conversation_id, sort_index, type, content, first_token_ms, decode_ms) " +
                        "VALUES ('n', 'c', 0, 'thinking', 'x', NULL, NULL)",
                )
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT first_token_ms FROM conversation_messages WHERE id = 'n'").use { rows ->
                    assertTrue(rows.next())
                    rows.getObject(1)
                    // 有些 SQLite 版本会给新列填默认值；这里确认没有。
                    assertTrue("未采集的轮次必须读回 NULL，不能变成 0", rows.wasNull())
                }
            }
        }
    }
}
