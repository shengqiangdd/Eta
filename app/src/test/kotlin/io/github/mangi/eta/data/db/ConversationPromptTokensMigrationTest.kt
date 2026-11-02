package io.github.mangi.eta.data.db

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 25→26 迁移语句的 JDBC 契约测试（纯 JVM，不需要 Robolectric）。
 *
 * 本机 arm64 跑不了 Robolectric 的 SQLite native，真机升级路径只能靠这一层守：
 * 语句本身在真实 SQLite 上跑得通、旧行拿到的是 NULL（不是 0）、新值能写能读。
 */
class ConversationPromptTokensMigrationTest {

    private val createLegacyTable =
        "CREATE TABLE conversation_messages(" +
            "id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, " +
            "type TEXT NOT NULL, content TEXT NOT NULL, cached_tokens INTEGER)"

    @Test
    fun migrationAddsANullableColumnAndOldRowsStayNull() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(
                    "INSERT INTO conversation_messages VALUES ('old', 'c', 0, 'assistant', '旧回答', 200)",
                )
            }

            connection.createStatement().use { it.executeUpdate(EtaDatabase.SQL_V25_ADD_PROMPT_TOKENS) }

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT prompt_tokens FROM conversation_messages WHERE id = 'old'").use { rows ->
                    assertTrue("迁移后读不到旧行", rows.next())
                    rows.getObject(1)
                    assertTrue("旧行的 prompt_tokens 必须是 NULL，不能是 0", rows.wasNull())
                }
                statement.executeUpdate("UPDATE conversation_messages SET prompt_tokens = 1250 WHERE id = 'old'")
                statement.executeQuery("SELECT prompt_tokens FROM conversation_messages WHERE id = 'old'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1_250, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun migrationIsIdempotentOnlyThroughTheVersionGate() {
        // 同一条语句执行第二次必然失败：这正是必须靠版本号门控、而不能「每次都跑一遍」的原因。
        // 这里把这条约束写下来，免得以后有人把它挪进 onCreate / onOpen。
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(createLegacyTable)
                statement.executeUpdate(EtaDatabase.SQL_V25_ADD_PROMPT_TOKENS)
            }

            val failed = runCatching {
                connection.createStatement().use { it.executeUpdate(EtaDatabase.SQL_V25_ADD_PROMPT_TOKENS) }
            }.isFailure

            assertTrue("重复加列竟然成功：迁移若被放到 onOpen 会每次都炸", failed)
        }
    }
}
