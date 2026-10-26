package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactionTest {
    @Test
    fun automaticScreenContentIsNotCopiedIntoCompactionInput() {
        val messages = jsonHistory()
        val user = AgentConversationCodec.userTextMessage("保留最新问题")
        AssistantScreenContextProjection.attach(user, "PRIVATE_AUTOMATIC_SCREEN_CONTENT")
        messages.put(user)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            assertFalse(request.messages.toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
            response("已完成此前的任务。")
        }, AgentRunController()).compact(messages, 1, emptySet()).messages
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(
            AgentConversationCodec.transcript(compacted, 1),
        ).contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
        assertTrue(AssistantScreenContextProjection.project(compacted).toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
    }

    @Test
    fun multiBatchCompactionReportsBatchCount() {
        // 单批上限 240K 字符：两条 150K 字符的历史必须分批，最后再合并一次。
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束"))
        repeat(3) { index ->
            messages.put(AgentConversationCodec.userTextMessage("第 $index 问"))
            messages.put(JSONObject().put("role", "assistant").put("content", "历史事实 ".repeat(30_000)))
        }
        var requests = 0
        val batchLines = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { _, _ ->
            requests++
            response("此前任务已完成。")
        }, AgentRunController(), onBatch = batchLines::add).compact(messages, 1, emptySet())
        // 批次必须真的发生，并且能被外面看见：以前只返回正文，批次数无从验证。
        assertTrue("应发生分批：requests=$requests", result.batches >= 2)
        assertEquals("每批一次请求，最后再合并一次", result.batches + 1, requests)
        // 分批进度只能走注入的出口，不能直接调 android.util.Log（纯 JVM 单测里它没实现）。
        assertEquals("每批一行 + 合并一行", result.batches + 1, batchLines.size)
        assertTrue("进度要带批号：$batchLines", batchLines.any { it.contains("第 1/") })
        assertTrue("合并行要报批数：$batchLines", batchLines.last().contains("${result.batches} 批合成一段"))
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 128_000,
    )

    @Test
    fun largeHistoryWithoutUsageDoesNotTriggerCompaction() {
        val original = "历史事实".repeat(40_000)
        var requests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", original)),
            provider = provider { request, _ ->
                requests++
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                assertTrue(request.messages.toString().contains(original))
                response("完成")
            })
        assertEquals(1, requests)
        assertNull(result.contextSnapshot)
    }

    @Test
    fun historyBeyondHardLimitCompactsWithoutAnyUsage() {
        // 真实事故形状：端点整场不回 usage，历史一路涨到 2.79M 字符，
        // 最后在整份序列化时 OOM。硬上限必须在没有 usage 时也触发压缩。
        val history = (1..13).map {
            AgentModelClient.ConversationMessage("assistant", "历史事实".repeat(25_000))
        }
        var summaryRequests = 0
        var chatRequests = 0
        val result = AgentModelClient.complete(
            config.copy(contextWindow = null), "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history,
            provider = provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("此前任务已完成，保留关键结论。")
                } else {
                    chatRequests++
                    response("完成")
                }
            },
        )
        assertEquals(1, chatRequests)
        assertTrue("应当分批压缩", summaryRequests >= 2)
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        // 压缩后进入下一次请求的历史必须远小于原历史。
        assertTrue(
            "压缩后仍过大",
            AgentContextBudget.messagesChars(snapshot.messages) <
                AgentContextBudget.messagesChars(history),
        )
    }

    @Test
    fun interruptedToolBatchKeepsCompletedResultAndMarksOnlyUnknownResults() {
        val call = AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
            {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]""")
        val completed = AgentModelClient.ConversationMessage("tool", "真实完成结果", toolCallId = "a")
        val restored = AgentToolBatchRecovery.completeInterrupted(listOf(call, completed))
        assertEquals(completed, restored[1])
        assertEquals("b", restored.last().toolCallId)
        assertTrue(restored.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals(restored, AgentToolBatchRecovery.completeInterrupted(restored))
    }

    @Test
    fun automaticCompactionPreservesIncrementalTranscriptAndLatestRequest() {
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "第 $turn 个任务"),
            AgentModelClient.ConversationMessage("assistant", "事实 $turn ".repeat(2200)),
        ) }
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertEquals(0, request.effectiveTools.length())
                response("已完成前面的任务，继续处理最新请求。")
            } else {
                assertTrue(request.messages.toString().contains("最新请求必须保留"))
                assertTrue(request.messages.toString().contains("固定约束"))
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 45_000)))
                response("完成最新请求")
            }
        }
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 50_000), "最新请求必须保留", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history, provider = provider, onContextSnapshot = snapshots::add, onEvent = events::add,
        )
        assertTrue(summaries > 0)
        assertEquals(listOf("完成最新请求"), result.transcript.map { it.content })
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals("最新请求必须保留", snapshot.messages.last().content)
        assertEquals(9, snapshot.consumedUserTurns)
        val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().single { it.phase == "completed" }
        // 真机缺陷回归：completed 只带 detail 文本时，追踪里 before/after 全是 0。
        assertNotNull(completed.charsBefore)
        assertNotNull(completed.charsAfter)
        assertTrue(completed.charsAfter!! < completed.charsBefore!!)
        assertTrue(snapshots.all { AgentContextSnapshot.decode(it.encode()) == it })
    }

    @Test
    fun manualCompactionDoesNotCreateUserOrAssistantTranscript() {
        val result = AgentModelClient.complete(
            config, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), compactOnly = true, provider = provider { request, _ ->
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("历史工作已完成，保留后续问题。")
            },
        )
        assertTrue(result.transcript.isEmpty())
        assertEquals("", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.messages.none { it.role == "user" && it.content.isBlank() })
    }

    @Test
    fun truncatedSummaryNeverReplacesOriginalContext() {
        val original = jsonHistory()
        val before = original.toString()
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            response("半截摘要", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun cancellationAndPersistenceFailureKeepOriginalContext() {
        for (cancel in listOf(true, false)) {
            val original = jsonHistory()
            val before = original.toString()
            val controller = AgentRunController()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
                if (cancel) controller.cancel()
                response("有界摘要")
            }, controller, { emptySet() }, {}, { throw IllegalStateException("fixture storage failure") })
            assertThrows(Exception::class.java) { session.compact(force = true) }
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun summaryInputRedactsSensitiveToolsAndOpaqueReasoningAndImages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
        messages.put(AgentConversationCodec.userTextMessage("旧任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取结果")
            .put("reasoning_content", "PRIVATE_THINKING")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "sensitive").put("type", "function")
                .put("function", JSONObject().put("name", "read_secret").put("arguments", "SECRET_ARGUMENT"))))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("encrypted", "OPAQUE_SECRET")))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "sensitive").put("content", "SECRET_RESULT"))
        history().forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val pending = AgentConversationCodec.userMessage("瞬时观察", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_IMAGE", "image/png", 10),
        )).put("_eta_observation", true)
        messages.put(pending)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            val text = request.messages.toString()
            listOf("SECRET_ARGUMENT", "SECRET_RESULT", "PRIVATE_THINKING", "OPAQUE_SECRET", "PRIVATE_IMAGE").forEach {
                assertFalse("摘要包含敏感数据 $it", text.contains(it))
            }
            response("已读取过信息，原始敏感结果未保留。")
        }, AgentRunController()).compact(messages, 1, setOf("sensitive")).messages
        assertSame(pending, compacted.getJSONObject(compacted.length() - 1))
        assertFalse(compacted.toString().contains("OPAQUE_SECRET"))
    }

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
            listOf("a", "b").forEach { put(JSONObject().put("id", it).put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))) }
        })
        val messages = listOf(call,
            JSONObject().put("role", "tool").put("tool_call_id", "a"),
            JSONObject().put("role", "tool").put("tool_call_id", "b"),
            AgentConversationCodec.userTextMessage("继续"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun signedAnthropicToolRoundCannotCompactBeforeReturningToolResults() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("继续查询"))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_current")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "CURRENT_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_current")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_current")
            .put("content", "工具结果"))
        val original = messages.toString()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            fail("签名未用前不能改写历史")
            response("不应执行")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })

        val failure = assertThrows(AgentModelFailure::class.java) {
            session.compact(force = true)
        }
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun compactionDropsCompletedAnthropicSignaturesAfterChangingPrefix() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("已完成的工具任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取中")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_old")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "STALE_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_old")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_old")
            .put("content", "完成"))
        messages.put(JSONObject().put("role", "assistant").put("content", "查询完成"))
        messages.put(AgentConversationCodec.userTextMessage("新的问题"))

        val compacted = AgentContextCompactor(config, provider { _, _ ->
            response("此前任务已完成。")
        }, AgentRunController()).compact(messages, 1, emptySet()).messages

        assertFalse(compacted.toString().contains("STALE_SIGNATURE"))
        assertTrue(compacted.toString().contains("新的问题"))
        assertFalse(compacted.toString().contains("toolu_old"))
    }

    @Test
    fun overflowClassificationDoesNotTreatEveryBadRequestAsCapacityFailure() {
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"code":"context_length_exceeded","message":"private fixture"}}""").code)
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"type":"invalid_request_error","message":"prompt is too long: fixture"}}""").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400,
            """{"error":{"message":"invalid tool parameter"}}""").code)
    }

    @Test
    fun explicitOverflowFailsWithoutCompactionOrReplay() {
        var normalRequests = 0
        var summaryRequests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("之前的任务已完成。")
                } else if (normalRequests++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败思考"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                } else response("完成")
            })
        }
        assertEquals("CONTEXT_OVERFLOW", (failure.cause as AgentModelFailure).code)
        assertEquals(1, normalRequests)
        assertEquals(0, summaryRequests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun hostedToolOverflowDoesNotReplayOrStartCompaction() {
        var requests = 0
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "查询", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                })
        }
        assertEquals(1, requests)
        assertFalse((error.cause as AgentModelFailure).retryable)
    }

    @Test
    fun summaryRequestIncludesAllCompletedHistoryInOneRequest() {
        var requests = 0
        val seen = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { request, _ ->
            requests++
            val historyText = request.messages.getJSONObject(1).getString("content")
            seen += historyText
            response("之前的工作已完成。")
        }, AgentRunController()).compact(jsonHistory(), 1, emptySet()).messages
        assertEquals(1, requests)
        for (turn in 1..4) assertTrue(seen.any { it.contains("问题 $turn") })
        assertTrue(result.toString().contains("问题 6"))
    }

    @Test
    fun completedAnswerRemainsSuccessfulWhenFinalCompactionFails() {
        val events = mutableListOf<AgentEvent>()
        val finalText = "完整结果".repeat(17_000)
        val result = AgentModelClient.complete(config.copy(contextWindow = 80_000), "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) response("不完整", "length")
                else {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 70_000)))
                    response(finalText)
                }
            }, onEvent = events::add)
        assertEquals(finalText, result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertNotNull(result.contextSnapshot)
        assertEquals(finalText, result.contextSnapshot!!.messages.last().content)
        assertEquals(finalText, result.transcript.last().content)
    }

    private fun history() = (1..6).flatMap { turn -> listOf(
        AgentModelClient.ConversationMessage("user", "问题 $turn"),
        AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(1000)),
    ) }

    private fun jsonHistory() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则")).also {
        history().forEach { message -> it.put(AgentConversationCodec.toJsonObject(message)) }
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
