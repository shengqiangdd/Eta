package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    /** 工具调用的完整追踪（参数/结果原文、耗时）；与面向用户的事件流分开，写失败不影响运行。 */
    private val onToolTrace: (
        round: Int,
        call: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
        elapsedMs: Long,
    ) -> Unit = { _, _, _, _ -> },
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    /** 大结果溢写：默认关闭，只在真实 App 运行里注入；纯单测不落盘。 */
    private val toolSpill: AgentToolSpill? = null,
    initialSupplementIndex: Int = 0,
    /**
     * 轮次广播：进入工具执行前 enterRound，结束后 exitRound，都带本次调用的 toolCallId。
     *
     * 供 [AgentEvent.ToolProgress] 补 round —— 进度来自工具自己的线程（ACP 读线程），
     * 那里拿不到 round，也拿不到任何线程本地状态。null 时进度事件会被丢弃（round 无意义），
     * 用于纯单测路径。
     */
    private val progressScope: AgentModelClient.ProgressScope? = null,
    /** 摘要分批进度出口；默认静默，只在真实 App 运行里接 AndroidAgentLogger。 */
    private val compactionLog: (String) -> Unit = {},
    /** 摘要请求的 token 用量出口；默认静默，只在真实 App 运行里接到用量账本。 */
    private val compactionUsage: (AgentTokenUsage) -> Unit = {},
    /**
     * 每轮实际使用的模型出口；默认静默。
     *
     * 运行中可热切换模型，「这一轮到底用的哪个模型」只能从日志追溯：没有这行时
     * 只能看到请求成功/失败，无法判断切换是否真的落到下一轮。
     */
    private val roundLog: (String) -> Unit = {},
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val toolGuard = AgentToolGuard()
    /** 并行子代理共享的收尾锁：guard/spill/敏感集合都不是线程安全的，工具本身并行，收尾串行。 */
    private val toolFinalizeLock = Any()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val turnMetrics = TurnMetricsAccumulator()
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
        onBatch = compactionLog,
        onUsage = compactionUsage,
        // 复用同一个出口，但加前缀：压缩判定每轮都输出，而摘要分批只在真正压缩时有，
        // 混在一起会让日志读不清。
        onDecision = { detail -> compactionLog("agent_context_decision run=$operationId $detail") },
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1
        var precedingTools: JSONArray? = null

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // Anthropic 思考签名绑定发出工具调用时的 system 与 tools；工具结果回传后再刷新目录。
            val roundTools = if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            var roundUsage: AgentTokenUsage? = null
            var stepStartedAtMs = monotonicNowMs()
            var firstTokenAtMs: Long? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            // 每轮都重新取一次配置：运行中热切换的模型 / 思考等级从下一轮模型请求开始生效，
            // 正在飞行的那次请求不受影响（见 AgentRunController.applyModelConfig）。
            val roundConfig = runController.effectiveConfig(config)
            roundLog(
                "agent_round_model run=$operationId round=$round model=${roundConfig.model} " +
                    "provider=${roundConfig.providerId} " +
                    "effort=${roundConfig.effectiveReasoningEffort.wireValue}"
            )
            // 协议适配器跟着模型走：跨 provider 热切换（OpenAI 兼容 → Anthropic）时
            // 若仍用启动时的适配器，请求会被打成旧协议。适配器是无状态单例，每轮取一次开销为零。
            // 配置没变时沿用注入的 provider：单测注入的假客户端靠这一点，
            // 也避免每轮白建一个客户端。
            context.updateModel(roundConfig)
            val roundProvider = if (roundConfig == config) {
                provider
            } else {
                ProviderClientFactory.getClient(roundConfig)
            }
            // 同一回合的重试可能换模型：这里跟着「当前这次尝试」走，
            // 好让事件流里标出的 model / effort / attempt 与真实发出的请求一致。
            val attemptStartRound = round
            var attemptConfig = roundConfig
            var attemptProvider = roundProvider
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(roundConfig, requestMessages, roundTools, sessionId, purpose),
                    provider = roundProvider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) {
                            roundInputTokens = null
                            roundUsage = null
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                        }
                        if (providerEvent is ProviderEvent.RequestStarted) {
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        if (firstTokenAtMs == null &&
                            providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.delta.isNotEmpty()
                        ) {
                            firstTokenAtMs = monotonicNowMs()
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundUsage = providerEvent.usage
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        // Usage 不往上报：本轮用量在回合结束时统一以累计值发出
                        // （见下方 onEvent(AgentEvent.UsageReceived(...))），
                        // 这里再发一次会让同一份用量被记两遍。
                        if (providerEvent !is ProviderEvent.Usage) {
                            providerEvent.toAgentEvent(
                                round = attemptRound,
                                config = attemptConfig,
                                attempt = attemptRound - attemptStartRound + 1,
                            )?.let(onEvent)
                        }
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                    /*
                     * 重试前重新解析请求：用户在本回合退避期间切了模型，
                     * 重试就整组用新配置（新请求体 + 新协议适配器），而不是等退避耗完再换。
                     */
                    nextAttempt = { attemptRound ->
                        val previousConfig = attemptConfig
                        val nextConfig = runController.effectiveConfig(config)
                        attemptConfig = nextConfig
                        // 没切就不重建：单测注入的 provider 必须继续用；
                        // 切了才换适配器，跨 provider（OpenAI 兼容 → Anthropic）才不会拿旧协议发新请求。
                        if (nextConfig != previousConfig) {
                            context.updateModel(nextConfig)
                            attemptProvider = ProviderClientFactory.getClient(nextConfig)
                            roundLog(
                                "agent_round_model run=$operationId round=$attemptRound " +
                                    "model=${nextConfig.model} provider=${nextConfig.providerId} " +
                                    "effort=${nextConfig.effectiveReasoningEffort.wireValue} retry=true"
                            )
                        }
                        AgentModelRetry.Attempt(
                            request = ProviderRequest(nextConfig, requestMessages, roundTools, sessionId, purpose),
                            provider = attemptProvider,
                        )
                    },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } catch (overflow: AgentModelFailure) {
                // 端点报容量硬超（retryable=false，原本直接把整个 run 判死）：
                // 本轮已经证明「这个量级的请求会被拒」。强制压缩、把上限钉在能通过的峰值，
                // 然后重跑**同一轮**。requestMessages 是压缩前的快照，必须重建，
                // 所以是 continue 回循环顶部，不是原地重试。
                if (!overflow.isContextOverflow() || overflow.replayUnsafe ||
                    !purpose.allowsTools || !context.canRecoverFromOverflow()
                ) {
                    throw overflow
                }
                context.noteContextOverflow()
                roundLog(
                    "agent_context_overflow run=$operationId round=$round " +
                        "tokens=${roundInputTokens ?: -1} rescued=true"
                )
                continue
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            val completedAtMs = monotonicNowMs()
            val stepLlmMs = (completedAtMs - stepStartedAtMs).coerceAtLeast(0L)
            val stepTtftMs = firstTokenAtMs?.let { (it - stepStartedAtMs).coerceAtLeast(0L) }
            val stepDecodeMs = firstTokenAtMs?.let { (completedAtMs - it).coerceAtLeast(0L) }
            context.observeInputTokens(roundInputTokens)
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )
            val accumulatedUsage = turnMetrics.recordStep(
                stepLlmMs = stepLlmMs,
                stepTtftMs = stepTtftMs,
                stepDecodeMs = stepDecodeMs,
                stepUsage = roundUsage,
                stepPromptTokens = roundInputTokens,
            )
            if (!accumulatedUsage.isEmpty) {
                onEvent(AgentEvent.UsageReceived(round = round, usage = accumulatedUsage))
            }

            if (toolCalls.isNotEmpty()) {
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                    publishTranscript()
                }
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * 拒答或过滤会在流中途截断回复，半截正文、思考块和工具调用都不能进入上下文：
     * 带着被截断的签名块继续请求会被上游判定为改动了 thinking 块，同一会话随后每轮都会 400。
     * 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                explanation?.let { append("服务商说明：").append(it) }
                append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户插话（当前最新指令）：$supplement\n\n" +
            "按这句话重新判断方向。它可能替换原来的任务：原任务里还没完成的部分先停下，" +
            "除非用户明确要求继续；已经完成且仍然有用的结果可以复用，但不要为了“做完”继续执行。" +
            "如果用户只是说“继续”，再继续原任务。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    /**
     * 同一轮里模型可能一次发起多个 `subagent`。它们之间没有数据依赖，串行跑只是白等；
     * 但普通工具仍顺序执行：写文件、改配置这类调用有隐式先后关系，不能擅自并发。
     * 子代理内部对 GUI 的访问仍受 `GuiControlCoordinator` 按屏互斥，不会两个代理同时点同一块屏。
     */
    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
    ): List<ToolOutcome> {
        fun outcomeFor(call: AgentModelClient.ToolCall): ToolOutcome = when (stopReason) {
            AssistantStopReason.TOOL_USE -> executeTool(round, call)
            AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                round, call, "TRUNCATED_TOOL_CALL",
                "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
            )
            else -> rejectedToolOutcome(
                round, call, "UNEXPECTED_TOOL_CALL",
                "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
            )
        }

        val parallelSubagents = stopReason == AssistantStopReason.TOOL_USE &&
            toolCalls.size > 1 &&
            toolCalls.all { it.name == AgentSubagentToolCatalog.TOOL_NAME }
        if (!parallelSubagents) return toolCalls.map(::outcomeFor)

        val pool = Executors.newFixedThreadPool(
            minOf(toolCalls.size, MAX_PARALLEL_SUBAGENTS),
        ) { runnable ->
            Thread(runnable, "eta-subagent-batch").apply { isDaemon = true }
        }
        return try {
            val startedAtNanos = System.nanoTime()
            val futures = toolCalls.map { call -> pool.submit(Callable { outcomeFor(call) }) }
            futures.mapIndexed { index, future ->
                try {
                    future.get()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AgentRunCancelledException()
                } catch (failed: ExecutionException) {
                    val cause = failed.cause ?: failed
                    // 取消是终止信号，必须往外抛；其它异常只影响它自己那一个子代理。
                    if (cause is AgentRunCancelledException) throw cause
                    runController.throwIfCancelled()
                    // 从提交算起：失败之前它可能已经跑了很久，写 0 会让卡片显示「耗时 0 秒」。
                    // 捕获到异常的那一刻就是它的终态，误差只是取结果前的排队时间。
                    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                    failedBatchOutcome(round, toolCalls[index], cause, elapsedMs)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 把一个并行子代理的意外异常折成**它自己**的失败结果。
     *
     * 为什么不能整批抛出：这几个子代理都已经在跑，各自的结果都真实存在（台账里也已经记了完成
     * 或失败）；整批抛会让父 loop 提前失败，其它子代理的结果全部丢掉，而台账说它们完成了 ——
     * 界面、台账、模型三方各说一套。逐个折叠后：成功的仍是成功结果，失败的只有一个，
     * 模型能看见剩下的部分并重新规划。
     *
     * 只发 ToolFinished，不补 ToolStarted：异常可能发生在开始事件之后（那时再发一次
     * 会让同一次调用出现两个开始）。投影层按 toolCallId 找现有卡片，找不到就跳过，两边都安全。
     */
    private fun failedBatchOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        cause: Throwable,
        elapsedMs: Long,
    ): ToolOutcome {
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", "SUBAGENT_BATCH_ERROR")
                .put("message", "这个子代理执行时抛出异常：${cause.message ?: cause.javaClass.simpleName}")
                .put("tool_call_id", toolCall.id)
                .toString(),
        )
        synchronized(toolFinalizeLock) {
            emitToolFinished(round, toolCall, result, elapsedMs)
            onToolTrace(round, toolCall, result, elapsedMs)
        }
        return ToolOutcome(toolCall, result)
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "工具未在本轮能力目录中提供，请使用当前可用工具",
            )
        }
        // 提问要把问题与候选答案一并带给界面：用户点选项即可作答，不用手打一遍。
        val ask = if (toolCall.name == AgentAskUserToolCatalog.NAME) {
            AgentAskUserToolCatalog.askFromArguments(toolCall.argumentsJson)
        } else {
            null
        }
        // 并行 subagent 会同时从多个线程进入这里：事件投影与归档列表不是线程安全的，
        // 开始事件也按同一把收尾锁串行。
        synchronized(toolFinalizeLock) {
            onEvent(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    argsPreview = traceFormatter.summarizeArguments(toolCall),
                    command = traceFormatter.displayCommand(toolCall),
                    askQuestion = ask?.question,
                    askOptions = ask?.options.orEmpty(),
                )
            )
        }

        val startedAtNanos = System.nanoTime()
        // 先广播轮次再执行：进度可能在工具内部第一条日志就产生，
        // 顺序反了会让最早（往往最关键）的几条进度因轮次未登记而被丢弃。
        // 必须带上 toolCallId：ACP 的进度来自它自己的 HandlerThread，
        // 靠线程本地变量是拿不到的（见 ProgressScope 的契约说明）。
        progressScope?.enterRound(round, toolCall.id)
        // 取消是异常路径：CancellationException 会穿过下面所有代码直接往外抛，
        // 若不用 finally 收尾，登记会一直留着，后续工具的进度可能查不到自己的轮次。
        var result = try {
            try {
                toolExecutor.execute(toolCall)
            } finally {
                progressScope?.exitRound(toolCall.id)
            }
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        // 原地打转时把提醒塞进这次工具结果：模型下一轮就能看到，且不需要伪造用户消息。
        // 顺序很重要：guard 先看原始结果（错误码完整），命中提醒后再溢写大输出，
        // 最后把提醒贴到最终内容上——否则溢写后的定位符里没有 code，重复失败再也认不出来。
        // 并行子代理时工具本身并发，但 guard/spill/敏感集合与事件收尾必须串行：
        // 这些状态没有线程安全契约，交给锁比让两个线程各写一半便宜得多。
        val finalResult = synchronized(toolFinalizeLock) {
            var finalized = result
            val guardNote = toolGuard.observe(toolCall, finalized.content)
            if (finalized.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                sensitiveToolCallIds += toolCall.id
            } else {
                // 敏感结果不落盘：那类数据连会话历史都要脱敏，写文件等于绕过整条脱敏链路。
                toolSpill?.maybeSpill(toolCall.name, operationId, toolCall.id, finalized.content)?.let { spilled ->
                    finalized = finalized.copy(content = spilled.content)
                }
            }
            guardNote?.let { note -> finalized = finalized.copy(content = AgentToolGuard.attach(finalized.content, note)) }
            finalized
        turnMetrics.recordToolMs((monotonicNowMs() - toolStartMs).coerceAtLeast(0L))
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        synchronized(toolFinalizeLock) {
            emitToolFinished(round, toolCall, finalResult, elapsedMs)
            onToolTrace(round, toolCall, finalResult, elapsedMs)
        }
        return ToolOutcome(toolCall, finalResult)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        onToolTrace(round, toolCall, result, 0)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
        elapsedMs: Long? = null,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                // 敏感结果连原文都不落盘，diff 同样不能进事件流。
                detail = if (result.sensitive) null else traceFormatter.resultDetail(toolCall.name, result),
                elapsedMs = elapsedMs,
                filePath = if (toolCall.name == "edit_file" || toolCall.name == "write_file") {
                    runCatching { org.json.JSONObject(result.content).optString("path").trim() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                },
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(
        round: Int,
        config: AgentModelClient.ModelConfig? = null,
        attempt: Int = 1,
    ): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(
                round = round,
                model = config?.model.orEmpty(),
                provider = config?.providerId.orEmpty(),
                effort = config?.effectiveReasoningEffort?.wireValue.orEmpty(),
                attempt = attempt,
            )
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            // 上游 3.3.5：模型直出的搜索建议（搜索建议胶囊）随事件流下发。
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private companion object {
        /** 同一轮最多同时跑几个子代理；再多会挤占主进程内存与模型连接，收益递减。 */
        const val MAX_PARALLEL_SUBAGENTS = 4
    }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private class TurnMetricsAccumulator {
        private var stepCount: Int = 0
        private var llmMs: Long = 0L
        private var toolMs: Long = 0L
        private var ttftMs: Long = 0L
        private var ttftSteps: Int = 0
        private var decodeMs: Long = 0L
        private var decodeTokens: Int = 0
        private var contextTokens: Int? = null
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var reasoningTokens: Int? = null
        private var cachedTokens: Int? = null

        fun reset() {
            stepCount = 0
            llmMs = 0L
            toolMs = 0L
            ttftMs = 0L
            ttftSteps = 0
            decodeMs = 0L
            decodeTokens = 0
            contextTokens = null
            inputTokens = null
            outputTokens = null
            reasoningTokens = null
            cachedTokens = null
        }

        fun recordStep(
            stepLlmMs: Long,
            stepTtftMs: Long?,
            stepDecodeMs: Long?,
            stepUsage: AgentTokenUsage?,
            stepPromptTokens: Int? = null,
        ): AgentTokenUsage {
            stepCount += 1
            llmMs += stepLlmMs.coerceAtLeast(0L)
            if (stepTtftMs != null) {
                ttftMs += stepTtftMs.coerceAtLeast(0L)
                ttftSteps += 1
            }
            val stepOut = stepUsage?.outputTokens
            if (stepDecodeMs != null && stepOut != null && stepOut > 0) {
                decodeMs += stepDecodeMs.coerceAtLeast(0L)
                decodeTokens += stepOut
            }
            stepUsage?.contextTokens?.let { contextTokens = it }
            (stepPromptTokens ?: stepUsage?.inputTokens)?.let { inputTokens = (inputTokens ?: 0) + it }
            stepUsage?.outputTokens?.let { outputTokens = (outputTokens ?: 0) + it }
            stepUsage?.reasoningTokens?.let { reasoningTokens = (reasoningTokens ?: 0) + it }
            stepUsage?.cachedTokens?.let { cachedTokens = (cachedTokens ?: 0) + it }
            return AgentTokenUsage(
                contextTokens = contextTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cachedTokens = cachedTokens,
                stepCount = stepCount.takeIf { it > 0 },
                llmMs = llmMs.takeIf { stepCount > 0 },
                toolMs = toolMs.takeIf { it > 0L },
                ttftMs = ttftMs.takeIf { ttftSteps > 0 },
                ttftSteps = ttftSteps.takeIf { it > 0 },
                decodeMs = decodeMs.takeIf { decodeTokens > 0 && it > 0L },
                decodeTokens = decodeTokens.takeIf { decodeMs > 0L && it > 0 },
            )
        }

        fun recordToolMs(elapsedMs: Long) {
            toolMs += elapsedMs.coerceAtLeast(0L)
        }
    }
}
