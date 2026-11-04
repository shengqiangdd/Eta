package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败码 → 改法。
 *
 * 这里守两件事：
 * 1. **真实出现过的码都有专属改法**（不能落到兜底），否则「知道怎么改」就是空话；
 * 2. 改法必须是**能执行的动作**，不是「注意一下」这类正确的废话。
 */
class AgentFailureHintsTest {

    /** 本仓 72 份真实运行追踪里实际出现过的失败码。 */
    private val realCodes = listOf(
        "READ_REQUIRED", "FILE_CHANGED", "MATCH_NOT_FOUND", "AMBIGUOUS_MATCH", "NO_CHANGES",
        "FILE_TOO_LARGE", "INVALID_ARGUMENT", "NOT_DIRECTORY", "FILE_NOT_FOUND", "NOT_REGULAR_FILE",
        "STALE_CONTENT", "NO_OBSERVATION", "STALE_OBSERVATION", "MAIN_INPUT_ACTIVE",
        "APP_ALREADY_RUNNING", "ALREADY_ON_TARGET_DISPLAY", "VIRTUAL_DISPLAY_NOT_FOUND",
        "SEARCH_CHALLENGE", "ACP_PROTOCOL_ERROR", "ACP_DELEGATION_DISABLED",
        "TOOL_NOT_AVAILABLE", "INVALID_QUERY",
    )

    @Test
    fun `真实出现过的每个码都被认为有专属改法`() {
        realCodes.forEach { code ->
            assertTrue("$code 应有专属改法", AgentFailureHints.hasSpecificFix(code))
        }
    }

    @Test
    fun `每个码给出的改法都非空且互不相同`() {
        val fixes = realCodes.associateWith { AgentFailureHints.fixFor("edit_file", it) }
        fixes.forEach { (code, fix) ->
            assertTrue("$code 的改法不能为空", fix.isNotBlank())
            // 「注意一下 / 小心 / 检查一下」这类话没有可执行内容。
            assertFalse("$code 的改法太笼统：$fix", fix.contains("注意一下"))
            assertFalse("$code 的改法太笼统：$fix", fix == "检查一下")
        }
        assertEquals("不同码应给出不同改法", realCodes.size, fixes.values.distinct().size)
    }

    @Test
    fun `改法指向具体动作而不是抽象建议`() {
        // 抽查几条最能体现「可执行」的：必须出现具体工具名或参数名。
        assertTrue(AgentFailureHints.fixFor("edit_file", "READ_REQUIRED").contains("read_file"))
        assertTrue(AgentFailureHints.fixFor("tap_element", "NO_OBSERVATION").contains("observation_id"))
        assertTrue(AgentFailureHints.fixFor("edit_file", "FILE_TOO_LARGE").contains("512 KiB"))
        assertTrue(AgentFailureHints.fixFor("web_search", "SEARCH_CHALLENGE").contains("fetch_url"))
    }

    @Test
    fun `未知码走按工具的兜底且不算专属`() {
        val fix = AgentFailureHints.fixFor("terminal", "SOMETHING_NEW")
        assertTrue(fix.isNotBlank())
        assertTrue(fix.contains("stderr"))
        assertFalse(AgentFailureHints.hasSpecificFix("SOMETHING_NEW"))
    }

    @Test
    fun `没有 code 时按工具兜底且不算专属`() {
        assertFalse(AgentFailureHints.hasSpecificFix(null))
        // 即使没有 code，也要给出一句能用的改法。
        assertTrue(AgentFailureHints.fixFor("terminal", null).isNotBlank())
        assertTrue(AgentFailureHints.fixFor("edit_file", null).contains("read_file"))
    }

    @Test
    fun `未知工具且未知码也永远不返回空串`() {
        assertTrue(AgentFailureHints.fixFor("some_future_tool", null).isNotBlank())
        assertTrue(AgentFailureHints.fixFor("some_future_tool", "WEIRD").isNotBlank())
    }
}
