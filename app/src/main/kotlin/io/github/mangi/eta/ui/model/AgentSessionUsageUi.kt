package io.github.mangi.eta.ui.model

import io.github.mangi.eta.ui.model.AgentChatMessageUi

/**
 * 会话级用量汇总。
 *
 * 为什么单独一个投影而不是在 Composable 里累加：这里的口径有几处容易算错，
 * 而算错时**界面看起来完全正常**（数字都在，只是错的）。典型三处：
 *
 * 1. **缓存命中率的分母**。必须用服务商返回的**总输入**（[TokenUsageUi.promptTokens]），
 *    但它可能缺失（旧消息）。缺失时要回退到 `inputTokens` —— 直接把原样 input 当总输入，
 *    在 Anthropic 下会漏掉全部缓存读写，命中率被系统性高估。
 * 2. **缓存命中率的分子**。只能取 `cachedTokens`，不能拿 `inputTokens - cachedTokens` 反推：
 *    OpenAI 兼容端点的 `inputTokens` 本身已含缓存，反推会在命中时算出负数。
 * 3. **会话累计是求和，不是取最后一次**。逐轮累加才有意义；取最新一条会把多轮回退成单轮。
 *
 * 输出 token 含推理 token：推理也是模型真实生成的，排除它会让速度虚高。
 */
internal data class AgentSessionUsageUi(
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
    val cachedTokens: Int = 0,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val requestCount: Int = 0,
) {
    /** 缓存命中率；分母为 0 或数据不足时为 null（界面显示「—」，不要显示 0%）。 */
    val cacheHitRate: Float?
        get() = if (promptTokens > 0 && cachedTokens >= 0) {
            (cachedTokens.toFloat() / promptTokens).coerceIn(0f, 1f)
        } else {
            null
        }

    /** 未走缓存的输入。命中率的分母是总输入，这里只表示「这部分没命中」。 */
    val uncachedInputTokens: Int?
        get() = if (promptTokens > 0) (promptTokens - cachedTokens).coerceAtLeast(0) else null

    /** 输出总量（正文 + 推理）；两者都缺时为 null 而不是 0。 */
    val totalOutputTokens: Int?
        get() = if (outputTokens == 0 && reasoningTokens == 0) null else outputTokens + reasoningTokens

    val isEmpty: Boolean
        get() = contextTokens == null && promptTokens == 0 && outputTokens == 0 && reasoningTokens == 0

    companion object {
        /**
         * 聚合 [messages] 里所有 assistant 消息的用量。
         *
         * @param contextWindow 当前模型的上下文窗口，用于算占用比例；未知传 null。
         */
        fun of(
            messages: List<AgentChatMessageUi>,
            contextWindow: Int? = null,
        ): AgentSessionUsageUi {
            var cached = 0
            var prompt = 0
            var output = 0
            var reasoning = 0
            var requests = 0
            // 上下文占用取「最近一条有值的」，它表示当前窗口里的实际占用，
            // 不能求和 —— 求和会把历史每轮的占用加在一起，远超窗口大小。
            var latestContext: Int? = null

            for (message in messages) {
                if (message !is AgentMessageUi) continue
                val usage = message.usage ?: continue
                if (usage.isEmpty) continue
                requests += 1
                usage.cachedTokens?.let { cached += it }
                // 没给总输入时回退到 inputTokens：旧消息只有这个字段。
                usage.promptTokens?.let { prompt += it } ?: usage.inputTokens?.let { prompt += it }
                usage.outputTokens?.let { output += it }
                usage.reasoningTokens?.let { reasoning += it }
                usage.contextTokens?.let { latestContext = it }
            }

            return AgentSessionUsageUi(
                contextTokens = latestContext,
                contextWindow = contextWindow,
                cachedTokens = cached,
                promptTokens = prompt,
                outputTokens = output,
                reasoningTokens = reasoning,
                requestCount = requests,
            )
        }
    }
}
