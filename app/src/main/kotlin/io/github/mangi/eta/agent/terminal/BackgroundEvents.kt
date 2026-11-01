package io.github.mangi.eta.agent.terminal

/**
 * 一条「后台任务已经结束」的事件。
 *
 * 只保留提示需要的字段：id、类型、结束时间、状态。为什么要单独造一个类型而不是直接拿
 * [DetachedTask]：事件一旦「告知过」就要记住，而记录本身会被 supervisor 清理；
 * 存整条记录既浪费也不稳，存这几个字段足够渲染。
 */
internal data class BackgroundEvent(
    val taskId: String,
    val commandTask: Boolean,
    /** 结束时间，取自记录的 `completedAt`。 */
    val at: Long,
    /** [DetachedTask.commandStatus] 的值（completed / failed / stopped / timed_out / interrupted …）。 */
    val status: String,
    val exitCode: Int?,
)

/**
 * 后台任务结束事件在系统提示里的那一段。
 *
 * 第五批（[BackgroundTasksSection]）回答「现在还有谁在跑」，这一段回答「跑完了哪些」。
 * 两者都在 run 开始时构建，区别是：任务段只读记录、不写盘；事件段要**记住已经告知过的水位线**，
 * 否则同一个结束事件会每轮重复出现。水位线由 `BackgroundEventStore` 保管。
 *
 * 刻意不做的事：
 * 1. **不判断成败之外的含义**：`status` 是记录里的收尾状态，不是模型对结果的判断；
 *    段落里必须写清「这里只是记录，不代表任务成功」。
 * 2. **不带命令正文与描述**：与任务段同一条理由 —— 记录里可能有用户手敲的命令。
 * 3. **不做触发**：本轮只把事件告诉模型，不自动发起新一轮（那涉及计费与循环保护，另开一轮）。
 */
internal object BackgroundEvents {

    /** 最多列几条；再多只说数量。 */
    const val MAX_LISTED = 6

    /**
     * 从记录里挑出「已结束且尚未告知」的事件，按结束时间升序。
     *
     * @param deliveredUntil 水位线：结束时间不晚于它的任务视为已告知；`null` 表示本会话从没告知过。
     */
    fun collect(
        records: List<DetachedTask>,
        deliveredUntil: Long?,
    ): List<BackgroundEvent> = records.mapNotNull { task ->
        val at = task.completedAt ?: return@mapNotNull null
        if (deliveredUntil != null && at <= deliveredUntil) return@mapNotNull null
        BackgroundEvent(
            taskId = task.id,
            commandTask = task.commandTask,
            at = at,
            status = task.commandStatus,
            exitCode = task.exitCode,
        )
    }.sortedBy { it.at }

    /** 渲染提示段；没有事件时返回空串（调用方据此不追加消息）。 */
    fun render(events: List<BackgroundEvent>, now: Long): String {
        if (events.isEmpty()) return ""
        val listed = events.take(MAX_LISTED)
        val overflow = events.size - listed.size
        return buildString {
            appendLine("<background_events>")
            appendLine("以下后台任务已经结束，还没有在当前会话里提过（共 ${events.size} 条）：")
            listed.forEach { event ->
                val kind = if (event.commandTask) "job" else "daemon"
                appendLine("- ${event.taskId}（$kind）：${statusText(event.status)} · ${BackgroundTasksSection.ageLabel(now - event.at)}前")
            }
            if (overflow > 0) appendLine("- 另有 $overflow 条未列出")
            append("要看输出或确认结果，用 terminal_job / terminal_daemon 的 logs；这里只是记录，不代表任务成功。")
            appendLine()
            append("</background_events>")
        }.trimEnd()
    }

    /** 把记录里的状态词翻成一句人话；不认识的词原样带上，不猜。 */
    fun statusText(status: String): String = when (status) {
        "completed" -> "已完成"
        "failed" -> "失败"
        "stopped" -> "已停止"
        "timed_out" -> "已超时"
        "interrupted" -> "已中断"
        "running" -> "仍在运行"
        else -> "已结束（$status）"
    }
}
