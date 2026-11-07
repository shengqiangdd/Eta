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
    /**
     * 窗口配得再大，也要有绝对上限。
     *
     * 真机实测（2026-11）：模型填了很大的 `contextWindow`，于是 `tokens >= window * 0.85`
     * 永远不成立 —— 23.9 万 token、1533 条消息，一次 compress 都没触发。
     */
    @Test
    fun hugeConfiguredWindowCannotDisableCompactionForever() {
        assertTrue(
            "窗口配成 1M 也要在 10 万 token 时压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
                observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS,
                historyChars = 10_000,
            ),
        )
    }

    @Test
    fun belowAbsoluteLimitStillRespectsTheConfiguredWindow() {
        assertFalse(
            "低于绝对上限且远未到窗口 85% 时不该压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
                observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS / 2,
                historyChars = 10_000,
            ),
        )
    }

    @Test
    fun effectiveTriggerIsMinOfWindowRatioAndAbsoluteLimit() {
        // 单点验证 min 的语义，不再靠给 shouldCompact 传测试专用参数。
        val absolute = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS.toDouble()
        val ratio = AgentContextBudget.TRIGGER_RATIO
        val assumed = AgentContextBudget.DEFAULT_ASSUMED_CONTEXT_WINDOW

        // 1. 小窗口：比例更早，阈值 = 窗口 × 比例。
        assertEquals(32_000 * ratio, AgentContextBudget.effectiveTriggerTokens(32_000, ratio), 0.0)
        // 2. 临界窗口：恰好两侧相等。注意不能直接写 (absolute / ratio).toInt()：
        //    100_000 / 0.85 = 117,647.05，截断后 117,647 × 0.85 = 99,999.95 < 100,000，
        //    取到的仍是比例侧。真正的临界点是向上取整（本用例就是被这个差错发现的）。
        val crossover = kotlin.math.ceil(absolute / ratio).toInt()
        assertEquals(117_648, crossover)
        assertEquals(absolute, AgentContextBudget.effectiveTriggerTokens(crossover, ratio), 0.0)
        assertEquals(
            "临界点以下仍由比例决定",
            117_647 * ratio,
            AgentContextBudget.effectiveTriggerTokens(117_647, ratio),
            0.0,
        )
        // 3. 超过临界：绝对上限接管，窗口再大也一样。
        assertEquals(absolute, AgentContextBudget.effectiveTriggerTokens(900_000, ratio), 0.0)
        assertEquals(absolute, AgentContextBudget.effectiveTriggerTokens(1_000_000, ratio), 0.0)
        // 4. 窗口未知：用假设值参与 min（这是该常量**活着**的证明）。
        assertEquals(
            minOf(assumed * ratio, absolute),
            AgentContextBudget.effectiveTriggerTokens(null, ratio),
            0.0,
        )
        // 5. 非正值窗口等同未知，而不是阈值 0（否则传 0 会变成每轮都压）。
        assertEquals(
            AgentContextBudget.effectiveTriggerTokens(null, ratio),
            AgentContextBudget.effectiveTriggerTokens(0, ratio),
            0.0,
        )
        assertEquals(
            AgentContextBudget.effectiveTriggerTokens(null, ratio),
            AgentContextBudget.effectiveTriggerTokens(-1, ratio),
            0.0,
        )
    }

    @Test
    fun assumedWindowOnlyMattersBelowTheAbsoluteLimit() {
        // 当前取值下这个常量影响不到结果，这是事实，不是巧合：108,800 > 10 万。
        val ratio = AgentContextBudget.TRIGGER_RATIO
        val assumed = AgentContextBudget.DEFAULT_ASSUMED_CONTEXT_WINDOW
        val ratioThreshold = assumed * ratio
        assertTrue(
            "前提：假设值 × 比例必须高于绝对上限，否则下面的断言就失去意义",
            ratioThreshold > AgentContextBudget.ABSOLUTE_COMPACT_TOKENS,
        )
        // 因此窗口未知时的真实阈值就是绝对上限，而不是 108,800。
        assertEquals(
            AgentContextBudget.ABSOLUTE_COMPACT_TOKENS.toDouble(),
            AgentContextBudget.effectiveTriggerTokens(null, ratio),
            0.0,
        )
        // 反向锁定：只要比例降到假设值之积低于绝对上限，该常量立刻成为唯一判据。
        // 0.5 → 128,000 × 0.5 = 64,000 < 100,000，于是 min 取 64,000。
        assertEquals(
            64_000.0,
            AgentContextBudget.effectiveTriggerTokens(null, 0.5),
            0.0,
        )
    }

    @Test
    fun unknownWindowCompactsExactlyAtTheAbsoluteLimit() {
        // 端到端锁定 shouldCompact 的实际行为：99,999 不压、100,000 压。
        assertFalse(
            "差 1 token 未达绝对上限",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS - 1,
                historyChars = 10_000,
            ),
        )
        assertTrue(
            "恰好达到绝对上限就压",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS,
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
        // 这里要验的是**硬上限**这条安全阀，不是绝对压缩上限：
        // 历史必须同时「低于窗口 85%」且「低于 ABSOLUTE_COMPACT_TOKENS 折算的字符数」，
        // 否则先被别的判据拦下，就测不到硬上限自己。
        val belowAbsoluteChars = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS.toLong() * 4 - 1
        assertFalse(
            "未到硬上限、也未触发其他判据时不该压",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 900_000,
                observedInputTokens = null, historyChars = belowAbsoluteChars,
            ),
        )
        // 硬上限本身仍然生效：超过它即使窗口更大也必须压。
        assertTrue(
            "超过硬上限必须压",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 900_000,
                observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS * 2,
                historyChars = 1_800_001,
            ),
        )
    }

    @Test
    fun observedUsageStillDrivesWindowRatio() {
        // 绝对上限（10 万 token）会吞掉比它高的任何比例边界，所以窗口比例要单独验证，
        // 窗口必须选得让 85% 触发线落在绝对上限**以下**：100K × 85% = 85,000。
        val window = 100_000
        val trigger = (window * AgentContextBudget.TRIGGER_RATIO).toInt()
        assertTrue("前提：触发线必须在绝对上限以下", trigger < AgentContextBudget.ABSOLUTE_COMPACT_TOKENS)
        assertFalse(
            "低于触发线",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = trigger - 1, historyChars = 1_000,
            ),
        )
        assertTrue(
            "恰好到达触发线",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = trigger, historyChars = 1_000,
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
    fun realSessionIsNotCompactedEarlyWhenTheEndpointReportsUsage() {
        // 真机数据（2026-10-23 生产库）：本会话 history 440,850 字符，端点回报 ctx=92,714，
        // 实际只占 256K 窗口的 36%。旧除数 2 会把它估成 220,425（86%），在没有 usage 的
        // 端点上白白触发一次压缩；按 4 折算为 110,212。
        //
        // 端点**回报了** usage 时以实测为准（92,714），不该压缩——这是本用例真正守的语义。
        assertFalse(
            "端点回报 36% 时不该压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 256_000,
                observedInputTokens = 92_714, historyChars = 440_850,
            ),
        )
    }

    @Test
    fun realSessionWithoutUsageIsEstimatedAtOneQuarterAndHitsTheAbsoluteLimit() {
        // 同一个真机会话在没有 usage 时的折算：440,850 / 4 = 110,212。
        // 该值 ≥ ABSOLUTE_COMPACT_TOKENS（10 万），所以**会被压缩**——这是 fix145 有意
        // 引入的行为变更（防“窗口填得很大就永不压缩”）。这里把差异钉成断言，
        // 避免以后有人以为无 usage 也一定不压。
        assertEquals(110_212L, AgentContextBudget.estimateTokens(440_850))
        // 旧除数 2 会得到 220,425，差异就是 fix144 改成 4 的意义所在。
        assertEquals(220_425L, 440_850L / 2)
        assertTrue(
            "无 usage 时 110,212 已达绝对上限，压缩是预期的",
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
