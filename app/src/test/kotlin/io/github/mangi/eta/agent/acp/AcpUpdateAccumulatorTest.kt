package io.github.mangi.eta.agent.acp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 委派的结构化汇总。
 *
 * 回归点（PKX110，2026-10-27）：一次 90 秒、两步的委派，回传里只有 `text` 与一行流水账，
 * 卡片上只剩「完成」两个字。步骤 / 每步耗时 / token 用量都被丢掉了。
 */
class AcpUpdateAccumulatorTest {

    private var now = 1_000L
    private fun accumulator() = AcpUpdateAccumulator { now }

    private fun toolCall(id: String, title: String, kind: String, status: String = "pending") =
        JSONObject()
            .put("sessionUpdate", "tool_call")
            .put("toolCallId", id)
            .put("title", title)
            .put("kind", kind)
            .put("status", status)

    private fun toolCallUpdate(id: String, status: String, detail: String? = null) =
        JSONObject()
            .put("sessionUpdate", "tool_call_update")
            .put("toolCallId", id)
            .put("status", status)
            .apply {
                if (detail != null) {
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "content")
                                .put("content", JSONObject().put("type", "text").put("text", detail)),
                        ),
                    )
                }
            }

    @Test
    fun `tool call and its completion become one step with elapsed time`() {
        val acc = accumulator()
        acc.onUpdate(toolCall("c1", "sleep 50", "execute", "in_progress"))
        now = 52_000L
        acc.onUpdate(toolCallUpdate("c1", "completed", "exit code 0"))

        val steps = acc.snapshot()
        assertEquals(1, steps.size)
        assertEquals("sleep 50", steps[0].title)
        assertEquals("completed", steps[0].status)
        assertEquals(51_000L, steps[0].endedAtMs!! - steps[0].startedAtMs)
        assertEquals("exit code 0", steps[0].detail)
    }

    @Test
    fun `repeated tool_call for the same id keeps the original start time`() {
        val acc = accumulator()
        acc.onUpdate(toolCall("c1", "Search *.kt", "search"))
        now = 5_000L
        acc.onUpdate(toolCall("c1", "Search *.kt", "search", "in_progress"))

        val steps = acc.snapshot()
        assertEquals(1, steps.size)
        assertEquals(1_000L, steps[0].startedAtMs)
        assertEquals("in_progress", steps[0].status)
    }

    @Test
    fun `usage and plan are recorded`() {
        val acc = accumulator()
        acc.onUpdate(JSONObject().put("sessionUpdate", "usage_update").put("used", 30_176).put("size", 258_400))
        acc.onUpdate(
            JSONObject().put("sessionUpdate", "plan").put(
                "entries",
                JSONArray()
                    .put(JSONObject().put("content", "a").put("status", "completed"))
                    .put(JSONObject().put("content", "b").put("status", "in_progress")),
            ),
        )

        assertEquals(30_176L, acc.usageUsed)
        assertEquals(258_400L, acc.usageSize)
        assertEquals(2, acc.planTotal)
        assertEquals(1, acc.planDone)
    }

    @Test
    fun `failed step is marked in the payload`() {
        val acc = accumulator()
        acc.onUpdate(toolCall("c1", "run tests", "execute"))
        now = 2_000L
        acc.onUpdate(toolCallUpdate("c1", "failed"))

        val json = acc.stepsJson()
        assertEquals(1, json.length())
        assertEquals("failed", json.getJSONObject(0).getString("status"))
    }

    @Test
    fun `unfinished step reports -1 instead of a fake duration`() {
        val acc = accumulator()
        acc.onUpdate(toolCall("c1", "long task", "execute", "in_progress"))
        now = 90_000L

        val step = acc.stepsJson().getJSONObject(0)
        assertEquals(-1L, step.getLong("elapsed_ms"))
        assertTrue(step.getString("title") == "long task")
    }
}
