package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
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
    ANTHROPIC_MESSAGES
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
        get() = when {
            purpose.allowsTools -> config
            purpose == ProviderRequestPurpose.COMPACTION -> config.copy(
                hostedWebSearchEnabled = false,
                extraBodyJson = "",
                customBody = emptyList(),
                thinkingEnabled = false,
                reasoningEffort = summaryReasoningEffort(config),
            )
            else -> config.copy(
                hostedWebSearchEnabled = false,
                extraBodyJson = "",
                customBody = emptyList(),
            )
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
         * 只在模型确实支持关闭思考时覆盖（`mandatory` 或不在可选集合里就保持用户设置），
         * 否则会触发 `当前模型强制启用思考，不能选择 Off` 的校验失败。
         */
        internal fun summaryReasoningEffort(
            config: AgentModelClient.ModelConfig,
        ): io.github.mangi.eta.data.model.ReasoningEffort {
            val capabilities = config.reasoningCapabilities ?: return config.effectiveReasoningEffort
            if (capabilities.mandatory) return config.effectiveReasoningEffort
            if (io.github.mangi.eta.data.model.ReasoningEffort.OFF !in capabilities.selectableEfforts) {
                return config.effectiveReasoningEffort
            }
            return io.github.mangi.eta.data.model.ReasoningEffort.OFF
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

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
