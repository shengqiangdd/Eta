package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTodoPlanTest {

    @Test
    fun parsesWholeListAndReportsCounts() {
        val plan = AgentTodoPlan.parse(
            array(
                item("读取日志", "completed"),
                item("定位根因", "in_progress"),
                item("补测试", "pending"),
            ),
        )
        assertEquals(3, plan.items.size)
        assertEquals(1, plan.completedCount)
        assertEquals(1, plan.inProgressCount)
        assertEquals(1, plan.pendingCount)
        assertFalse(plan.allCompleted)
        assertEquals("3 项 · 1 进行中 · 1 已完成", plan.summary())
        assertEquals(
            "- [x] 读取日志\n- [~] 定位根因\n- [ ] 补测试",
            plan.renderChecklist(),
        )
    }

    @Test
    fun emptyArrayClearsPlan() {
        val plan = AgentTodoPlan.parse(JSONArray())
        assertTrue(plan.isEmpty)
        assertFalse(plan.allCompleted)
        assertEquals("", plan.renderChecklist())
    }

    @Test
    fun allCompletedIgnoresEmptyList() {
        val plan = AgentTodoPlan.parse(JSONArray().put(item("a", "completed")))
        assertTrue(plan.allCompleted)
        assertTrue(plan.promptSection().contains("- [x] a"))
    }

    @Test
    fun rejectsDuplicateContent() {
        assertEquals(
            "第 2 项与前面的条目重复：a",
            failureMessage(item("a", "pending"), item("a", "completed")),
        )
    }

    @Test
    fun rejectsTwoInProgressItems() {
        assertEquals(
            "最多只能有一项处于 in_progress（收到 2 项）；Eta 的步骤是串行的",
            failureMessage(item("a", "in_progress"), item("b", "in_progress")),
        )
    }

    @Test
    fun rejectsUnknownItemField() {
        assertEquals(
            "第 1 项包含未知字段 `priority`，只接受 content 与 status",
            failureMessage(JSONObject().put("content", "a").put("status", "pending").put("priority", 1)),
        )
    }

    @Test
    fun rejectsBlankContent() {
        assertEquals("第 1 项的 content 不能为空", failureMessage(item("   ", "pending")))
    }

    @Test
    fun rejectsTooManyItems() {
        val items = (0..AgentTodoPlan.MAX_ITEMS).map { item("t$it", "pending") }
        val array = JSONArray().also { target -> items.forEach(target::put) }
        val message = try {
            AgentTodoPlan.parse(array)
            fail("expected IllegalArgumentException")
            ""
        } catch (error: IllegalArgumentException) {
            error.message.orEmpty()
        }
        assertEquals(
            "任务清单最多 ${AgentTodoPlan.MAX_ITEMS} 项（收到 ${items.size} 项），请合并步骤",
            message,
        )
    }

    @Test
    fun rejectsUnknownStatus() {
        assertEquals(
            "第 1 项的 status 只能是 pending、in_progress 或 completed（收到 doing）",
            failureMessage(item("a", "doing")),
        )
    }

    @Test
    fun storedJsonRoundTrips() {
        val plan = AgentTodoPlan.parse(
            JSONArray().put(item("a", "completed")).put(item("b", "pending")),
        )
        val restored = AgentTodoPlan.fromStoredJson(plan.toJson().toString())
        assertEquals(plan, restored)
    }

    @Test
    fun corruptStoredJsonIsTreatedAsNoPlan() {
        assertNull(AgentTodoPlan.fromStoredJson(null))
        assertNull(AgentTodoPlan.fromStoredJson(""))
        assertNull(AgentTodoPlan.fromStoredJson("not json"))
        assertNull(AgentTodoPlan.fromStoredJson("""{"todos":"nope"}"""))
        // 旧版本或坏文件里出现两项 in_progress 时按「没有清单」处理，而不是让会话启动失败。
        assertNull(
            AgentTodoPlan.fromStoredJson(
                """{"todos":[{"content":"a","status":"in_progress"},{"content":"b","status":"in_progress"}]}""",
            ),
        )
    }

    private fun array(vararg items: JSONObject): JSONArray =
        JSONArray().also { target -> items.forEach(target::put) }

    private fun item(content: String, status: String): JSONObject =
        JSONObject().put("content", content).put("status", status)

    private fun failureMessage(vararg items: JSONObject): String {
        val array = JSONArray().also { target -> items.forEach(target::put) }
        return try {
            AgentTodoPlan.parse(array)
            fail("expected IllegalArgumentException")
            ""
        } catch (error: IllegalArgumentException) {
            error.message.orEmpty()
        }
    }
}
