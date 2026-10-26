package io.github.mangi.eta.data.db

import java.sql.DriverManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 全库内容搜索的 SQL 契约测试（纯 JVM + JDBC，不依赖 Robolectric）。
 *
 * 为什么需要它：这个查询的正确性此前只能在真机上验证（Robolectric 的 SQLite native
 * 只有 x86_64，本机 arm64 跑不了），而它恰好在 2026-10-20 被发现漏掉了
 * tool 消息的四个字段 —— 真机上有 542 条只在 result_summary 里命中「继续」的消息，
 * SQL 一条都搜不到，界面表现为「切到别的会话就搜不到刚才还看得到的内容」。
 *
 * 这里用 JDBC 的 in-memory SQLite 建一张与 Room schema 同形的表，
 * 把 DAO 上的 @Query 原文取出来直接跑，守三件事：
 *   1. 内存侧 `AgentChatMessageUi.matches` 覆盖的字段，SQL 必须全部覆盖；
 *   2. content 里的 @eta:chunks 引用行不能被当成正文命中（分块正文另算）；
 *   3. LIKE 的 % _ \ 转义后仍是字面量。
 */
class ConversationSearchSqlContractTest {

    /**
     * 从 DAO 源码里取 @Query 原文。
     *
     * 为什么不反射：`androidx.room.Query` 的 retention 是 CLASS（不是 RUNTIME），
     * 编译后字节码里可见但运行时 `getAnnotation` 取不到 —— 第一次实现走反射，
     * 8 个用例全部挂在「找不到 @Query」上。
     * 读源码反而更贴近意图：这里守的就是「写下来的这条 SQL」。
     */
    private val sql: String = run {
        val source = File(
            "src/main/kotlin/io/github/mangi/eta/data/db/ConversationDao.kt",
        )
        assumeTrue("找不到 DAO 源码：${source.absolutePath}", source.isFile)
        val text = source.readText()
        val start = text.indexOf("fun searchConversationHits")
        assumeTrue("DAO 里没有 searchConversationHits", start >= 0)
        // 从方法声明往回找最近的 @Query(...)，取其括号内的字符串拼接。
        val queryStart = text.lastIndexOf("@Query(", start)
        assumeTrue("searchConversationHits 上没有 @Query", queryStart >= 0)
        val open = text.indexOf('(', queryStart)
        val close = text.indexOf("\n    )", open)
        assumeTrue("@Query 参数没有正常结束", close > open)
        text.substring(open + 1, close)
            .lines()
            .map { it.trim() }
            // Kotlin 里这条 SQL 是多个 "…" 用 + 拼起来的；只取引号内的内容，
            // 丢掉 + 、逗号与行尾注释，再把源码层的 \" 还原成单引号形式。
            .filter { it.startsWith("\"") }
            .joinToString("") { line ->
                line.removePrefix("\"")
                    .substringBefore("\"")
                    .replace("\\\\", "\\")
            }
    }

    private fun connection(): java.sql.Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").also { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE conversation_messages(
                      id TEXT PRIMARY KEY,
                      conversation_id TEXT NOT NULL,
                      sort_index INTEGER NOT NULL,
                      type TEXT NOT NULL,
                      content TEXT NOT NULL DEFAULT '',
                      tool_name TEXT,
                      tool_status TEXT,
                      arguments_summary TEXT,
                      result_summary TEXT,
                      images_json TEXT DEFAULT '',
                      tools_json TEXT DEFAULT '',
                      is_edited INTEGER DEFAULT 0,
                      render_markdown INTEGER,
                      context_tokens INTEGER,
                      input_tokens INTEGER,
                      output_tokens INTEGER,
                      reasoning_tokens INTEGER,
                      cached_tokens INTEGER,
                      elapsed_seconds REAL,
                      image_count INTEGER DEFAULT 0
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE agent_text_chunks(
                      owner_table TEXT NOT NULL,
                      owner_id TEXT NOT NULL,
                      field TEXT NOT NULL,
                      chunk_index INTEGER NOT NULL,
                      content TEXT NOT NULL,
                      PRIMARY KEY(owner_table, owner_id, field, chunk_index)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun java.sql.Connection.insertMessage(
        id: String,
        conversationId: String,
        sortIndex: Int,
        type: String,
        content: String,
        toolName: String? = null,
        argumentsSummary: String? = null,
        resultSummary: String? = null,
        toolsJson: String? = null,
    ) {
        prepareStatement(
            "INSERT INTO conversation_messages(id, conversation_id, sort_index, type, content," +
                " tool_name, arguments_summary, result_summary, tools_json) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, conversationId)
            ps.setInt(3, sortIndex)
            ps.setString(4, type)
            ps.setString(5, content)
            ps.setString(6, toolName)
            ps.setString(7, argumentsSummary)
            ps.setString(8, resultSummary)
            ps.setString(9, toolsJson)
            ps.executeUpdate()
        }
    }

    private fun java.sql.Connection.insertChunk(
        ownerId: String,
        field: String,
        chunkIndex: Int,
        content: String,
    ) {
        prepareStatement(
            "INSERT INTO agent_text_chunks(owner_table, owner_id, field, chunk_index, content)" +
                " VALUES('conversation_messages', ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, field)
            ps.setInt(3, chunkIndex)
            ps.setString(4, content)
            ps.executeUpdate()
        }
    }

    private fun java.sql.Connection.search(like: String): Map<String, Int> {
        prepareStatement(sql).use { ps ->
            ps.setString(1, like)
            ps.executeQuery().use { rs ->
                val out = linkedMapOf<String, Int>()
                while (rs.next()) out[rs.getString("conversationId")] = rs.getInt("matchCount")
                return out
            }
        }
    }

    @Test
    fun toolFieldsAreSearchable() = connection().use { conn ->
        conn.insertMessage("m1", "conv-a", 0, "tool", "ls -la", toolName = "terminal")
        conn.insertMessage("m2", "conv-a", 1, "tool", "", resultSummary = "命令输出里有继续推进四个字")
        conn.insertMessage("m3", "conv-a", 2, "tool", "", argumentsSummary = """{"path":"继续"}""")
        conn.insertMessage("m4", "conv-a", 3, "tool_summary", "", toolsJson = """["observe","继续"]""")
        conn.insertMessage("m5", "conv-b", 0, "assistant", "正文里也有继续")

        // content 只在 m5 命中；conv-a 的三条全靠 tool 专属字段。
        assertEquals(mapOf("conv-a" to 3, "conv-b" to 1), conn.search("%继续%"))
    }

    @Test
    fun toolNameAloneIsSearchable() = connection().use { conn ->
        conn.insertMessage("m1", "conv-a", 0, "tool", "", toolName = "observe_screen")
        assertEquals(mapOf("conv-a" to 1), conn.search("%observe_screen%"))
    }

    @Test
    fun noticeDetailIsSearchable() = connection().use { conn ->
        // system_notice 的 content 是 wireValue，detail 落在 result_summary。
        conn.insertMessage("m1", "conv-a", 0, "system_notice", "model_retry", resultSummary = "模型请求暂时中断")
        assertEquals(mapOf("conv-a" to 1), conn.search("%暂时中断%"))
    }

    @Test
    fun chunkedBodyIsSearchableButReferenceRowIsNot() = connection().use { conn ->
        // 长正文入库后 content 只留引用串；真身进 chunks。
        conn.insertMessage("m1", "conv-a", 0, "thinking", "@eta:chunks:v1:2:32000")
        conn.insertChunk("m1", "content", 0, "前半段闲聊")
        conn.insertChunk("m1", "content", 1, "后半段提到工作区")

        assertEquals(mapOf("conv-a" to 1), conn.search("%工作区%"))
        // 引用串自身不能算命中。
        assertEquals(emptyMap<String, Int>(), conn.search("%eta:chunks%"))
        assertEquals(emptyMap<String, Int>(), conn.search("%v1%"))
        // 分块正文仍可命中。
        assertEquals(mapOf("conv-a" to 1), conn.search("%闲聊%"))
    }

    @Test
    fun likeMetacharactersStayLiteral() = connection().use { conn ->
        conn.insertMessage("m1", "conv-a", 0, "assistant", "折扣 50% 起")
        conn.insertMessage("m2", "conv-a", 1, "assistant", "字段 foo_bar")
        conn.insertMessage("m3", "conv-a", 2, "assistant", """路径 C:\tmp""")

        assertEquals(mapOf("conv-a" to 1), conn.search("%50\\%%"))
        assertEquals(mapOf("conv-a" to 1), conn.search("%\\%%"))
        assertEquals(mapOf("conv-a" to 1), conn.search("%foo\\_bar%"))
        assertEquals(mapOf("conv-a" to 1), conn.search("%C:\\\\tmp%"))
        // 未命中时返回空，不是全库。
        assertEquals(emptyMap<String, Int>(), conn.search("%不存在%"))
    }

    @Test
    fun asciiIsCaseInsensitiveLikeMemorySide() = connection().use { conn ->
        conn.insertMessage("m1", "conv-a", 0, "tool", "", resultSummary = "DONE ok")
        assertEquals(mapOf("conv-a" to 1), conn.search("%done%"))
    }

    @Test
    fun everyFieldMemorySideMatchesAppearsInSql() {
        // 内存侧 ConversationSearch.matches 覆盖的列：
        // user: content（images 是 base64，两侧都不搜）
        // assistant / thinking: content
        // tool: tool_name, command(→content), arguments_summary, result_summary, tools_json
        // notice: noticeText(不落库) + detail(→result_summary)
        val required = listOf(
            "content", "tool_name", "arguments_summary", "result_summary", "tools_json",
            "agent_text_chunks",
        )
        required.forEach { column ->
            assertTrue(
                "SQL 里必须出现 $column —— 内存侧匹配它，SQL 漏掉就是「换个会话就搜不到」",
                sql.contains(column),
            )
        }
    }

    @Test
    fun imagesJsonIsNotSearched() {
        // 里面是 base64 data URL，搜它只会带来噪声命中。
        assertTrue("images_json 不应参与匹配", !sql.contains("images_json"))
    }
}
