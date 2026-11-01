package io.github.mangi.eta.agent.terminal

/**
 * 后台任务（`terminal_job` / `terminal_daemon`）在系统提示里的那一小段。
 *
 * 为什么需要它：这两类任务**活过一轮对话**，但模型只会记得自己最近一次工具结果。隔几轮回来，
 * 它不知道「还有个 daemon 在跑」，于是要么重复启动、要么让用户去终端页自己看。
 * 子代理台账（§32.51）解决的是同一类问题，这里解决「进程级的长任务」。
 *
 * 三条刻意的取舍：
 * 1. **只读**：数据来自 [DetachedTaskSupervisor.readRecords]（不探活、不认领、不补结束时间、不清理）。
 *    系统提示每轮都构建，不能为此起 shell 或写盘 —— `list()` 会做这些，所以不能用它。
 * 2. **不带命令正文**：只列 id 与数量。记录里的命令可能是用户在终端里手敲的（含路径、令牌），
 *    系统提示会被发到模型服务商；要看内容就让模型显式调 `terminal_job` / `terminal_daemon` 的 list。
 * 3. **写明未探活**：进程被系统杀掉时记录不会自己改写，所以「未结束」只是记账状态，
 *    段落里必须说清楚，否则模型会把一条死任务当成还在跑。
 */
internal object BackgroundTasksSection {

    /** 最多列几个 id；再多就只说数量，避免把提示撑长。 */
    const val MAX_LISTED = 6

    /** 记录里「还没标结束」的任务，按开始时间升序（最久没管的排最前）。 */
    fun running(tasks: List<DetachedTask>): List<DetachedTask> =
        tasks.filter { it.completedAt == null }.sortedBy { it.startedAt }

    /**
     * 渲染提示段；没有未结束的任务时返回空串（调用方据此不追加消息）。
     *
     * @param now 现在是几点，用于算「最早一条始于多久前」；单测传入固定值。
     */
    fun render(tasks: List<DetachedTask>, now: Long): String {
        val running = running(tasks)
        if (running.isEmpty()) return ""
        val jobs = running.count { it.commandTask }
        val daemons = running.size - jobs
        val counts = buildList {
            if (jobs > 0) add("job $jobs")
            if (daemons > 0) add("daemon $daemons")
        }.joinToString(" · ")
        val listed = running.take(MAX_LISTED).joinToString("、") { it.id }
        val overflow = running.size - minOf(running.size, MAX_LISTED)
        val ids = if (overflow > 0) "$listed（另有 $overflow 条未列出）" else listed
        val oldest = running.first().startedAt
        return buildString {
            appendLine("<background_tasks>")
            append("本机还有 ${running.size} 条未结束的后台任务记录（$counts）：$ids")
            append("（最早一条始于 ${ageLabel(now - oldest)}前）。")
            appendLine()
            append("这里只是任务记录，没有做存活探测；要读日志、确认状态或停止，")
            append("用 terminal_job / terminal_daemon 的 list。")
            appendLine()
            append("</background_tasks>")
        }.trimEnd()
    }

    /** 年龄只报到「分钟 / 小时 / 天」，不需要精确到秒。 */
    fun ageLabel(elapsedMs: Long): String {
        val ms = elapsedMs.coerceAtLeast(0)
        return when {
            ms < 60_000L -> "不到 1 分钟"
            ms < 3_600_000L -> "${ms / 60_000L} 分钟"
            ms < 86_400_000L -> "${ms / 3_600_000L} 小时"
            else -> "${ms / 86_400_000L} 天"
        }
    }
}
