package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentGoalTest {

    @Test
    fun createTrimsObjectiveAndStartsActive() {
        val goal = AgentGoal.create("  把长跑日志分析完  ", now = 1_000L)
        assertEquals("把长跑日志分析完", goal.objective)
        assertEquals(AgentGoalPhase.ACTIVE, goal.phase)
        assertEquals(1, goal.revision)
        assertEquals(1_000L, goal.createdAt)
        assertEquals(1_000L, goal.updatedAt)
        assertNull(goal.blockedReason)
        assertFalse(goal.id.isBlank())
    }

    @Test
    fun createRejectsBlankAndOversizedObjective() {
        for (bad in listOf("", "   ", "x".repeat(AgentGoal.MAX_OBJECTIVE_CHARS + 1))) {
            try {
                AgentGoal.create(bad)
                fail("应拒绝目标：$bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun renderLineShowsPhaseAndBlockedReasonOnlyWhenBlocked() {
        val goal = AgentGoal.create("迁移配置", now = 1L)
        assertEquals("迁移配置（进行中）", goal.renderLine())
        assertEquals("迁移配置（已暂停）", goal.copy(phase = AgentGoalPhase.PAUSED).renderLine())
        assertEquals(
            "迁移配置（受阻）：缺少 release keystore",
            goal.copy(phase = AgentGoalPhase.BLOCKED, blockedReason = "缺少 release keystore").renderLine(),
        )
        // 非 blocked 状态不显示原因，避免状态改了原因还留在界面上。
        assertEquals(
            "迁移配置（进行中）",
            goal.copy(phase = AgentGoalPhase.ACTIVE, blockedReason = "残留原因").renderLine(),
        )
    }

    @Test
    fun promptSectionExplainsItIsNotTheTaskList() {
        val section = AgentGoal.create("交付 v3.3.1").promptSection()
        assertTrue(section.startsWith("<goal>"))
        assertTrue(section.trimEnd().endsWith("</goal>"))
        assertTrue(section.contains("长期目标"))
        assertTrue(section.contains("交付 v3.3.1（进行中）"))
        assertTrue(section.contains("goal update"))
    }

    @Test
    fun storedJsonRoundTrips() {
        val goal = AgentGoal.create("交付 v3.3.1", now = 42L)
            .copy(revision = 7, phase = AgentGoalPhase.BLOCKED, blockedReason = "等 CI")
        assertEquals(goal, AgentGoal.fromStoredJson(goal.toJson().toString()))
    }

    @Test
    fun storedJsonRejectsCorruptOrUnusableRecords() {
        val good = AgentGoal.create("交付 v3.3.1", now = 42L).toJson()
        assertNull(AgentGoal.fromStoredJson(null))
        assertNull(AgentGoal.fromStoredJson(""))
        assertNull(AgentGoal.fromStoredJson("not json"))
        // 逐项破坏：缺 id、revision 0、未知 phase、空目标。
        for (broken in listOf(
            JSONObject(good.toString()).put("id", ""),
            JSONObject(good.toString()).put("revision", 0),
            JSONObject(good.toString()).put("phase", "running"),
            JSONObject(good.toString()).put("objective", "   "),
            JSONObject(good.toString()).put("objective", "x".repeat(AgentGoal.MAX_OBJECTIVE_CHARS + 1)),
        )) {
            assertNull(broken.toString(), AgentGoal.fromStoredJson(broken.toString()))
        }
    }
}
