package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 模型请求重试设置（全局，不按会话）。
 *
 * 为什么做成设置项而不是写死：限流与网络抖动在不同服务商差异很大。
 * 有的按次计费、限流窗口很短，用户希望快速失败后手动换模型；
 * 有的长上下文请求本身就要几十秒，重试一次比直接失败划算。
 *
 * [maxRetries] = 0 表示只尝试一次；[enabled] = false 等价于 0，
 * 保留两个字段是为了让界面能「一键关闭」而不丢失之前设置的次数。
 */
@Serializable
data class ModelRetrySettings(
    val enabled: Boolean = true,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    /** 实际生效的重试次数：关闭时为 0。 */
    val effectiveMaxRetries: Int
        get() = if (enabled) maxRetries.coerceIn(0, MAX_RETRIES) else 0

    fun normalized(): ModelRetrySettings =
        copy(maxRetries = maxRetries.coerceIn(0, MAX_RETRIES))

    companion object {
        const val DEFAULT_MAX_RETRIES = 4
        const val MAX_RETRIES = 8
    }
}
