package io.github.mangi.eta.ui.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 把会话用量格式化成可读文本。
 *
 * 单独成函数（而不是写在 Composable 里）的原因：这里的取整与单位选择有明确口径，
 * 写错时界面依然「看起来对」，但会让用户误判花费。三条约定：
 *
 * 1. **千分位用缩写**（1.2k / 3.4M），不用本地化分组符号 —— 输入栏胶囊宽度很窄，
 *    完整数字会被截断。
 * 2. **命中率显示为百分比整数**，且**数据不足时显示 `—` 而不是 0%**。
 *    0% 表示「完全没有命中」，与「不知道」是两件事。
 * 3. **tok/s 保留一位小数**，四舍五入；低于 0.05 显示 `<0.1` 避免误导成 0。
 */
internal object AgentSessionUsageFormatter {

    /** 1234 → "1.2k"；999 → "999"；12_345_678 → "12.3M"。 */
    fun abbreviateCount(value: Int): String {
        val abs = kotlin.math.abs(value.toLong())
        return when {
            abs < 1_000L -> value.toString()
            abs < 1_000_000L -> oneDecimal(value / 1_000.0) + "k"
            abs < 1_000_000_000L -> oneDecimal(value / 1_000_000.0) + "M"
            else -> oneDecimal(value / 1_000_000_000.0) + "G"
        }
    }

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        // 去掉无意义的 .0：1.0k 读起来比 1k 啰嗦。
        return if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    /** 命中率；null 或分母为 0 时返回 null（调用方应显示 `—`）。 */
    fun percentOrNull(rate: Float?): String? =
        rate?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" }

    /** 每秒输出 token 数。 */
    fun tokensPerSecondOrNull(outputTokens: Int?, elapsedMillis: Long?): String? {
        if (outputTokens == null || outputTokens <= 0) return null
        if (elapsedMillis == null || elapsedMillis <= 0) return null
        val tps = outputTokens * 1000.0 / elapsedMillis
        return if (tps < 0.05) "<0.1" else String.format(Locale.US, "%.1f", tps)
    }

    /** 首字延迟。 */
    fun firstTokenOrNull(firstTokenMillis: Long?): String? {
        if (firstTokenMillis == null || firstTokenMillis < 0) return null
        return if (firstTokenMillis < 1_000L) {
            "${firstTokenMillis}ms"
        } else {
            String.format(Locale.US, "%.1fs", firstTokenMillis / 1000.0)
        }
    }

    /**
     * 组装 tooltip 里的多行摘要。缺数据的项整行省略，不写「—」占位
     * （tooltip 空间有限，占位行只会挤掉有用信息）。
     */
    fun summaryLines(
        usage: AgentSessionUsageUi,
        inputLabel: String,
        outputLabel: String,
        cacheLabel: String,
    ): List<String> {
        val lines = mutableListOf<String>()
        if (usage.promptTokens > 0) lines += "$inputLabel ${abbreviateCount(usage.promptTokens)}"
        usage.totalOutputTokens?.let { lines += "$outputLabel ${abbreviateCount(it)}" }
        percentOrNull(usage.cacheHitRate)?.let { lines += "$cacheLabel $it" }
        return lines
    }
}
