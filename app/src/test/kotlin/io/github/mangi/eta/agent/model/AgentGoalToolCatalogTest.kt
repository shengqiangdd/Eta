package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentGoalToolCatalogTest {

    private fun args(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().also { json ->
        pairs.forEach { (key, value) -> json.put(key, value) }
    }

    private fun failureMessage(vararg pairs: Pair<String, Any>): String = try {
        AgentGoalToolCatalog.parse(args(*pairs))
        fail("应拒绝参数：${args(*pairs)}")
        ""
    } catch (expected: IllegalArgumentException) {
        expected.message.orEmpty()
    }

    @Test
    fun parsesReadOnlyActions() {
        assertEquals(AgentGoalToolCatalog.Command.Get, AgentGoalToolCatalog.parse(args("action" to "get")))
        assertEquals(AgentGoalToolCatalog.Command.Clear, AgentGoalToolCatalog.parse(args("action" to "clear")))
    }

    @Test
    fun createTrimsObjectiveAndRequiresIt() {
        val create = AgentGoalToolCatalog.parse(args("action" to "create", "objective" to "  交付 v3.3.1  "))
        assertEquals(AgentGoalToolCatalog.Command.Create("交付 v3.3.1"), create)
        assertEquals("create 需要 objective", failureMessage("action" to "create", "objective" to "   "))
        assertEquals(
            "objective 超过 ${AgentGoal.MAX_OBJECTIVE_CHARS} 字",
            failureMessage("action" to "create", "objective" to "x".repeat(AgentGoal.MAX_OBJECTIVE_CHARS + 1)),
        )
    }

    @Test
    fun updateNeedsRevision() {
        val message = "update 需要 revision（先用 action=get 读取）"
        assertEquals(message, failureMessage("action" to "update", "state" to "pause"))
        assertEquals(message, failureMessage("action" to "update", "revision" to 0))
        assertEquals(message, failureMessage("action" to "update", "revision" to -3))
        // revision 合法、不带 state 也允许：只用于校验当前 revision（幂等读）。
        assertEquals(
            AgentGoalToolCatalog.Command.Update(1, null, null, null),
            AgentGoalToolCatalog.parse(args("action" to "update", "revision" to 1)),
        )
    }

    @Test
    fun editWithoutNewObjectiveIsRejected() {
        assertEquals(
            "state=edit 需要新的 objective",
            failureMessage("action" to "update", "revision" to 1, "state" to "edit"),
        )
        val edited = AgentGoalToolCatalog.parse(
            args("action" to "update", "revision" to 2, "state" to "edit", "objective" to "新目标"),
        )
        assertEquals(
            AgentGoalToolCatalog.Command.Update(2, AgentGoalToolCatalog.UpdateState.EDIT, "新目标", null),
            edited,
        )
    }

    @Test
    fun parsesEveryStateAndRejectsUnknownOnes() {
        AgentGoalToolCatalog.UpdateState.entries.forEach { state ->
            // edit 必须带新目标，其余状态不能顺手带 blocked_reason。
            val objective = if (state == AgentGoalToolCatalog.UpdateState.EDIT) "新目标" else null
            val command = AgentGoalToolCatalog.parse(
                args("action" to "update", "revision" to 3, "state" to state.wire).also { json ->
                    objective?.let { json.put("objective", it) }
                },
            )
            assertEquals(
                AgentGoalToolCatalog.Command.Update(3, state, objective, null),
                command,
            )
        }
        assertEquals(
            "state 只能是 edit/pause/resume/complete/blocked",
            failureMessage("action" to "update", "revision" to 1, "state" to "running"),
        )
    }

    @Test
    fun blockedReasonOnlyWithBlockedState() {
        val blocked = AgentGoalToolCatalog.parse(
            args("action" to "update", "revision" to 1, "state" to "blocked", "blocked_reason" to "等 CI 结果"),
        )
        assertEquals(
            AgentGoalToolCatalog.Command.Update(1, AgentGoalToolCatalog.UpdateState.BLOCKED, null, "等 CI 结果"),
            blocked,
        )
        assertEquals(
            "blocked_reason 只能与 state=blocked 一起使用",
            failureMessage("action" to "update", "revision" to 1, "state" to "pause", "blocked_reason" to "等 CI"),
        )
    }

    @Test
    fun rejectsUnknownFieldsAndActions() {
        assertTrue(
            failureMessage("action" to "get", "goal_id" to "x").contains("goal 不接受未知字段"),
        )
        assertEquals(
            "action 只能是 get/create/update/clear（收到 空值）",
            failureMessage("action" to "  "),
        )
        assertEquals(
            "action 只能是 get/create/update/clear（收到 done）",
            failureMessage("action" to "done"),
        )
    }

    @Test
    fun schemaIsAvailableInCatalogAndSurvivesPlanMode() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val goal = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .firstOrNull { it.getJSONObject("function").getString("name") == AgentGoalToolCatalog.NAME }
        assertTrue("工具目录里应有 goal", goal != null)
        val function = goal!!.getJSONObject("function")
        assertEquals(
            listOf("action"),
            function.getJSONObject("parameters").getJSONArray("required").let { array ->
                (0 until array.length()).map(array::getString)
            },
        )
        assertEquals(
            listOf("get", "create", "update", "clear"),
            function.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("action").getJSONArray("enum").let { array ->
                    (0 until array.length()).map(array::getString)
                },
        )
        val planned = AgentPlanModePolicy.retainReadOnly(tools)
        assertTrue(
            "计划模式仍需读写目标：它是本机记录，不改设备状态",
            (0 until planned.length()).any {
                planned.getJSONObject(it).getJSONObject("function").getString("name") == AgentGoalToolCatalog.NAME
            },
        )
    }
}
