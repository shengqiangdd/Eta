package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/** App 持有后台命令；Agent run 只提交和查询，结束回复不会释放任务。 */
internal class BackgroundTerminalJobs(
    private val supervisor: DetachedTaskSupervisor,
    private val logger: AgentLogger,
    private val acquireLease: (() -> Unit) -> Boolean = { true },
    private val releaseLease: () -> Unit = {},
    private val onCompleted: (DetachedTask) -> Unit = {},
    private val completionNotificationsEnabled: () -> Boolean = { true },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-background-commands").apply { isDaemon = true }
    }
    private val waitLock = ReentrantLock()
    private val changed = waitLock.newCondition()
    private var monitor: ScheduledFuture<*>? = null
    private var leased = false
    private var closed = false

    @Synchronized
    fun start(
        launcher: DetachedTaskSupervisor,
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        description: String,
        timeoutMs: Long,
        /**
         * 环境变量注入片段（只有一次性脚本的路径，不含值）。
         *
         * 单独传入而不是拼进 [command]：任务列表与通知里展示的是用户写的命令，
         * 不应该出现一串临时文件路径。
         */
        envPrefix: String? = null,
        /** 派发这次任务的会话作用域键；null 表示没有归属（语音一次性入口 / 外部直调）。 */
        sessionScope: String? = null,
    ): String {
        if (closed) return failure("BACKGROUND_CLOSED", "后台命令宿主已关闭")
        if (!lease()) return failure("BACKGROUND_START_NOT_ALLOWED", "系统未允许启动后台执行，请返回 Eta 后重试")
        return when (val result = launcher.start(command, cwd, identity, environment,
            commandTask = true, description = description, timeoutMs = timeoutMs.coerceAtLeast(0), envPrefix = envPrefix,
            sessionScope = sessionScope)) {
            is DaemonStartResult.Failed -> {
                if (monitor == null) release()
                failure(result.code, result.message)
            }
            is DaemonStartResult.Started -> {
                monitor()
                taskJson(result.task).put("action", "start").toString()
            }
        }
    }

    /**
     * 列出后台命令。
     *
     * [scopeId] 非空时只回该会话自己派的任务，并附带 hidden_tasks 告知还有多少属于别的会话。
     * 为什么不直接全隐：旧行为是所有会话看到同一份全局列表 —— 别人派的活出现在你的面板里，
     * 你会以为那是自己的（选错 id、误读日志、甚至去 stop 别人的任务）。
     * 但也不能一概拒绝让你看：排查问题确实需要全局视角，所以留一个明确的出口（includeOther）。
     */
    fun list(activeOnly: Boolean, scopeId: String? = null, includeOther: Boolean = false): String {
        refresh()
        val visible = supervisor.list().filter { it.task.commandTask && (!activeOnly || it.task.completedAt == null) }
        if (scopeId.isNullOrBlank() || includeOther) {
            return base("list").put("tasks", JSONArray(visible.map { taskJson(it.task) })).toString()
        }
        // 先算「本会话真正拥有的」和「需要挡掉的」，不要先过滤再统计 —— 那样 foreign 恒为 0。
        val foreign = visible.count {
            !BackgroundTaskOwnership.visible(
                taskScope = it.task.sessionScope,
                viewerScope = scopeId,
                includeOther = includeOther,
            )
        }
        val mine = visible.filter {
            BackgroundTaskOwnership.visible(
                taskScope = it.task.sessionScope,
                viewerScope = scopeId,
                includeOther = includeOther,
            )
        }
        return base("list")
            .put("tasks", JSONArray(mine.map { taskJson(it.task) }))
            .apply {
                if (foreign > 0) {
                    put("hidden_tasks", foreign)
                    put(
                        "hidden_note",
                        "另有 $foreign 个后台任务属于其他会话，默认不列出；" +
                            "确实需要看全部时传 include_other=true",
                    )
                }
            }
            .toString()
    }

    /**
     * 探活并修正记录（含守护任务）。
     *
     * [list] 只把 `commandTask` 回给调用方，但底层 `supervisor.list()` 巡检的是**所有**未完成任务，
     * 会给确认死亡的那些补 `completed_at`。守护任务没有完成线程，只能靠这个入口发现它已经退出。
     */
    fun probe(): Int {
        // 返回记录条数，只为让调用方能打一行「确实巡检了」的诊断日志（不参与判定）。
        return supervisor.list().size
    }

    /**
     * 这个任务能不能被 [scopeId] 这个会话操作。
     *
     * 规则：
     *  - 没传 scopeId（语音一次性入口、外部直调）→ 不做限制，保持旧行为；
     *  - 任务没有归属（旧记录）→ 允许，否则升级后旧任务会突然无法 stop；
     *  - 有归属 → 必须同会话。
     *
     * 为什么不只看 list：光把别人任务藏起来不够 —— 只要知道 id（之前 listing 过、
     * 或从日志里看到），read / stop 照样能操作到别的会话的活。
     */
    private fun ownedBy(task: DetachedTask, scopeId: String?): Boolean =
        BackgroundTaskOwnership.visible(
            taskScope = task.sessionScope,
            viewerScope = scopeId,
            includeOther = false,
        )

    private fun foreignTask(id: String): String = failure(
        "JOB_NOT_FOUND",
        "未找到后台命令 $id（它可能属于其他会话）。确实需要看全部时用 terminal_job list 传 include_other=true",
    )

    fun read(id: String, maxBytes: Int = 16_000, scopeId: String? = null): String {
        refresh()
        val task = supervisor.findTask(id)?.takeIf { it.commandTask && ownedBy(it, scopeId) }
            ?: return foreignTask(id)
        val logs = supervisor.readLogs(id, maxBytes)
        val result = taskJson(task).put("action", "read")
        if (!logs.ok) return result.put("ok", false).put("code", logs.code).put("message", logs.message).toString()
        return result.put("output", logs.text).put("output_truncated", logs.truncated).toString()
    }

    fun stop(id: String, scopeId: String? = null): String {
        val task = supervisor.findTask(id)?.takeIf { it.commandTask && ownedBy(it, scopeId) }
            ?: return foreignTask(id)
        if (!supervisor.stopCommand(task.id)) return failure("JOB_STOP_FAILED", "无法停止后台命令，请检查当前身份授权")
        refresh()
        return taskJson(supervisor.findTask(id) ?: task).put("action", "stop").toString()
    }

    fun waitFor(
        id: String,
        timeoutMs: Long,
        scopeId: String? = null,
        isCancelled: () -> Boolean,
    ): String {
        resume()
        if (supervisor.findTask(id)?.let { it.commandTask && ownedBy(it, scopeId) } != true) {
            return foreignTask(id)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(0, 60_000))
        waitLock.withLock {
            while (supervisor.findTask(id)?.completedAt == null) {
                if (isCancelled()) return failure("CANCELLED", "已取消等待；后台命令仍保留，可单独停止")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                changed.awaitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)))
            }
        }
        val task = supervisor.findTask(id) ?: return failure("JOB_NOT_FOUND", "后台命令已清理")
        return taskJson(task).put("action", "wait").put("wait_timed_out", task.completedAt == null).toString()
    }

    /** 重新打开 App 后从同一份任务记录恢复观察，不重新执行命令。 */
    @Synchronized
    fun resume() {
        if (closed) return
        refresh()
        if (supervisor.list().any { it.task.commandTask && it.task.completedAt == null } && lease()) monitor()
    }

    @Synchronized
    private fun refresh() {
        if (closed) return
        try {
            var tasks = supervisor.list().filter { it.task.commandTask }
            val now = System.currentTimeMillis()
            val expired = tasks.filter { it.running && it.task.timeoutMs > 0 && now - it.task.startedAt >= it.task.timeoutMs }
            expired.forEach { supervisor.stopCommand(it.task.id, "timed_out") }
            if (expired.isNotEmpty()) tasks = supervisor.list().filter { it.task.commandTask }
            tasks.filter { it.task.completedAt != null && !it.task.completionNotified }.forEach { status ->
                try {
                    onCompleted(status.task)
                    supervisor.markCompletionNotified(status.task.id)
                } catch (failure: Exception) {
                    logger.warn("Background command notification failed: type=${failure.javaClass.simpleName}")
                }
            }
            waitLock.withLock { changed.signalAll() }
            if (tasks.none { it.running }) {
                monitor?.cancel(false)
                monitor = null
                release()
            }
        } catch (failure: Exception) {
            logger.warn("Background command refresh failed: type=${failure.javaClass.simpleName}")
        }
    }

    private fun monitor() {
        if (monitor == null) monitor = executor.scheduleWithFixedDelay(::refresh, 0, 1, TimeUnit.SECONDS)
    }

    private fun lease(): Boolean {
        if (!leased) leased = acquireLease { stopAll() }
        return leased
    }

    private fun release() {
        if (leased) {
            leased = false
            releaseLease()
        }
    }

    @Synchronized
    fun stopAll() {
        supervisor.list().filter { it.task.commandTask && it.running }.forEach { supervisor.stopCommand(it.task.id) }
        refresh()
    }

    /**
     * 清除全部已结束的任务记录（进行中的一条不动），给界面上的「清除已结束」用。
     *
     * 保护条件在 [DetachedTaskSupervisor.dismissFinished] 里，与自动淘汰共用同一套：
     * 没通知过结果的命令任务、刚结束不足一分钟的记录都会留下。
     *
     * @param scopeId 当前会话；只清它看得见的那些。
     * @param scopePending 当前会话还没拿到 id（新对话发第一条消息前），只能清无归属的旧记录。
     * @return 真正清掉的条数；0 表示没有可清的。
     */
    @Synchronized
    fun dismissFinished(scopeId: String? = null, scopePending: Boolean = false): Int {
        val removed = supervisor.dismissFinished(scopeId, scopePending)
        if (removed > 0) refresh()
        return removed
    }

    @Synchronized
    override fun close() {
        stopAll()
        closed = true
        monitor?.cancel(false)
        monitor = null
        executor.shutdownNow()
        release()
        waitLock.withLock { changed.signalAll() }
    }

    private fun taskJson(task: DetachedTask): JSONObject = base("status")
        .put("job_id", task.id).put("description", task.description)
        .put("environment", task.environment.wireName).put("identity", task.identity).put("cwd", task.cwd)
        .put("status", task.commandStatus).put("running", task.completedAt == null)
        .put("started_at", task.startedAt).put("completed_at", task.completedAt ?: JSONObject.NULL)
        .put("exit_code", task.exitCode ?: JSONObject.NULL).put("end_reason", task.endReason ?: JSONObject.NULL)
        // output_path 是**宿主物理路径**（hostDaemonPath 换出来的），所以它总是 android 侧；
        // 但 Linux 任务的 guest 路径不同（/workspace/daemon/x.log），模型拿宿主路径去 `cat`
        // 就会落空。两个都给，并明说哪个能在哪侧用。
        .put("output_path", supervisor.hostDaemonPath(task, task.logPath))
        .put("output_environment", "android")
        .put("guest_output_path", task.logPath)
        .put("guest_output_environment", task.environment.wireName)
        .put("timeout_ms", task.timeoutMs)
        .put("output_mode", task.outputMode.ifBlank { "file" })
        .put("completion_notifications_enabled", completionNotificationsEnabled())

    private fun base(action: String) = JSONObject().put("ok", true).put("tool", "terminal_job").put("action", action)
    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("tool", "terminal_job")
        .put("code", code).put("message", message).toString()
}
