package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_INTERACTIONS,
}

internal enum class ProviderRequestPurpose {
    CHAT, COMPACTION, REPLY_REWRITE, TRANSCRIPT_REFINE;

    val allowsTools: Boolean get() = this == CHAT
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
) {
    val effectiveConfig: AgentModelClient.ModelConfig
        get() {
            // 订阅登录（OAuth）的 provider 由连接档案决定实际端点，密钥不进配置。
            val connected = ProviderConnectionProfile.forAuthMode(config.authMode)?.let { profile ->
                config.copy(
                    baseUrl = profile.baseUrl,
                    providerType = profile.providerType,
                    providerSourceType = profile.sourceType,
                    openAiEndpointMode = profile.endpointMode,
                    apiKey = "",
                )
            } ?: config
            return when {
                purpose.allowsTools -> connected
                purpose == ProviderRequestPurpose.COMPACTION -> connected.copy(
                    hostedWebSearchEnabled = false,
                    extraBodyJson = "",
                    customBody = emptyList(),
                    thinkingEnabled = false,
                    reasoningEffort = summaryReasoningEffort(connected),
                )
                else -> connected.copy(
                    hostedWebSearchEnabled = false,
                    extraBodyJson = "",
                    customBody = emptyList(),
                )
            }
        }
    val effectiveTools: JSONArray get() = if (purpose.allowsTools) tools else JSONArray()

    companion object {
        /**
         * 摘要请求一律关掉思考（模型允许时），这是压缩耗时的主要来源。
         *
         * 实测依据（PKX110 / Android 16，2026-10-25 生产用量账本与 run-traces）：
         * - 89 次 compact 请求共 462,453 输出 token，其中 **348,820 是 reasoning（75%）**，
         *   平均每次 3,920 个思考 token；
         * - 26 次完整压缩的耗时中位数 79 秒、均值 91 秒、最长 199 秒，每次 2~3 批 + 合并
         *   共 3~4 次串行请求 —— 与「思考 token 是输出主体」完全吻合；
         * - 同一模型上普通对话每轮 reasoning 只有约 300 token，说明不是端点强制思考。
         *
         * 摘要的产物是 6,000 字符的正文，思考对它没有可测收益，却让每次压缩多花一分钟以上。
         *
         * 三档处理（按收益排序）：
         * 1. 能关就关（`canDisable` 且非 `mandatory`）—— 省掉全部 reasoning；
         * 2. 强制思考的模型**降到最低显式档**：原来这里是「mandatory 就沿用用户设置」，
         *    用户开着 High 时，每次压缩都按 High 思考 —— 慢了却没有任何提示；
         * 3. 模型只提供 Default（没有显式档）时沿用用户设置：Default 的决定权在服务端，
         *    擅自传别的值反而会触发「当前模型不支持……」的校验失败。
         *
         * 不能传集合外的值：`ProviderReasoning.validatedEffort` 要求 effort ∈ selectableEfforts，
         * 而库里可能残留旧模型时代的档位，所以兜底也要回到 Default。
         */
        internal fun summaryReasoningEffort(
            config: AgentModelClient.ModelConfig,
        ): ReasoningEffort {
            val capabilities = config.reasoningCapabilities ?: return config.effectiveReasoningEffort
            val userEffort = config.effectiveReasoningEffort
            val selectable = capabilities.selectableEfforts
            if (!capabilities.mandatory && ReasoningEffort.OFF in selectable) return ReasoningEffort.OFF
            val cheapestExplicit = selectable
                .asSequence()
                .filter { it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT }
                .minByOrNull { it.rank }
            if (cheapestExplicit != null) return cheapestExplicit
            // 没有显式档可用：用户设置合法就照旧，不合法（换模型留下的旧值）退回 Default。
            return if (userEffort in selectable) userEffort else ReasoningEffort.DEFAULT
        }
    }
}

internal data class ProviderResponse(
    val assistantMessage: JSONObject
) {
    val stopReason: AssistantStopReason
        get() = AssistantStopReason.fromWireValue(assistantMessage.optString("finish_reason"))
}

internal enum class AssistantStopReason {
    END_TURN,
    TOOL_USE,
    OUTPUT_LIMIT,
    CONTENT_FILTER,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): AssistantStopReason =
            when (value?.trim()?.lowercase()) {
                "stop", "end_turn" -> END_TURN
                "tool_calls", "tool_use" -> TOOL_USE
                "length", "max_tokens" -> OUTPUT_LIMIT
                "content_filter", "refusal" -> CONTENT_FILTER
                else -> UNKNOWN
            }
    }
}

internal enum class AssistantBlockKind {
    TEXT,
    THINKING,
    TOOL_CALL,
}

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int,
        /**
         * 服务端 `Retry-After`（秒）。限流时服务商常明确要求等待，本地退避不能更短。
         * 解析失败或缺失时为 null。
         */
        val retryAfterSeconds: Long? = null,
    ) : ProviderEvent

    data class BlockStart(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : ProviderEvent

    data class BlockDelta(
        val kind: AssistantBlockKind,
        val index: Int,
        val delta: String,
    ) : ProviderEvent

    data class BlockEnd(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val content: String = "",
        val replaceContent: Boolean = false,
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage,
        val contextInputTokens: Int? = usage.inputTokens,
    ) : ProviderEvent

    data class HostedToolStarted(
        val id: String,
        val name: String,
    ) : ProviderEvent

    data class HostedToolFinished(
        val id: String,
        val name: String,
        val success: Boolean,
    ) : ProviderEvent

    data class SearchSuggestions(
        val index: Int,
        val html: String,
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
