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
    /**
     * 属于**其他会话**、因而没在这里列出的条数。
     *
     * 面板与 `terminal_job list` 必须是同一口径：「面板执行中 2、工具查 0 条」那种矛盾
     * 比少看到两条更伤人 —— 用户会以为工具坏了。但也不能装作没有：标题上要说明还有几条，
     * 否则「我明明在别的会话里开着东西」会变成「它去哪了」。
     */
    val foreignTotal: Int = 0,
) {
    val runningTotal: Int get() = runningJobs + runningDaemons

    /**
     * 没有任何记录时卡片不出现，避免给空状态加一行常驻噪音。
     *
     * 别的会话的任务也算「有记录」：本会话刚点完「清除已结束」、而其他会话手里还有任务时，
     * 若这里只按本会话判空，整张卡会连「其他会话 N」一起消失 —— 用户很自然会怀疑
     * 刚才那一下把别人的任务也删了（真机实测就是这么消失的）。
     */
    val isEmpty: Boolean get() = runningTotal == 0 && finished == 0 && foreignTotal == 0
}

/** 展开后最多列几条运行中；再多用一行 `+N` 收尾。 */
internal const val BACKGROUND_TASKS_MAX_RUNNING_ROWS = 6

/** 最近结束只列几条；完整历史在 `terminal_job` / `terminal_daemon` 的 list 里。 */
internal const val BACKGROUND_TASKS_MAX_FINISHED_ROWS = 3

internal fun summarizeBackgroundTasks(
    records: List<DetachedTask>,
    viewerScope: String? = null,
): BackgroundTasksView {
    // 归属规则只认 BackgroundTaskOwnership 一处：面板、list、read/stop 各写一套必然漂移，
    // 而漂移的表现是「界面看得见却操作不了」这种最难解释的样子。
    val mine = records.filter {
        BackgroundTaskOwnership.visible(
            taskScope = it.sessionScope,
            viewerScope = viewerScope,
            includeOther = false,
        )
    }
    val running = mine.filter { it.completedAt == null }.sortedBy { it.startedAt }
    val finished = mine.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    return BackgroundTasksView(
        runningJobs = running.count { it.commandTask },
        runningDaemons = running.count { !it.commandTask },
        finished = finished.size,
        running = running.take(BACKGROUND_TASKS_MAX_RUNNING_ROWS),
        recentFinished = finished.take(BACKGROUND_TASKS_MAX_FINISHED_ROWS),
        foreignTotal = records.size - mine.size,
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
