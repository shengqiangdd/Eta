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

    @Test
    fun unknownWindowStillRunsButNeverAutoCompacts() {
        for (window in listOf(null, 0, -1)) {
            var summaries = 0
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("摘要")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("完成")
                    }
                })
            assertEquals("完成", result.content)
            assertEquals(0, summaries)
        }
    }

    @Test
    fun automaticCompactionUsesOnlyActualInputUsageAndConfiguredWindow() {
        for (input in listOf(null, 300_000, 764_999, 765_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(config, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        assertTrue(request.messages.toString().contains("长".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            // 分批后一次压缩会发多次摘要请求，这里只看“是否压缩”，请求次数由分批用例守护。
            if (input == 765_000) assertTrue("达到 85% 应当压缩", summaries > 0)
            else assertEquals("未达阈值不应压缩", 0, summaries)
            assertEquals("完成", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 765_000) {
                assertEquals(765_000, completed!!.tokensBefore)
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
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("完成")
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals(2, requests)
        assertNull(loop.contextSnapshot())
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
