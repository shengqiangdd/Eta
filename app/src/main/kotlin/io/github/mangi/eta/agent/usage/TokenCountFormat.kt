package io.github.mangi.eta.agent.usage

import java.util.Locale

/**
 * token 数量的紧凑展示：运行详情、运行导出、会话导出共用这一份实现。
 *
 * 三处各写一份的后果已经出现过：同一个数在不同界面一个按 k 分档、一个全量输出，
 * 用户看到两个不同的数，只会认为统计坏了。
 */
internal fun formatTokenCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1000.0)
    else -> value.toString()
}
