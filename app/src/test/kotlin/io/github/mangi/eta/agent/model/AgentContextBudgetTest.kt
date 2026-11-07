package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文预算与摘要切分的纯 JVM 用例。
 *
 * 依据来自真机事故（PKX110 / Android 16，2026-10-19）：
 * 会话历史 2.79M 字符、1,143 条消息里 1,140 条没有 input_tokens，
 * 压缩从未触发，最终在整份序列化时申请 11,161,552 字节失败。
 */
class AgentContextBudgetTest {

    @Test
    fun tinyContextSkipsManualCompaction() {
        // canary 真机 2026-10-25：1,796 字符点「压缩上下文」照样发出一次完整摘要请求
        // （out 18,946 token、约 2.5 分钟），最后 CONTEXT_NO_REDUCTION。
        val note = AgentContextBudget.manualCompactionSkipNote(1_796L)
        assertTrue("应给出跳过说明：$note", note != null)
        assertTrue("说明里要有真实字符数：$note", note!!.contains("1,796"))
        assertTrue("要讲清是无需压缩：$note", note.contains("无需压缩"))
    }

    @Test
    fun manualCompactionStillRunsAtOrAboveTheFloor() {
        assertNull(AgentContextBudget.manualCompactionSkipNote(AgentContextBudget.MIN_MANUAL_COMPACT_CHARS))
        assertNull(AgentContextBudget.manualCompactionSkipNote(200_000L))
    }

    @Test
    fun plainTextMessageCountsItsContent() {
        val message = JSONObject().put("role", "assistant").put("content", "a".repeat(1234))
        assertEquals(1234L, AgentContextBudget.messageChars(message))
    }

    @Test
    fun multimodalMessageCountsTextAndImageWithoutSerializing() {
        // 图片以 data URL 内联；这里只统计长度，必须精确、且不产生整份 JSON 字符串。
        val dataUrl = "data:image/png;base64," + "A".repeat(5000)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "看这张图"))
            .put(JSONObject().put("type", "image_url").put("image_url", dataUrl))
        val message = JSONObject().put("role", "user").put("content", content)
        assertEquals(4L + dataUrl.length, AgentContextBudget.messageChars(message))
    }

    @Test
    fun reasoningAndToolArgumentsAreCounted() {
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "ab")
            .put("reasoning_content", "思考".repeat(10))
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "c1").put("type", "function").put(
                        "function",
                        JSONObject().put("name", "terminal").put("arguments", "x".repeat(300)),
                    ),
                ),
            )
        assertEquals(2L + 20 + 300, AgentContextBudget.messageChars(message))
    }

    @Test
    fun contextCharsStartsAfterSystemPrompt() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s".repeat(50)))
            .put(JSONObject().put("role", "user").put("content", "u".repeat(10)))
            .put(JSONObject().put("role", "assistant").put("content", "a".repeat(5)))
        assertEquals(65L, AgentContextBudget.contextChars(messages))
        assertEquals(15L, AgentContextBudget.contextChars(messages, 1))
    }

    @Test
    fun forcedCompactionAlwaysRuns() {
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = true, autoCompactionEnabled = false, contextWindow = null,
                observedInputTokens = null, historyChars = 0,
            ),
        )
    }

    @Test
    fun smallHistoryWithoutUsageDoesNotCompact() {
        // 既有语义：拿不到远端 usage 时，小幅超出窗口的会话不压缩。
        assertFalse(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 128_000,
                observedInputTokens = null, historyChars = 160_000,
            ),
        )
    }

    @Test
    fun historyBeyondHardLimitCompactsWithoutUsageOrWindow() {
        // 新兜底：端点忽略 include_usage 时，只有本地字符数能拦住无限增长。
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = null,
                historyChars = AgentContextBudget.HARD_CONTEXT_CHARS + 1,
            ),
        )
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = false, contextWindow = null,
                observedInputTokens = null,
                historyChars = AgentContextBudget.HARD_CONTEXT_CHARS + 1,
            ),
        )
    }

    @Test
    fun hardLimitIsNotCrossedAtExactBoundary() {
        assertFalse(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = false, contextWindow = null,
                observedInputTokens = null,
                historyChars = AgentContextBudget.HARD_CONTEXT_CHARS,
            ),
        )
    }

    /**
     * 窗口未知时也必须压缩 —— 这是真机现场。
     *
     * 2026-11 的 `run-f1358776`：22 轮、1422 条消息、21.4 万 token，**一次压缩都没触发**。
     * 原因就是这条：模型配置里没填 `contextWindow`（绝大多数用户不填），
     * 旧代码直接 `return false`，只剩 1.2M 字符的安全阀，而那已经是序列化会爆堆的量级。
     */
    @Test
    fun unknownWindowStillCompactsAtTheAssumedBudget() {
        val assumed = AgentContextBudget.DEFAULT_ASSUMED_CONTEXT_WINDOW
        val nearBudget = (assumed * AgentContextBudget.TRIGGER_RATIO).toInt()

        assertTrue(
            "窗口未知 + 已达保守预算时应该压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = nearBudget,
                historyChars = 10_000,
            ),
        )
        assertFalse(
            "远低于预算时不该白花一次摘要请求",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = nearBudget / 4,
                historyChars = 10_000,
            ),
        )
    }

    @Test
    fun largeWindowRaisesTheHardLimit() {
        // 900K token 的模型对应 1.8M 字符，不该被 1.2M 的默认上限提前打断。
        assertEquals(1_800_000L, AgentContextBudget.hardLimitFor(900_000))
        assertEquals(AgentContextBudget.HARD_CONTEXT_CHARS, AgentContextBudget.hardLimitFor(null))
        assertEquals(AgentContextBudget.HARD_CONTEXT_CHARS, AgentContextBudget.hardLimitFor(128_000))
        assertFalse(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 900_000,
                observedInputTokens = null, historyChars = 1_300_000,
            ),
        )
    }

    @Test
    fun observedUsageStillDrivesWindowRatio() {
        val window = 128_000
        assertFalse(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = 100_000, historyChars = 1_000,
            ),
        )
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = 110_000, historyChars = 1_000,
            ),
        )
    }

    @Test
    fun estimatedTokensNeverOverflowOnHugeHistories() {
        // 2.79M 字符对应的估算必须明显超过任何窗口，否则兜底失效。
        assertEquals(697_500L, AgentContextBudget.estimateTokens(2_790_000L))
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
                observedInputTokens = null, historyChars = 2_790_000,
            ),
        )
    }

    @Test
    fun realSessionRatioDoesNotTriggerCompactionEarly() {
        // 真机数据（2026-10-23 生产库）：本会话 history 440,850 字符，端点回报 ctx=92,714，
        // 实际只占 256K 窗口的 36%。旧除数 2 会把它估成 220,425（86%），在没有 usage 的
        // 端点上白白触发一次压缩；现在按 4 折算为 110,212，仍在 85% 触发线以下。
        assertFalse(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 256_000,
                observedInputTokens = null, historyChars = 440_850,
            ),
        )
    }

    @Test
    fun smallHistoryStaysInOneBatch() {
        val history = (1..6).map {
            AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(1000))
        }
        val plan = SummaryBatching.plan(history)
        assertEquals(1, plan.batches.size)
        assertEquals(0L, plan.omittedChars)
        assertFalse(plan.requiresMerge)
    }

    @Test
    fun largeHistoryIsSplitAndKeepsTheNewestMessages() {
        // 10 × 100,000 = 1,000,000 字符：每批 240,000 装 2 条，共 4 批 → 省略 2 条。
        val history = (1..10).map { index ->
            AgentModelClient.ConversationMessage("assistant", "#$index" + "a".repeat(100_000))
        }
        val plan = SummaryBatching.plan(history)
        assertEquals(SummaryBatching.MAX_BATCHES, plan.batches.size)
        // 省略的恰好是最老的两条。
        assertEquals(AgentContextBudget.messagesChars(history.take(2)), plan.omittedChars)
        assertTrue(plan.requiresMerge)
        // 批次按时间顺序排列，最后一批的最后一条必须是原历史的最新一条。
        assertEquals(history.last(), plan.batches.last().last())
        assertEquals(history[8], plan.batches.last().first())
        plan.batches.forEach { batch ->
            val chars = AgentContextBudget.messagesChars(batch)
            assertTrue("每批不得超过预算：$chars", chars <= SummaryBatching.MAX_BATCH_CHARS)
        }
        assertEquals(plan.omittedChars + plan.batches.sumOf { AgentContextBudget.messagesChars(it) },
            AgentContextBudget.messagesChars(history))
    }

    @Test
    fun singleOversizedMessageDoesNotLoopForever() {
        val huge = AgentModelClient.ConversationMessage("tool", "x".repeat(300_000))
        val plan = SummaryBatching.plan(listOf(huge))
        assertEquals(1, plan.batches.size)
        assertEquals(0L, plan.omittedChars)
        assertEquals(huge, plan.batches.single().single())
    }

    @Test
    fun batchingCapsBatchCountForVeryLargeHistory() {
        // 25 × 200,000 = 5,000,000 字符：每批只装得下 1 条，最多 4 批。
        val history = (1..25).map {
            AgentModelClient.ConversationMessage("assistant", "a".repeat(200_000))
        }
        val plan = SummaryBatching.plan(history)
        assertEquals(SummaryBatching.MAX_BATCHES, plan.batches.size)
        assertEquals(4_200_000L, plan.omittedChars)
        assertEquals(history.last(), plan.batches.last().last())
    }
}
