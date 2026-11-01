package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/** 一条守护任务记录。pid + token 用于跨 App 重启后的认领，以及停止前防止 PID 复用误杀。 */
internal data class DetachedTask(
    val id: String,
    val pid: Long,
    val token: String,
    val command: String,
    val cwd: String,
    val identity: String,
    val environment: TerminalEnvironment,
    val logPath: String,
    val startedAt: Long,
    val backend: LinuxExecutionBackend = LinuxExecutionBackend.CHROOT,
    val hostWorkspace: String? = null,
    val commandTask: Boolean = false,
    val description: String = "",
    val timeoutMs: Long = 0,
    val completedAt: Long? = null,
    val exitCode: Int? = null,
    val endReason: String? = null,
    val completionNotified: Boolean = false,
    val isolatedGroup: Boolean? = null,
    /** "pty" 表示日志由 PTY 捕获（管道下游按行刷新），空表示直接重定向。 */
    val outputMode: String = "",
    /**
     * 派发这条任务的会话作用域键（与台账 / 目标 / 待办同一个 `conversationId ?: runId`）。
     *
     * 为什么记录里需要它：任务结束要不要「自动接着跑」，取决于这条任务**属于哪个会话**。
     * 记录原先没有归属，A 会话的任务结束时 B 会话的卡片也会把它当成自己的候选 ——
     * 用户没看着的会话就这样花掉了别的会话的额度。
     *
     * null 表示「没有归属」（旧记录、语音一次性入口、外部直接调 supervisor 的路径）：
     * 这类任务不会自动续跑，手动「继续」按钮照常可用。
     */
    val sessionScope: String? = null,
)

internal data class DetachedTaskStatus(
    val task: DetachedTask,
    val running: Boolean,
)

internal sealed interface DaemonStartResult {
    data class Started(val task: DetachedTask) : DaemonStartResult
    data class Failed(val code: String, val message: String) : DaemonStartResult
}

internal data class DaemonLogsResult(
    val ok: Boolean,
    val text: String = "",
    val truncated: Boolean = false,
    val code: String = "",
    val message: String = "",
)

internal fun DetachedTask.toRecordJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("pid", pid)
    .put("token", token)
    .put("command", command)
    .put("cwd", cwd)
    .put("identity", identity)
    .put("environment", environment.wireName)
    .put("log_path", logPath)
    .put("started_at", startedAt)
    .put("backend", backend.wireName)
    .put("host_workspace", hostWorkspace ?: JSONObject.NULL)
    .put("command_task", commandTask)
    .put("description", description)
    .put("timeout_ms", timeoutMs)
    .put("completed_at", completedAt ?: JSONObject.NULL)
    .put("exit_code", exitCode ?: JSONObject.NULL)
    .put("end_reason", endReason ?: JSONObject.NULL)
    .put("completion_notified", completionNotified)
    .put("isolated_group", isolatedGroup ?: JSONObject.NULL)
    .put("output_mode", outputMode)
    .put("session_scope", sessionScope ?: JSONObject.NULL)

internal fun JSONObject.toDetachedTask(): DetachedTask = DetachedTask(
    id = getString("id"),
    pid = getLong("pid"),
    token = getString("token"),
    command = getString("command"),
    cwd = getString("cwd"),
    identity = getString("identity"),
    environment = when (optString("environment")) {
        TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
        TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
        else -> TerminalEnvironment.ANDROID
    },
    logPath = getString("log_path"),
    startedAt = getLong("started_at"),
    backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == optString("backend") } ?: LinuxExecutionBackend.CHROOT,
    hostWorkspace = optString("host_workspace").takeIf { it.isNotBlank() && it != "null" },
    commandTask = optBoolean("command_task", false),
    description = optString("description"),
    timeoutMs = optLong("timeout_ms", 0),
    completedAt = if (isNull("completed_at")) null else getLong("completed_at"),
    exitCode = if (isNull("exit_code")) null else getInt("exit_code"),
    endReason = if (isNull("end_reason")) null else getString("end_reason"),
    completionNotified = optBoolean("completion_notified", false),
    isolatedGroup = if (isNull("isolated_group")) null else getBoolean("isolated_group"),
    outputMode = optString("output_mode"),
    // 旧记录没有这个键：缺失 / 空串 / 字面 null 一律按「没有归属」，不抛异常。
    sessionScope = optString("session_scope").takeIf { it.isNotBlank() && it != "null" },
)

internal val DetachedTask.commandStatus: String get() = when {
    completedAt == null -> "running"
    endReason != null -> endReason
    exitCode == 0 -> "completed"
    else -> "failed"
}

/**
 * 探活结论 → 这条记录该不该补「结束时间」。
 *
 * 只对**探活明确说死了**的记录返回 true：探测本身失败（起不了 shell、没权限）时调用方传
 * false，宁可继续显示「运行中」也不能把活着的进程记成结束——记录一旦写上 `completed_at`
 * 就只能看到日志、不能再认领。
 *
 * 抽成函数是为了能本机测：`list()` 本身要起 shell 探 `/proc/<pid>/environ`，单测跑不了。
 */
internal fun shouldMarkCompleted(task: DetachedTask, probeDead: Boolean): Boolean =
    probeDead && task.completedAt == null
