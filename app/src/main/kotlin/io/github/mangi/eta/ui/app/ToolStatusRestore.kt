package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/**
 * 从数据库恢复工具状态时的判定。
 *
 * `Running` 落库后有两种含义，必须分开：
 * - **仍在跑**：消息所属的 run 还是服务端活跃 run（`messageId` 形如 `"$runId-tool-$round-$callId"`）。
 *   这时要保留 `Running`，界面才会继续用落盘的 `started_at` 接着计时、标题继续呼吸动画。
 * - **残留**：进程上次退出/被杀时没来得及写终态。保留 `Running` 会让卡片永远转圈，
 *   所以降级成 `Unknown`（结果未知），把「它已经不在跑了」这个事实如实显示出来。
 *
 * 以前这里**无条件**把 Running 转成 Unknown，直接导致用户看到的
 * 「切会话回来后计时消失、卡片像卡死」：运行其实还在，只是界面不再认为它在跑。
 */
internal object ToolStatusRestore {

    fun resolve(
        rawStatus: String?,
        messageId: String,
        activeRunIds: Set<String>,
    ): ToolActivityStatusUi {
        val parsed = runCatching { ToolActivityStatusUi.valueOf(rawStatus.orEmpty()) }.getOrNull()
            ?: return ToolActivityStatusUi.Unknown
        if (parsed != ToolActivityStatusUi.Running) return parsed
        return if (belongsToAnyRun(messageId, activeRunIds)) {
            ToolActivityStatusUi.Running
        } else {
            ToolActivityStatusUi.Unknown
        }
    }

    /** 消息 id 由 [io.github.mangi.eta.ui.app.AgentRunMessageProjector] 生成：`"$runId-tool-…"`。 */
    fun belongsToAnyRun(messageId: String, activeRunIds: Set<String>): Boolean =
        activeRunIds.any { runId -> runId.isNotBlank() && messageId.startsWith("$runId-tool-") }
}
