package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 900_000,
    )

    /**
     * 窗口未知时对话照常完成，但压缩按“保守预算”而非“永不压缩”处理。
     *
     * 这条用例最初断言“未知窗口永不自动压缩”，那在 fix144 就被推翻了：真机 run-f1358776
     * 22 轮 / 1422 条消息 / 21.4 万 token 一次都没压，因为绝大多数用户根本不填 `contextWindow`。
     * 现在未知窗口按 [AgentContextBudget.DEFAULT_ASSUMED_CONTEXT_WINDOW] 保守预算判断，
     * 本例仍只验证“对话能拿到结果”——压缩是否发生由 AgentContextBudgetTest 守（那里
     * 有 nearBudget 边界），不再在这里重复一遍。
     */
    @Test
    fun unknownWindowStillRunsAndUsesTheConservativeBudget() {
        for (window in listOf(null, 0, -1)) {
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        response("摘要")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("完成")
                    }
                })
            // 关键不变式：无论压不压，用户都要拿到这轮的正文，不能被压缩吞掉或多跑一轮。
            assertEquals("窗口=$window 时对话仍应完成", "完成", result.content)
        }
    }

    /**
     * 自动压缩的触发完全由 [AgentContextBudget.shouldCompact] 的三条判据决定；
     * 本例逐条钉死实测 usage 与配置窗口那两条的边界。
     *
     * 历史必须小到本地字符估算不会自己触发兜底：78 万字符 ÷ 4 = 19.5 万 token，
     * 早已越过 [AgentContextBudget.ABSOLUTE_COMPACT_TOKENS]（10 万），那时不管 usage
     * 报什么都会压，用例就失去了意义。所以这里用 3.2 万字符（约 8000 token）的小历史。
     *
     * 注意 [AgentContextBudget.ABSOLUTE_COMPACT_TOKENS] 是**另一条独立判据**：
     * 超过 10 万 token 直接压，不管窗口配多大。所以“30 万 token 不压”在今天
     * 不可能成立——把它当作“未达 85%”来断言是错的。
     */
    @Test
    fun automaticCompactionFollowsAbsoluteLimitThenWindowRatio() {
        // usage -> 是否应当压缩。null 与 0 表示“没有可用实测值”，退回本地估算。
        val cases = listOf(
            null to false,          // 无从得知 → 估算 8000 token，不压
            0 to false,             // 0 也不该被当成“用量很大”
            99_999 to false,        // 差一 token 到绝对上限
            100_000 to true,        // 刚好碰到绝对上限
            300_000 to true,        // 远超绝对上限，与窗口无关
            764_999 to true,        // 同样已被绝对上限覆盖
        )
        for ((input, expectCompact) in cases) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            // 3.2 万字符 ≈ 8000 token，低于绝对上限，把变量留给 usage。
            val source = listOf(
                AgentModelClient.ConversationMessage("user", "旧任务"),
                AgentModelClient.ConversationMessage("assistant", "已完成一些步骤。".repeat(4_000)),
            )
            val result = AgentModelClient.complete(config, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = source, onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        if (!expectCompact) {
                            assertTrue(
                                "未压缩时历史原文应完整发给模型",
                                request.messages.toString().contains("旧任务"),
                            )
                        }
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            assertEquals("usage=$input 时对话仍应完成", "完成", result.content)
            // 分批后一次压缩会发多次摘要请求，这里只看“是否压缩”，请求次数由分批用例守护。
            if (expectCompact) assertTrue("usage=$input 应触发压缩", summaries > 0)
            else assertEquals("usage=$input 不应触发压缩", 0, summaries)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (expectCompact) {
                // 追踪里的 tokensBefore 就是本轮实测 usage（或 null），不是恒定的阈值。
                assertEquals("usage=$input 应原样记入追踪", input, completed!!.tokensBefore)
                assertNull(completed.tokensAfter)
                // 字符数是本地精确值：没有它，追踪里只能看到 0 → 0。
                assertNotNull("压缩前字符数应可追踪", completed.charsBefore)
                assertNotNull("压缩后字符数应可追踪", completed.charsAfter)
                assertTrue(completed.charsAfter!! < completed.charsBefore!!)
                // 必须是真实摘要长度，不是恒 0：翻遍 candidate 找 `_eta_context_summary` 的结果要正数。
                val summaryChars = Regex("摘要 (\\d+) 字符").find(completed.detail)
                    ?.groupValues?.get(1)?.toIntOrNull()
                assertTrue("摘要字符数应为正数：${completed.detail}", (summaryChars ?: 0) > 0)
                assertTrue(completed.detail.contains("字符"))
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("此前任务已完成。")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("完成")
            }
        }
        val answer = AgentModelClient.complete(disabled, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("完成", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNull(answer.contextSnapshot)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("历史已完成。")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun blankOrTooShortSummariesAreRetriedOnceThenFailWithoutTouchingHistory() {
        for ((content, stop) in listOf("" to "stop", "半截" to "length")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val events = mutableListOf<AgentEvent>()
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, events::add, { fail("不应提交无效摘要") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals("空内容 / 过短要重试一次再判失败", 2, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
            val failed = events.filterIsInstance<AgentEvent.ContextCompaction>().last()
            assertEquals("failed", failed.phase)
            assertTrue("失败原因要带上具体信息", failed.detail.isNotBlank())
        }
    }

    @Test
    fun automaticCompactionFailureKeepsOriginalContextAndDoesNotThrow() {
        val messages = messages()
        val original = messages.toString()
        var requests = 0
        val events = mutableListOf<AgentEvent>()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            requests++
            response("", "stop")
        }, AgentRunController(), { emptySet() }, events::add, {})
        session.observeInputTokens(765_000)
        session.compact()
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
        val failed = events.filterIsInstance<AgentEvent.ContextCompaction>().last()
        assertEquals("failed", failed.phase)
        assertEquals("CONTEXT_SUMMARY_INVALID", failed.reasonCode)
        assertEquals(2, requests)
        // 失败后冷却几轮，不在下一轮立刻重放同一份历史。
        session.compact()
        session.compact()
        assertEquals(2, requests)
    }

    @Test
    fun oversizedSummaryIsRetriedWithShorterTargetThenBoundedInsteadOfFailing() {
        val messages = shortMessages()
        val original = messages.toString()
        val prompts = mutableListOf<String>()
        val session = AgentContextSession(config, messages, 1, "operation", provider { request, _ ->
            prompts += request.messages.toString()
            response("长".repeat(12_001), "stop")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.compact(force = true)
        assertEquals(2, prompts.size)
        assertTrue("第一次目标要留余量", prompts[0].contains("不超过 6000 字符"))
        assertTrue("重试要要求更短", prompts[1].contains("不超过 4000 字符"))
        assertNotEquals(original, messages.toString())
        assertNotNull(session.snapshot())
        val summary = summaryContent(messages)
        // 上限针对模型输出的摘要正文；写进历史时还会加固定前缀，所以留 64 字余量。
        assertTrue(summary.length <= AgentContextSummarizer.MAX_SUMMARY_CHARS + 64)
        assertTrue("要标注中段被省略", summary.contains("中段因摘要长度上限已省略"))
    }

    @Test
    fun truncatedButSubstantialSummaryIsAcceptedWithNote() {
        val messages = shortMessages()
        var requests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            requests++
            response("已完成的步骤：".repeat(400), "length")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.compact(force = true)
        assertEquals(2, requests)
        assertNotNull(session.snapshot())
        val summary = summaryContent(messages)
        assertTrue(summary.contains("原摘要被输出上限截断"))
        assertTrue(summary.contains("已完成的步骤"))
    }

    @Test
    fun oldSummaryPrefixIsStrippedBeforeRecompaction() {
        val prefix = "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "固定约束"))
            .put(JSONObject().put("role", "user").put("content", "旧任务"))
            .put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "$prefix 旧摘要正文。" + "细节。".repeat(4_000),
                contextSummary = true,
            )))
        var prompt = ""
        val session = AgentContextSession(config, messages, 1, "operation", provider { request, _ ->
            prompt = request.messages.toString()
            response("新摘要")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.compact(force = true)
        assertFalse("旧前缀不能再喂给模型", prompt.contains("[Eta 上下文摘要"))
        assertTrue(prompt.contains("旧摘要正文"))
    }

    @Test
    fun automaticCompactionFailureDoesNotAbortAnInFlightRun() {
        val call = JSONObject().put("id", "ctx-1").put("type", "function")
            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))
        val tools = JSONArray().put(JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "get_current_context")
                .put("description", "fixture")
                .put("parameters", JSONObject().put("type", "object")),
        ))
        var chatRequests = 0
        var compactionRequests = 0
        val events = mutableListOf<AgentEvent>()
        val result = AgentModelClient.complete(
            config, "继续",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            additionalTools = tools,
            provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    compactionRequests++
                    response("", "stop")
                } else {
                    chatRequests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000, outputTokens = 8)))
                    if (chatRequests == 1) {
                        ProviderResponse(
                            JSONObject().put("role", "assistant").put("content", "")
                                .put("finish_reason", "tool_calls")
                                .put("tool_calls", JSONArray().put(call)),
                        )
                    } else {
                        response("完成", "stop")
                    }
                }
            },
            onEvent = events::add,
        )
        // 旧行为：压缩摘要不合格 → 异常 → 整轮「模型未返回完整且有界的摘要」。现在只降级为提示。
        assertEquals("完成", result.content)
        assertEquals(2, chatRequests)
        assertTrue(compactionRequests > 0)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
    }

    @Test
    fun boundedSummaryKeepsHeadTailAndRespectsCap() {
        val text = "目标：完成 Eta 修复。\n" + "细节。".repeat(5_000) + "\n下一步：装包验证。"
        val bounded = AgentContextSummarizer.boundedSummary(text, 2_000)
        assertTrue(bounded.length <= 2_000)
        assertTrue(bounded.startsWith("目标"))
        assertTrue(bounded.endsWith("下一步：装包验证。"))
        assertTrue(bounded.contains("中段因摘要长度上限已省略"))
    }

    @Test
    fun summaryPrefixStripHandlesLayeredPrefixes() {
        val prefix = "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]"
        assertEquals("正文", AgentConversationCodec.stripContextSummaryPrefix("$prefix $prefix 正文"))
        assertEquals("正文", AgentConversationCodec.stripContextSummaryPrefix("正文"))
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        // 只数 CHAT 请求：本例守护的是“空响应不会被重放”，
        // 压缩请求是另一回事。850K 已过 900K 窗口的 85%，压缩必然先跑一轮，
        // 把两者混在一个计数器里，断言就会随压缩策略变化而无关地红。
        var chatRequests = 0
        var summaries = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("摘要")
                    } else {
                        chatRequests++
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                        response("", "length")
                    }
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals("空响应不应重放：CHAT 只发一次", 1, chatRequests)
        assertTrue(failure.transcript.isEmpty())
    }

    /**
     * 重试后的成功轮不能沿用上一次失败尝试上报的 usage 去触发压缩。
     *
     * 关键前提：历史必须小到字符估算不会自己触发压缩（否则 78 万字符 ÷ 4 = 19.5 万
     * token 早已超过 [AgentContextBudget.ABSOLUTE_COMPACT_TOKENS]，压缩与 usage 无关），
     * 且收尾的 `compact(final = true)` 会再跑一次。这里只用 shortMessages
     * 把变量控制在“失败尝试报的那一个 usage”上。
     */
    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var chatRequests = 0
        var summaries = 0
        val loop = AgentLoop(
            config, shortMessages(), JSONArray(), provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("摘要")
                } else {
                    chatRequests++
                    if (chatRequests == 1) {
                        // 失败尝试报了很大的 usage：它不能被算进“本轮上下文占用”。
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                        throw AgentModelFailure.incompleteStream("fixture")
                    }
                    response("完成")
                }
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals("失败一次后重试一次就应成功", 2, chatRequests)
        assertEquals("失败尝试的 usage 不得触发压缩", 0, summaries)
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("不应提交")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    private fun history() = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(780_000)),
    )

    /** 32K 字符：能触发压缩，又小到单批（<240K），便于精确数摘要请求次数。 */
    private fun shortMessages() = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "固定约束"))
        .put(JSONObject().put("role", "user").put("content", "旧任务"))
        .put(AgentConversationCodec.toJsonObject(
            AgentModelClient.ConversationMessage("assistant", "已完成一些步骤。".repeat(4_000)),
        ))

    private fun summaryContent(messages: JSONArray): String {
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            if (message.optBoolean("_eta_context_summary")) return message.optString("content")
        }
        error("没有写入摘要消息")
    }

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
