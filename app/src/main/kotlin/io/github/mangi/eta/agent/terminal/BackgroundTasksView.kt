package io.github.mangi.eta.agent.terminal

/**
 * 后台任务卡片要展示的只读投影。
 *
 * 为什么不让界面直接读记录：卡片只关心「几条在跑、最近结束了哪几条」，而记录里还有命令正文、
 * 令牌、pid、日志路径。把裁剪放在这里，界面层不接触这些字段，也就不会不小心显示出来。
 *
 * 时间一律以毫秒返回（[BackgroundTaskLine.ageMs]），由界面本地化 —— 纯逻辑里不写中文，
 * 否则英文界面会冒出中文时间。
 */
internal data class BackgroundTasksView(
    val runningJobs: Int,
    val runningDaemons: Int,
    /** 记录里所有已结束的条数（不是 [recentFinished] 的长度）。 */
    val finished: Int,
    val running: List<DetachedTask>,
    val recentFinished: List<DetachedTask>,
) {
    val runningTotal: Int get() = runningJobs + runningDaemons

    /** 没有任何记录时卡片不出现，避免给空状态加一行常驻噪音。 */
    val isEmpty: Boolean get() = runningTotal == 0 && finished == 0
}

/** 展开后最多列几条运行中；再多用一行 `+N` 收尾。 */
internal const val BACKGROUND_TASKS_MAX_RUNNING_ROWS = 6

/** 最近结束只列几条；完整历史在 `terminal_job` / `terminal_daemon` 的 list 里。 */
internal const val BACKGROUND_TASKS_MAX_FINISHED_ROWS = 3

internal fun summarizeBackgroundTasks(records: List<DetachedTask>): BackgroundTasksView {
    val running = records.filter { it.completedAt == null }.sortedBy { it.startedAt }
    val finished = records.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    return BackgroundTasksView(
        runningJobs = running.count { it.commandTask },
        runningDaemons = running.count { !it.commandTask },
        finished = finished.size,
        running = running.take(BACKGROUND_TASKS_MAX_RUNNING_ROWS),
        recentFinished = finished.take(BACKGROUND_TASKS_MAX_FINISHED_ROWS),
    )
}

/**
 * 一条任务的显示素材。
 *
 * [status] 是 [DetachedTask.commandStatus] 的原值，界面自己映射到已有文案键
 * （`daemon_status_*` 一套三语都有），不在这里翻译。
 */
internal data class BackgroundTaskLine(
    val id: String,
    val kind: String,
    val status: String,
    val running: Boolean,
    val ageMs: Long,
)

internal fun backgroundTaskLine(task: DetachedTask, now: Long): BackgroundTaskLine {
    val at = task.completedAt ?: task.startedAt
    return BackgroundTaskLine(
        id = task.id,
        kind = if (task.commandTask) "job" else "daemon",
        status = task.commandStatus,
        running = task.completedAt == null,
        ageMs = (now - at).coerceAtLeast(0),
    )
}
