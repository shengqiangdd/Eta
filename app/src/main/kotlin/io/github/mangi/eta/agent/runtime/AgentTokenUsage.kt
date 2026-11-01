package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /**
     * 服务商返回的**总输入 token**（缓存读写是否单列由协议决定，这里统一折算成总量）。
     *
     * Anthropic 的 `input_tokens` 不含缓存，需要加 `cache_read` 与 `cache_creation`；
     * OpenAI 兼容端点与 Gemini 的输入总量本身已包含缓存命中。缓存命中率必须以它为分母，
     * 否则「已包含」的协议会被重复计算，命中率被系统性低估。
     */
    val promptTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            promptTokens == null
}
