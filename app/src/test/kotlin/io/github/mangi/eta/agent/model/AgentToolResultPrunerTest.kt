package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧工具结果的回收。
 *
 * 真机 trace（`run-f64201f5`）：
 * ```
 * r1: msgs=1031  in=410,751  out=1,708
 * r2: msgs=1034  in=411,546  out=564   cache=410,624
 * ```
 * 每轮推 41 万 token、输出一二百 token，缓存命中 99.8% —— 不是缓存坏了，
 * 是 1031 条消息里塞满了历史工具输出（单条 `res_chars` 常在一千上下）。
 */
class AgentToolResultPrunerTest {

    private fun toolMessage(content: String): JSONObject =
        JSONObject().put("role", "tool").put("content", content)

    private fun history(count: Int, content: String = "x".repeat(600)): JSONArray =
        JSONArray().also { array -> repeat(count) { array.put(toolMessage(content)) } }

    @Test
    fun oldLargeToolResultsAreReplacedWithPlaceholder() {
        val messages = history(30)

        val pruned = AgentToolResultPruner.prune(messages, keepTail = 10)

        assertEquals("末尾之外的 20 条都应被回收", 20, pruned)
        for (index in 0 until 20) {
            assertEquals(AgentToolResultPruner.PRUNED_PLACEHOLDER, messages.getJSONObject(index).getString("content"))
        }
        // 末尾 10 条原样保留：模型正在用它们。
        for (index in 20 until 30) {
            assertEquals(600, messages.getJSONObject(index).getString("content").length)
        }
    }

    @Test
    fun smallResultsAreLeftAlone() {
        val messages = history(30, content = "ok")

        val pruned = AgentToolResultPruner.prune(messages, keepTail = 10)

        assertEquals("占位本身也占字符，比它短的结果换掉没有收益", 0, pruned)
        assertEquals("ok", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun runningTwiceChangesNothingTheSecondTime() {
        val messages = history(30)

        val first = AgentToolResultPruner.prune(messages, keepTail = 10)
        val second = AgentToolResultPruner.prune(messages, keepTail = 10)

        assertEquals(20, first)
        // 幂等是硬要求：每轮都改写历史会把前缀缓存全部打碎。
        assertEquals("第二次不该再改动任何东西", 0, second)
    }

    @Test
    fun toolCallPairingSurvivesBecauseMessagesAreNotDropped() {
        val messages = history(30)

        AgentToolResultPruner.prune(messages, keepTail = 10)

        // 只换内容、不删消息：删掉会让 tool_call 与 tool_result 配不上对，端点直接报错。
        assertEquals(30, messages.length())
        for (index in 0 until 30) {
            assertEquals("tool", messages.getJSONObject(index).getString("role"))
        }
    }

    @Test
    fun anthropicStyleToolResultBlocksArePrunedToo() {
        val big = "y".repeat(600)
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("content", big),
                    ),
                ),
            )
            .also { array -> repeat(25) { array.put(toolMessage("z".repeat(600))) } }

        val pruned = AgentToolResultPruner.prune(messages, keepTail = 20)

        assertTrue("至少回收了 Anthropic 形状的那一条", pruned >= 1)
        val first = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals(AgentToolResultPruner.PRUNED_PLACEHOLDER, first.getString("content"))
    }

    @Test
    fun keepTailLargerThanHistoryIsSafe() {
        val messages = history(5)

        val pruned = AgentToolResultPruner.prune(messages, keepTail = 100)

        assertEquals(0, pruned)
        assertFalse(messages.getJSONObject(0).getString("content").isEmpty())
    }
}
