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
        // 端点忽略 include_usage 时，只有本地字符数能拦住增长。
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = null,
                observedInputTokens = null,
                historyChars = AgentContextBudget.HARD_CONTEXT_CHARS + 1,
            ),
        )
    }

    @Test
    fun hardLimitDoesNotOverrideTheUserTurningAutoCompactionOff() {
        // 用户关掉自动压缩后，硬上限不得替他发起一次付费的摘要请求。
        // 曾经这里写 `autoCompactionEnabled || window == null`，窗口未知时无视开关。
        for (window in listOf(null, 128_000, 1_000_000)) {
            assertFalse(
                "窗口=$window",
                AgentContextBudget.shouldCompact(
                    force = false, autoCompactionEnabled = false, contextWindow = window,
                    observedInputTokens = null,
                    historyChars = AgentContextBudget.HARD_CONTEXT_CHARS * 4,
                ),
            )
        }
        // 手动压缩不受开关限制：用户点了就压。
        assertTrue(
            AgentContextBudget.shouldCompact(
                force = true, autoCompactionEnabled = false, contextWindow = null,
                observedInputTokens = null,
                historyChars = 0,
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
        // 上限 = 窗口一半（见 ABSOLUTE_LIMIT_WINDOW_RATIO），1M 窗口即 500K。
        val ceiling = 500_000
        assertTrue(
            "窗口配成 1M，到绝对上限（窗口一半）时必须压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
                observedInputTokens = ceiling,
                historyChars = 10_000,
            ),
        )
        assertFalse(
            "差一 token 未达上限、又远未到窗口 85% 时不该压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
                observedInputTokens = ceiling - 1,
                historyChars = 10_000,
            ),
        )
    }

    /**
     * 绝对上限随窗口放大，但不超过窗口的一半。
     *
     * 回归对象：旧实现是 `min(窗口 × 0.85, 100_000)`，于是 720K 窗口的触发点被钉在 10 万
     * （trace：`before≈100,055–101,961`），用户体感是「700K 才到 120K 就压缩了」。
     */
    @Test
    fun absoluteLimitGrowsWithTheWindow() {
        assertEquals(100_000, AgentContextBudget.absoluteLimitFor(null))
        assertEquals("小窗口不放大", 100_000, AgentContextBudget.absoluteLimitFor(32_000))
        assertEquals(100_000, AgentContextBudget.absoluteLimitFor(128_000))
        assertEquals(128_000, AgentContextBudget.absoluteLimitFor(256_000))
        assertEquals("720K 窗口终于不是 10 万", 360_000, AgentContextBudget.absoluteLimitFor(720_000))
        assertEquals(500_000, AgentContextBudget.absoluteLimitFor(1_000_000))
        assertEquals(
            "非正窗口等同未知，不能算出 0 的阈值",
            AgentContextBudget.absoluteLimitFor(null),
            AgentContextBudget.absoluteLimitFor(0),
        )
    }

    /**
     * 阈值只抬到「已验证峰值 × 1.5」，逐级逼近窗口天花板。
     *
     * 为何不能一步到位：720,000 是用户填的 `context_window_override`，不是端点自报；
     * 一次性按窗口一半去要 36 万 token，端点若只支持 25 万就会以 CONTEXT_OVERFLOW
     *（`retryable=false`）打断整个 run。
     */
    @Test
    fun absoluteLimitIsAnchoredToTheProvenPeak() {
        val window = 720_000
        assertEquals("没有实测时只能取天花板", 360_000, AgentContextBudget.absoluteLimitFor(window))
        assertEquals(150_000, AgentContextBudget.absoluteLimitFor(window, 100_000))
        assertEquals(300_000, AgentContextBudget.absoluteLimitFor(window, 200_000))
        assertEquals("到顶后不再放大", 360_000, AgentContextBudget.absoluteLimitFor(window, 300_000))
        assertEquals("峰值 0 视为无效", 360_000, AgentContextBudget.absoluteLimitFor(window, 0))
        // 单调性：峰值越大，阈值不降（压缩不回退峰值，所以这条是长期成立的）。
        var previous = 0
        for (peak in listOf(10_000, 50_000, 90_000, 120_000, 200_000, 400_000)) {
            val current = AgentContextBudget.absoluteLimitFor(window, peak)
            assertTrue("峰值 $peak 时阈值 $current 小于上一档 $previous", current >= previous)
            previous = current
        }
    }

    /** 端到端：720K 窗口不再「一到 10 万就压」，而是随已验证峰值逐级后退。 */
    @Test
    fun largeWindowCompactsLaterAsThePeakGrows() {
        val window = 720_000
        assertFalse(
            "峰值 10 万时阈值已抬到 15 万，10 万不再触发",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = 100_000, historyChars = 10_000, peakObservedTokens = 100_000,
            ),
        )
        assertTrue(
            "同一峰值下到 15 万就压",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = window,
                observedInputTokens = 150_000, historyChars = 10_000, peakObservedTokens = 100_000,
            ),
        )
        // 天花板必须低于窗口比例，否则大窗口下「窗口一半」会先于比例生效而失去意义。
        assertEquals(
            360_000.0,
            AgentContextBudget.effectiveTriggerTokens(
                window, AgentContextBudget.TRIGGER_RATIO, peakObservedTokens = 400_000,
            ),
            0.0,
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
        // 3. 超过临界：绝对上限接管；但上限本身随窗口放大（见 absoluteLimitGrowsWithTheWindow），
        //    所以这里钉的是「窗口 × 0.5」而不是固定常量。
        assertEquals(450_000.0, AgentContextBudget.effectiveTriggerTokens(900_000, ratio), 0.0)
        assertEquals(500_000.0, AgentContextBudget.effectiveTriggerTokens(1_000_000, ratio), 0.0)
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
    fun realSessionWithoutUsageIsEstimatedAtOneQuarter() {
        // 同一个真机会话在没有 usage 时的折算：440,850 / 4 = 110,212。
        // 旧除数 2 会得到 220,425，差异就是 fix144 改成 4 的意义所在。
        assertEquals(110_212L, AgentContextBudget.estimateTokens(440_850))
        assertEquals(220_425L, 440_850L / 2)
        // 256K 窗口的上限是 12.8 万，所以这个估算值**不再**触发压缩。
        // 这是本轮有意改的（大窗口不该一到 10 万就压）的反向锁定，
        // 避免以后有人把「110,212 必压」当成不变量重新钉回去。
        assertFalse(
            "256K 窗口下 110,212 < 12.8 万，不该压缩",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 256_000,
                observedInputTokens = null, historyChars = 440_850,
            ),
        )
        // 但窗口只有 128K 时上限仍是 10 万，同一个估算值必须压缩（兵底仍然有效）。
        assertTrue(
            "128K 窗口下 110,212 已达绝对上限，压缩是预期的",
            AgentContextBudget.shouldCompact(
                force = false, autoCompactionEnabled = true, contextWindow = 128_000,
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

    // ---- 压缩判定原因（compactionDecision）--------------------------------
    // 存在的意义：真机上出现「历史很大但一次没压」时，只能看到「没有 compact 事件」。
    // 下面每一条都固定住一种原因，避免再次出现无法归因的情况。

    @Test
    fun decisionReportsForcedCauseAndUsesObservedTokens() {
        val d = AgentContextBudget.compactionDecision(
            force = true, autoCompactionEnabled = false, contextWindow = 200_000,
            observedInputTokens = 1_234, historyChars = 5_000,
        )
        assertTrue(d.compact)
        assertEquals(AgentContextBudget.CompactionCause.FORCED, d.cause)
        assertEquals(1_234, d.tokens)
        assertEquals(AgentContextBudget.TokenSource.OBSERVED, d.tokenSource)
        // force 在开关之前生效，与旧 shouldCompact 一致。
        assertEquals(5_000L, d.historyChars)
    }

    @Test
    fun decisionReportsDisabledWhenUserTurnedAutoCompactionOff() {
        val d = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = false, contextWindow = null,
            observedInputTokens = null, historyChars = AgentContextBudget.HARD_CONTEXT_CHARS * 2,
        )
        assertFalse("关掉开关后即使超硬上限也不能压：${d.describe()}", d.compact)
        assertEquals(AgentContextBudget.CompactionCause.DISABLED, d.cause)
    }

    @Test
    fun decisionReportsHardLimitWhenCharsExceedItWithoutUsage() {
        // 端点不回 usage 的真实情况：只能靠字符数兜底。
        val d = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = null,
            observedInputTokens = null, historyChars = AgentContextBudget.HARD_CONTEXT_CHARS + 1,
        )
        assertTrue(d.compact)
        assertEquals(AgentContextBudget.CompactionCause.HARD_LIMIT, d.cause)
        // 没有 usage 时必须标明是本地估算，否则无法解释「为何与端点数字不符」。
        assertEquals(AgentContextBudget.TokenSource.ESTIMATED, d.tokenSource)
    }

    @Test
    fun decisionReportsThresholdWhenTokensReachTheTrigger() {
        val d = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 128_000,
            observedInputTokens = AgentContextBudget.ABSOLUTE_COMPACT_TOKENS, historyChars = 1_000,
        )
        assertTrue(d.compact)
        assertEquals(AgentContextBudget.CompactionCause.THRESHOLD, d.cause)
        // 阈值取 min(窗口比例, 绝对上限)，这里是绝对上限生效。
        assertEquals(
            AgentContextBudget.ABSOLUTE_COMPACT_TOKENS.toDouble(), d.threshold, 0.001,
        )
    }

    @Test
    fun decisionReportsNoneWhenNothingTriggersCompaction() {
        val d = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 128_000,
            observedInputTokens = 1_000, historyChars = 160_000,
        )
        assertFalse(d.compact)
        assertEquals(AgentContextBudget.CompactionCause.NONE, d.cause)
        // 未压缩时原因必然是 NONE 以外的某个具体原因，不能留空。
        assertTrue(d.describe().contains("cause=none"))
        assertTrue(d.describe().contains("compact=no"))
    }

    @Test
    fun decisionNeverDisagreesWithShouldCompact() {
        // shouldCompact 已改为委托 compactionDecision；这里的组合矩阵用来锁住两者不漂移，
        // 并覆盖 force / 开关 / 窗口 / usage 三条判据的全部交织情况。
        val windows = listOf(null, 0, 8_000, 128_000, 1_000_000)
        val usages = listOf(null, -1, 0, 50_000, 150_000)
        val chars = listOf(0L, 5_000L, 200_000L, AgentContextBudget.HARD_CONTEXT_CHARS + 1)
        for (force in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                for (window in windows) {
                    for (usage in usages) {
                        for (charCount in chars) {
                            val d = AgentContextBudget.compactionDecision(
                                force, enabled, window, usage, charCount,
                            )
                            val expected = AgentContextBudget.shouldCompact(
                                force, enabled, window, usage, charCount,
                            )
                            assertEquals(
                                "force=$force enabled=$enabled window=$window usage=$usage chars=$charCount",
                                expected, d.compact,
                            )
                            // 原因必须与结论一致：
                            // - 压缩时 cause 只能是 FORCED / HARD_LIMIT / THRESHOLD；
                            // - 不压缩时只能是 DISABLED 或 NONE。
                            // DISABLED 是"不压缩"的一个具体原因，不能归入 NONE。
                            val compressedCauses = setOf(
                                AgentContextBudget.CompactionCause.FORCED,
                                AgentContextBudget.CompactionCause.HARD_LIMIT,
                                AgentContextBudget.CompactionCause.THRESHOLD,
                            )
                            assertEquals(
                                "compact=${d.compact} 却给出 cause=${d.cause}",
                                d.compact,
                                d.cause in compressedCauses,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun decisionTreatsNegativeUsageAsMissing() {
        // usage 为负时旧实现走估算；不能把它当真值直接与阈值比较。
        val d = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 128_000,
            observedInputTokens = -1, historyChars = 400_000,
        )
        assertEquals(AgentContextBudget.TokenSource.ESTIMATED, d.tokenSource)
        assertEquals(AgentContextBudget.estimateTokens(400_000).toInt(), d.tokens)
    }

    @Test
    fun decisionTreatsZeroUsageAsMissingNotAsEmptyContext() {
        // 真机风险：部分兼容端点在流式首块回 `usage: {prompt_tokens: 0}` 占位。
        // 曾经 0 被当成有效实测，于是同一份 40 万字符历史得到 tokens=0 且 compact=no
        // （见本用例下方对照），而 usage 缺失时反而能正确压缩——参数越「有值」越糟。
        val zero = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 128_000,
            observedInputTokens = 0, historyChars = 400_000,
        )
        assertEquals(
            "0 必须退回估算，不能当成「上下文是空的」：${zero.describe()}",
            AgentContextBudget.TokenSource.ESTIMATED,
            zero.tokenSource,
        )
        assertEquals(AgentContextBudget.estimateTokens(400_000).toInt(), zero.tokens)

        // 与「完全没有 usage」必须给出同一个结论，否则说明 0 仍在走特殊路径。
        val missing = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 128_000,
            observedInputTokens = null, historyChars = 400_000,
        )
        assertEquals(missing.tokens, zero.tokens)
        assertEquals(missing.compact, zero.compact)
        assertEquals(missing.cause, zero.cause)
        assertTrue("40 万字符必须触发压缩：${zero.describe()}", zero.compact)
    }

    @Test
    fun decisionZeroWindowFallsBackToDefaultAssumptions() {
        // window<=0 必须与 null 等价，否则会算出 0 的阈值直接开压。
        val zero = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 0,
            observedInputTokens = 1_000, historyChars = 10_000,
        )
        val nullWindow = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = null,
            observedInputTokens = 1_000, historyChars = 10_000,
        )
        assertEquals(nullWindow.threshold, zero.threshold, 0.001)
        assertEquals(nullWindow.hardLimit, zero.hardLimit)
        assertEquals(nullWindow.compact, zero.compact)
        assertNull("0 窗口应归一为 null：${zero.describe()}", zero.contextWindow)
    }

    // ---- 绝对上限可注入：边界语义与常量取值解耦 ---------------------------
    // 背景：这些边界用例原先直接引用 ABSOLUTE_COMPACT_TOKENS，把「逻辑对不对」和
    // 「常量是不是 100_000」绑在一起。实测把常量改成 52_000 会让 5 个用例失败，
    // 但它们锁的其实是数值而非语义。absoluteLimit 参数让测试自己给边界，
    // 于是同一套断言对任意上限都成立。

    @Test
    fun crossoverPointScalesWithAbsoluteLimit() {
        val ratio = AgentContextBudget.TRIGGER_RATIO
        for (limit in listOf(52_000, 100_000, 200_000)) {
            // 临界窗口必须向上取整：limit / ratio 常带小数，截断后乘回去会略小于 limit，
            // 那样取到的仍是比例侧（这个坑本用例最初的版本踩过）。
            val crossover = kotlin.math.ceil(limit / ratio).toInt()
            assertEquals(
                "上限 $limit：临界窗口处两侧应相等",
                limit.toDouble(),
                AgentContextBudget.effectiveTriggerTokens(crossover, ratio, limit),
                0.0,
            )
            assertEquals(
                "上限 $limit：临界窗口下方仍由比例决定",
                (crossover - 1) * ratio,
                AgentContextBudget.effectiveTriggerTokens(crossover - 1, ratio, limit),
                0.0,
            )
            assertEquals(
                "上限 $limit：大窗口一律由绝对上限接管",
                limit.toDouble(),
                AgentContextBudget.effectiveTriggerTokens(900_000, ratio, limit),
                0.0,
            )
        }
    }

    @Test
    fun decisionThresholdFollowsInjectedLimit() {
        val ratio = AgentContextBudget.TRIGGER_RATIO
        val limit = 52_000
        // 上限正下方一个 token：不压缩。
        val below = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
            observedInputTokens = limit - 1, historyChars = 1_000,
            absoluteLimit = limit,
        )
        assertFalse("差一 token 不该压缩：${below.describe()}", below.compact)
        assertEquals(AgentContextBudget.CompactionCause.NONE, below.cause)
        assertEquals(limit.toDouble(), below.threshold, 0.0)

        // 恰好达到：压缩，且原因必须是阈值。
        val at = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
            observedInputTokens = limit, historyChars = 1_000,
            absoluteLimit = limit,
        )
        assertTrue("刚好达到上限应压缩：${at.describe()}", at.compact)
        assertEquals(AgentContextBudget.CompactionCause.THRESHOLD, at.cause)

        // 省略参数时必须按「窗口与峰值」推算，不能退回固定常量。
        val defaults = AgentContextBudget.compactionDecision(
            force = false, autoCompactionEnabled = true, contextWindow = 1_000_000,
            observedInputTokens = 1, historyChars = 1_000,
        )
        assertEquals(
            AgentContextBudget.absoluteLimitFor(1_000_000).toDouble(),
            defaults.threshold,
            0.0,
        )
        assertEquals(500_000, defaults.absoluteLimit)
    }
}
