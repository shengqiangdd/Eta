package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    /**
     * 输出 token 总量，**已包含推理**。
     *
     * 这是跨 provider 的统一口径，不是某个协议的原始字段：
     * - OpenAI（chat / responses）：`completion_tokens` / `output_tokens` 本身已含
     *   `reasoning_tokens`（在 `*_tokens_details` 里）；
     * - Anthropic：`output_tokens` 已含 `thinking_output_tokens`；
     * - Gemini：`total_output_tokens` **不含** `total_thought_tokens`，解析时相加折算。
     *
     * 不统一的话，同一个字段名在不同 provider 下含义不同，下游任何基于它的计算
     * （输出总量、生成速度、推理占比）都会随 provider 漂移，而且不会有任何报错。
     */
    val outputTokens: Int? = null,

    /** 推理 token，是 [outputTokens] 的**子集**（已按上面的口径统一）。 */
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
    val stepCount: Int? = null,
    val llmMs: Long? = null,
    val toolMs: Long? = null,
    val ttftMs: Long? = null,
    val ttftSteps: Int? = null,
    val decodeMs: Long? = null,
    val decodeTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            promptTokens == null &&
            stepCount == null &&
            llmMs == null &&
            toolMs == null &&
            ttftMs == null &&
            ttftSteps == null &&
            decodeMs == null &&
            decodeTokens == null}
