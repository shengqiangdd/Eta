package io.github.mangi.eta.agent.acp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** acp_* 工具的参数解析：纯 JSON，不需要 Android 框架。 */
class AgentAcpToolsTest {

    @Test
    fun schemaDeclaresExactlyTheExecutableTools() {
        val expected = listOf(
            "acp_agent_list", "acp_delegate", "acp_session_status", "acp_session_close",
        )
        assertEquals(expected.sorted(), AgentAcpTools.names.sorted())
    }

    @Test
    fun delegateRequiresTaskAndSaneCwd() {
        assertNull(AgentAcpTools.parseDelegate(JSONObject()))
        assertNull(AgentAcpTools.parseDelegate(JSONObject().put("task", "   ")))
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "重构").put("cwd", "relative/path"),
            ),
        )
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "重构").put("cwd", "/workspace/../etc"),
            ),
        )
        val ok = AgentAcpTools.parseDelegate(
            JSONObject().put("task", "把 Rust 侧帧感知补齐测试").put("cwd", "/workspace"),
        )!!
        assertEquals("把 Rust 侧帧感知补齐测试", ok.task)
        assertEquals("/workspace", ok.cwd)
        assertEquals(AgentAcpTools.DEFAULT_TIMEOUT_SECONDS, ok.timeoutSeconds)
    }

    @Test
    fun omittedCwdUsesTheSessionWorkingDirectory() {
        // 回归：cwd 缺省值原本写死 /workspace，无视会话自己的工作区。
        // 后果是委派在错误目录里改文件，而且它不会报错。
        val parsed = AgentAcpTools.parseDelegate(
            JSONObject().put("task", "改配置"),
            defaultCwd = "/workspace/my-project",
        )!!
        assertEquals("/workspace/my-project", parsed.cwd)

        // 显式给了 cwd 时仍然以显式为准，不被会话目录覆盖。
        val explicit = AgentAcpTools.parseDelegate(
            JSONObject().put("task", "改配置").put("cwd", "/tmp/other"),
            defaultCwd = "/workspace/my-project",
        )!!
        assertEquals("/tmp/other", explicit.cwd)

        // 会话目录为空/空白时回退到 DEFAULT_CWD，不能拼出空路径。
        assertEquals(
            AgentAcpTools.DEFAULT_CWD,
            AgentAcpTools.parseDelegate(JSONObject().put("task", "t"), defaultCwd = "  ")!!.cwd,
        )
    }

    @Test
    fun delegateTaskLengthAndTimeoutBounds() {
        val tooLong = JSONObject().put("task", "x".repeat(AgentAcpTools.MAX_TASK_CHARS + 1))
        assertNull(AgentAcpTools.parseDelegate(tooLong))

        val atLimit = JSONObject().put("task", "x".repeat(AgentAcpTools.MAX_TASK_CHARS))
        assertTrue(AgentAcpTools.parseDelegate(atLimit) != null)

        assertNull(AgentAcpTools.parseDelegate(JSONObject().put("task", "t").put("timeout_seconds", 1)))
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "t").put("timeout_seconds", AgentAcpTools.MAX_TIMEOUT_SECONDS + 1),
            ),
        )
        assertEquals(
            AgentAcpTools.MIN_TIMEOUT_SECONDS,
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "t").put("timeout_seconds", AgentAcpTools.MIN_TIMEOUT_SECONDS),
            )!!.timeoutSeconds,
        )
    }

    @Test
    fun delegateAcceptsPluginNamespacedAgentId() {
        val namespaced = AgentAcpTools.parseDelegate(
            JSONObject().put("task", "重构").put("agent", "dsh-codex:codex"),
        )!!
        assertEquals("dsh-codex:codex", namespaced.agent)
    }

    @Test
    fun delegateRejectsMalformedAgentId() {
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "t").put("agent", "has space"),
            ),
        )
        // 两个冒号无法确定归属，必须拒绝。
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "t").put("agent", "a:b:c"),
            ),
        )
        assertNull(
            AgentAcpTools.parseDelegate(
                JSONObject().put("task", "t").put("agent", ":codex"),
            ),
        )
        assertTrue(
            AgentAcpTools.parseDelegate(JSONObject().put("task", "t").put("agent", "codex-acp")) != null,
        )
    }

    @Test
    fun sessionIdAcceptsAllKeywordAndGeneratedIds() {
        assertEquals("all", AgentAcpTools.parseSession(JSONObject().put("session_id", "ALL"))!!.sessionId)
        val generated = "acp${(1_700_000_000_000L).toString(36)}"
        assertEquals(
            generated,
            AgentAcpTools.parseSession(JSONObject().put("session_id", generated))!!.sessionId,
        )
        assertNull(AgentAcpTools.parseSession(JSONObject().put("session_id", "")))
        assertNull(AgentAcpTools.parseSession(JSONObject().put("session_id", "bad id!")))
        assertNull(AgentAcpTools.parseSession(JSONObject().put("session_id", "x".repeat(121))))
    }
}
