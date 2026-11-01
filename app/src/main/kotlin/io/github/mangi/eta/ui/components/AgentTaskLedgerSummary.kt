package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskStatus

/**
 * 台账卡收起时那一行要显示的状态计数。
 *
 * 为什么单独抽成纯函数：计数算错（把已取消算成运行中、总数漏掉一条）在界面上完全看不出来，
 * 只会在「到底还剩几个在跑」这种判断上悄悄骗人；而卡片本身要真机截图才能看，本机跑不了。
 * 映射放在这里，JVM 单测就能守住它。
 */
internal data class AgentTaskLedgerSummary(
    val total: Int,
    val completed: Int,
    val running: Int,
    val failed: Int,
    val cancelled: Int,
)

/**
 * 台账 → 卡片计数。
 *
 * 复用 [AgentTaskLedger.counts]（`tasks` 工具也用同一份），避免界面和工具各算一套导致
 * 两处数字对不上。将来加新状态时，`counts()` 会自动带上它，但这里不显式归类就会漏进
 * 「总数 - 已完成」的差额里 —— 单测用「四个桶之和 == total」守着这一点。
 */
internal fun AgentTaskLedger.cardSummary(): AgentTaskLedgerSummary {
    val byStatus = counts()
    return AgentTaskLedgerSummary(
        total = entries.size,
        completed = byStatus[AgentTaskStatus.COMPLETED] ?: 0,
        running = byStatus[AgentTaskStatus.RUNNING] ?: 0,
        failed = byStatus[AgentTaskStatus.FAILED] ?: 0,
        cancelled = byStatus[AgentTaskStatus.CANCELLED] ?: 0,
    )
}
