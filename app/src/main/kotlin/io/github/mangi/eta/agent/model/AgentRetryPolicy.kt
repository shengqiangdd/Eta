package io.github.mangi.eta.agent.model

import kotlin.math.pow

/**
 * 模型请求的重试预算。
 *
 * 设计取舍：
 * - 指数退避 + 抖动，避免多个会话在同一时刻一起重撞限流窗口；
 * - 服务端 `Retry-After` 优先于本地推算（限流时服务商通常明确要求等待秒数）；
 * - 上限可配，非法配置降级到默认值而不抛异常（设置来源不可信）；
 * - `maxRetries = 0` 表示只尝试一次，用于用户明确关闭重试。
 */
internal data class AgentRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val jitterRatio: Double = DEFAULT_JITTER_RATIO,
) {
    init {
        require(maxRetries in 0..MAX_RETRIES_LIMIT) { "maxRetries 超出范围" }
        require(baseDelayMs in MIN_DELAY_MS..MAX_BASE_DELAY_MS) { "baseDelayMs 超出范围" }
        require(maxDelayMs >= baseDelayMs && maxDelayMs <= MAX_DELAY_CEILING_MS) { "maxDelayMs 超出范围" }
        require(jitterRatio in 0.0..MAX_JITTER_RATIO) { "jitterRatio 超出范围" }
    }

    /**
     * 第 [retry] 次重试（从 1 开始）应等待的毫秒数。
     *
     * [retryAfterSeconds] 来自响应头；[random] 取 `[0, 1)`，由调用方注入以便测试可复现。
     * [minimumDelayMs] 是本次等待的下限；用于 Cloudflare 52x 等回源错误，给服务端更多恢复时间。
     */
    fun delayMs(
        retry: Int,
        retryAfterSeconds: Long? = null,
        random: Double = Math.random(),
        minimumDelayMs: Long = 0L,
    ): Long {
        val attempt = retry.coerceIn(1, maxRetries.coerceAtLeast(1))
        val exponent = (attempt - 1).coerceAtMost(MAX_EXPONENT)
        val exponential = (baseDelayMs * 2.0.pow(exponent)).toLong().coerceAtMost(maxDelayMs)
        val server = retryAfterSeconds
            ?.takeIf { it in 0..MAX_RETRY_AFTER_SECONDS }
            ?.let { it * 1_000 }
        val floor = minimumDelayMs.coerceAtLeast(0L)
        // 服务端要求、网关下限与本地退避取最大；抖动后仍不得低于下限。
        val base = maxOf(exponential, server ?: 0L, floor)
        val jittered = base * (1.0 + (random.coerceIn(0.0, 1.0) * 2 - 1) * jitterRatio)
        val ceiling = maxOf(maxDelayMs, server ?: 0L, floor)
        return maxOf(jittered.toLong().coerceIn(0L, ceiling), floor)
    }

    /** 是否还有重试预算。 */
    fun canRetry(retriesUsed: Int): Boolean = retriesUsed < maxRetries

    companion object {
        const val DEFAULT_MAX_RETRIES = 4
        const val DEFAULT_BASE_DELAY_MS = 2_000L
        const val DEFAULT_MAX_DELAY_MS = 60_000L
        const val DEFAULT_JITTER_RATIO = 0.2
        const val MAX_RETRIES_LIMIT = 8
        const val MAX_RETRY_AFTER_SECONDS = 900L

        private const val MIN_DELAY_MS = 100L
        private const val MAX_BASE_DELAY_MS = 30_000L
        private const val MAX_DELAY_CEILING_MS = 300_000L
        private const val MAX_JITTER_RATIO = 0.5
        private const val MAX_EXPONENT = 10

        /**
         * 从设置值构造；`null` 或非法值回落到默认策略。
         * 设置可能来自旧版本或被手工改坏的配置文件，这里不做异常冒泡。
         */
        fun fromSettings(maxRetries: Int?): AgentRetryPolicy {
            val retries = maxRetries?.takeIf { it in 0..MAX_RETRIES_LIMIT } ?: DEFAULT_MAX_RETRIES
            return AgentRetryPolicy(maxRetries = retries)
        }

        /** 解析 HTTP `Retry-After`：既接受秒数，也接受 HTTP-date（转为相对秒数）。 */
        fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { return it.takeIf { seconds -> seconds in 0..MAX_RETRY_AFTER_SECONDS } }
            val parsed = runCatching {
                java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return null
            val seconds = ((parsed - nowMs) / 1_000).coerceAtLeast(0)
            return seconds.takeIf { it <= MAX_RETRY_AFTER_SECONDS }
        }
    }
}
