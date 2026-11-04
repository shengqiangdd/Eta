package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.agent.tool.requiredText
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger,
    linuxRootfsPath: String? = null,
    linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val detachedSupervisor: DetachedTaskSupervisor? = null,
    private val backgroundJobs: BackgroundTerminalJobs? = null,
    /**
     * 本工具实例所属的会话作用域键，写进新建任务的记录。
     *
     * 空串表示没有归属（语音 / 一次性入口）：这类任务不会自动续跑，
     * 但手动「继续」仍然可用。
     */
    private val backgroundScopeId: String = "",
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    selectedLinuxEnvironmentProvider: () -> TerminalEnvironment = {
        TerminalEnvironment.ALPINE
    },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    /**
     * 终端环境变量的注入片段（参考 dsh-env.conf）；每次执行命令前注入，文件改动立即生效。
     *
     * 参数是本次命令的环境：Android 写 App 私有目录，Linux 写 rootfs 内的 `/tmp`。
     * 实现方只返回「source 一个 0600 脚本」的片段，变量值绝不进命令行（`ps` 可见）。
     */
    private val environmentScriptProvider: (TerminalEnvironment) -> String? = { null },
    /** 读取门禁与文件检查点；为空时（单测/旧入口）保持原有行为。 */
    private val fileAccessLedger: FileAccessLedger? = null,
    private val fileBackupStore: FileBackupStore? = null,
    executionContext: TerminalExecutionContext? = null,
) : AutoCloseable {
    private companion object {
        const val USER_STORAGE = "/storage/emulated/0"
        const val MAX_TIMEOUT_SECONDS = 180
        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024
    }

    private val sessions = linkedMapOf<String, TerminalSession>()
    private val cleanupStarted = AtomicBoolean(false)
    val executionContext: TerminalExecutionContext = executionContext ?: run {
        val linuxEnvironment = selectedLinuxEnvironmentProvider()
        TerminalExecutionContext(
            linuxEnvironment = linuxEnvironment,
            linuxRootfsPath = if (linuxRootfsPathProvider != null) {
                linuxRootfsPathProvider(linuxEnvironment)
            } else {
                linuxRootfsPath
            },
        )
    }

    fun executeTool(name: String, args: JSONObject): String = when (name) {
        "terminal" -> terminal(args)
        "terminal_job" -> terminalJob(args)
        "terminal_daemon" -> terminalDaemon(args)
        else -> errorJson("UNKNOWN_TOOL", "未知终端工具：$name")
    }

    private fun terminal(args: JSONObject): String = when (args.optString("action", "exec").lowercase()) {
        "open" -> openSession(
            identity = args.optString("identity"),
            cwd = args.optString("cwd").ifBlank { null },
            environment = args.optString("environment", "android"),
        )
        "exec" -> execInTerminal(
            command = args.requiredText("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutMs = args.optInt("timeout_ms", 30_000),
            identity = args.optString("identity"),
            environment = args.optString("environment", "android"),
            mergeStderr = args.optBoolean("merge_stderr", false),
            sessionId = args.optString("session_id").ifBlank { null },
        )
        "close" -> closeTerminal(args.optString("session_id"))
        else -> errorJson("UNSUPPORTED_TERMINAL_ACTION", "terminal action 仅支持 exec/open/close")
    }

    private fun terminalJob(args: JSONObject): String {
        val jobs = backgroundJobs ?: return errorJson("BACKGROUND_UNAVAILABLE", "后台命令宿主不可用")
        return when (args.optString("action").lowercase()) {
            "start" -> {
                if (processSupervisor.isClosing) return errorJson("TERMINAL_CLOSED", "当前运行已结束，不能再启动任务")
                val launcher = detachedSupervisor ?: return errorJson("BACKGROUND_UNAVAILABLE", "后台进程宿主不可用")
                val environment = normalizeEnvironment(args.optString("environment", "android"))
                val identity = normalizeIdentity(args.optString("identity").ifBlank { defaultIdentity(environment) })
                environmentPreflight(identity, environment)?.let { return it }
                val cwd = normalizeCwd(args.optString("cwd").ifBlank { null }, environment, identity)
                val command = args.requiredText("command")
                if (command.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
                jobs.start(
                    launcher = launcher,
                    command = command,
                    cwd = cwd,
                    identity = identity,
                    environment = environment,
                    description = args.optString("description", "后台命令"),
                    timeoutMs = args.optLong("timeout_ms", 600_000),
                    // 后台命令也要拿到 env.conf：否则「设置里配了 token，跑后台任务就没有」。
                    envPrefix = envPrefixFor(environment, surface = "job"),
                    sessionScope = backgroundScopeId.takeIf { it.isNotBlank() },
                )
            }
            "list" -> jobs.list(
                activeOnly = args.optBoolean("active_only", true),
                // 默认只看本会话派的任务：旧行为是全局共享，别人派的活会出现在你的面板里，
                // 你会当成自己的（选错 id、误读日志、甚至去 stop）。
                scopeId = backgroundScopeId.takeIf { it.isNotBlank() },
                includeOther = args.optBoolean("include_other", false),
            )
            "read" -> jobs.read(
                args.requiredText("job_id"),
                args.optInt("max_bytes", 16_000),
                scopeId = backgroundScopeId.takeIf { it.isNotBlank() },
            )
            "wait" -> jobs.waitFor(
                args.requiredText("job_id"),
                args.optLong("wait_ms", 30_000),
                isCancelled = { processSupervisor.isClosing },
                scopeId = backgroundScopeId.takeIf { it.isNotBlank() },
            )
            "stop" -> jobs.stop(
                args.requiredText("job_id"),
                scopeId = backgroundScopeId.takeIf { it.isNotBlank() },
            )
            else -> errorJson("UNSUPPORTED_TERMINAL_ACTION", "terminal_job action 仅支持 start/list/read/wait/stop")
        }
    }

    private fun terminalDaemon(args: JSONObject): String = when (args.optString("action").lowercase()) {
        "start" -> daemonStart(
            command = args.requiredText("command"),
            cwd = args.optString("cwd").ifBlank { null },
            identity = args.optString("identity"),
            environment = args.optString("environment", "android"),
        )
        "list" -> daemonList()
        "logs" -> daemonLogs(args.optString("task_id"))
        "stop" -> daemonStop(args.optString("task_id"))
        else -> errorJson("UNSUPPORTED_TERMINAL_ACTION", "terminal_daemon action 仅支持 start/list/logs/stop")
    }

    private fun openSession(identity: String, cwd: String?, environment: String): String {
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        val sessionRootfs = rootfsPathFor(normalizedEnvironment)
        environmentPreflight(normalizedIdentity, normalizedEnvironment, sessionRootfs)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity, normalizedEnvironment, sessionRootfs)
            ?: return errorJson(
                "PROCESS_START_FAILED",
                "无法启动 ${normalizedEnvironment.wireName}/$normalizedIdentity terminal session",
            )
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
            environment = normalizedEnvironment,
            rootfsPath = sessionRootfs,
            cwd = safeCwd,
            createdAt = System.currentTimeMillis(),
            process = process,
            stdout = stdout,
            stderr = stderr
        )
        session.stdoutThread = thread(name = "agent-terminal-session-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input) }
        }
        session.stderrThread = thread(name = "agent-terminal-session-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        session.waiterThread = thread(name = "agent-terminal-session-waiter-$id", isDaemon = true) {
            runCatching { process.waitFor() }
            processSupervisor.retireExitedProcess(process)
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(sessions) { sessions[id] = session }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }

        val mkdirDefault = if (safeCwd == androidWorkspace()) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val probeMarker = TerminalExecutionProbe.marker()
        val setup = "${mkdirDefault}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && {\n${TerminalExecutionProbe.script(probeMarker)}\n}"
        val setupResult = runSessionCommand(session, setup, timeoutMs = 5_000)
        if (setupResult.exitCode != 0 || setupResult.timedOut) {
            closeSession(id)
            return errorJson("SESSION_OPEN_FAILED", setupResult.stderr.ifBlank { "exit=${setupResult.exitCode}" })
        }
        session.cwd = setupResult.cwd ?: safeCwd
        session.runtime = TerminalExecutionProbe.extract(setupResult.stdout, probeMarker, normalizedIdentity, normalizedEnvironment).runtime
        session.stdout.clear()
        session.stderr.clear()
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open")
            .put("session_id", id)
            .put("identity", normalizedIdentity)
            .put("environment", normalizedEnvironment.wireName)
            .put("cwd", session.cwd)
            .put("runtime", session.runtime ?: JSONObject.NULL)
            .put("status", "open")
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: String,
        mergeStderr: Boolean,
        sessionId: String?,
    ): String {
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        // 会话已经有固定环境（open 时确定），不改；新建命令按路径自动纠正一次。
        // 模型经常省掉 environment 却给 /workspace 路径，以前只能拿一句 "can't cd to" 再重试一轮。
        val resolution = if (session == null) {
            EnvironmentPathHeuristic.resolve(
                requested = environment,
                paths = EnvironmentPathHeuristic.pathsInCommand(command),
            )
        } else {
            null
        }
        val effectiveEnvironment = session?.environment
            ?: normalizeEnvironment(resolution?.environment ?: environment)
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { defaultIdentity(effectiveEnvironment) })
        environmentPreflight(effectiveIdentity, effectiveEnvironment, session?.rootfsPath ?: rootfsPathFor(effectiveEnvironment))?.let { return it }
        if (session != null) {
            return execInSession(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
                mergeStderr = mergeStderr
            )
        }
        return appendEnvironmentNote(
            runCommand(
                command = command,
                cwd = cwd,
                timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
                    .coerceIn(1, MAX_TIMEOUT_SECONDS),
                identity = effectiveIdentity,
                environment = effectiveEnvironment,
                mergeStderr = mergeStderr,
            ),
            resolution?.note,
        )
    }

    private fun daemonStart(
        command: String,
        cwd: String?,
        identity: String,
        environment: String,
    ): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        environmentPreflight(normalizedIdentity, normalizedEnvironment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        return when (
            val result = supervisor.start(
                command = trimmed,
                cwd = safeCwd,
                identity = normalizedIdentity,
                environment = normalizedEnvironment,
                // 常驻服务与普通命令用同一份 env.conf；值同样只走一次性脚本。
                envPrefix = envPrefixFor(normalizedEnvironment, surface = "daemon"),
                sessionScope = backgroundScopeId.takeIf { it.isNotBlank() },
            )
        ) {
            is DaemonStartResult.Started -> JSONObject()
                .put("ok", true)
                .put("tool", "terminal_daemon")
                .put("action", "start")
                .put("task_id", result.task.id)
                .put("pid", result.task.pid)
                .put("identity", result.task.identity)
                .put("environment", result.task.environment.wireName)
                .put("cwd", result.task.cwd)
                .toString()
            is DaemonStartResult.Failed -> errorJson(result.code, result.message)
        }
    }

    private fun daemonList(): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val statuses = supervisor.list().filterNot { it.task.commandTask }
        val tasks = JSONArray()
        statuses.forEach { status -> tasks.put(daemonTaskJson(status)) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal_daemon")
            .put("action", "list")
            .put("task_count", statuses.size)
            .put("running_count", statuses.count { it.running })
            .put("tasks", tasks)
            .toString()
    }

    private fun daemonLogs(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val result = supervisor.readLogs(taskId)
        if (!result.ok) {
            return errorJson(result.code.ifBlank { "LOGS_UNAVAILABLE" }, result.message)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal_daemon")
            .put("action", "logs")
            .put("task_id", taskId)
            .put("log", result.text.truncateForJson())
            .put("log_truncated", result.truncated || result.text.length > MAX_OUTPUT_CHARS)
            .toString()
    }

    private fun daemonStop(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val task = supervisor.findTask(taskId) ?: return errorJson("TASK_NOT_FOUND", "未找到守护任务：$taskId")
        if (task.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        if (!supervisor.stop(taskId)) {
            return errorJson("DAEMON_STOP_FAILED", "守护任务停止失败，请重试")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal_daemon")
            .put("action", "stop")
            .put("task_id", taskId)
            .toString()
    }

    private fun closeTerminal(sessionId: String): String {
        if (sessionId.isBlank()) return errorJson("INVALID_ARGUMENT", "关闭会话需要 session_id")
        if (!closeSession(sessionId)) return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$sessionId")
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "close")
            .put("session_id", sessionId)
            .put("closed_session", true)
            .toString()
    }

    override fun close() {
        closeAll()
    }

    /** 取消热路径只封闭新进程接纳；进程树终止和 reader/waiter 回收在后台完成。 */
    fun interruptAll() {
        beginClosing()
        if (cleanupStarted.compareAndSet(false, true)) {
            thread(name = "agent-terminal-cleanup", isDaemon = true) {
                closeAllInternal()
            }
        }
    }

    fun closeAll() {
        beginClosing()
        cleanupStarted.set(true)
        closeAllInternal()
    }

    private fun beginClosing() {
        processSupervisor.beginClosing()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.closed = true }
        }
    }

    private fun closeAllInternal() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val remainingProcesses = processSupervisor.takeRemainingProcesses()
        remainingProcesses.forEach { process ->
            processSupervisor.terminateAndReap(process)
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        processSupervisor.terminateAndReap(session.process)
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        runCatching { session.waiterThread.join(500) }
        processSupervisor.unregisterProcess(session.process)
        return true
    }

    private fun execInSession(
        session: TerminalSession,
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        val result = runSessionCommand(session, trimmed, timeout)
        val outcome = when {
            result.timedOut -> "timed_out"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome session=true " +
                "identity=${session.identity} environment=${session.environment.wireName} " +
                "timeoutMs=$timeout commandChars=${trimmed.length} " +
                "exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        if (result.cwd != null) session.cwd = result.cwd
        if (result.timedOut) {
            closeSession(session.id)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.stdout + "\n[stderr]\n" + result.stderr
        } else {
            result.stdout
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("session_id", session.id)
            .put("identity", session.identity)
            .put("environment", session.environment.wireName)
            .put("cwd", session.cwd)
            .put("runtime", session.runtime ?: JSONObject.NULL)
            .put("status", when { result.timedOut -> "timed_out"; session.closed -> "cancelled"; result.exitCode == 0 -> "exited"; else -> "failed" })
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put(
                "timeout_note",
                if (!result.timedOut) {
                    JSONObject.NULL
                } else {
                    // 超时只关闭本次会话，不保证杀掉命令自己 setsid/nohup 派生的子进程。
                    // 不写清楚时，模型会把“已超时”当成“已停止”，随后看到进程还在跑。
                    "命令超过 ${timeout}ms 未结束，本次终端会话已关闭；" +
                        "若命令自己 setsid/nohup 派生了子进程，它们可能仍在后台运行，请用 ps 核查"
                },
            )
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > MAX_OUTPUT_CHARS)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > MAX_OUTPUT_CHARS)
            .put("output_truncated", rawStdout.length > MAX_OUTPUT_CHARS || (!mergeStderr && result.stderr.length > MAX_OUTPUT_CHARS))
            .put("session_closed", result.timedOut || session.closed)
            .put(
                "environment_note",
                EnvironmentMismatchHint.note(session.environment.wireName, trimmed, result.exitCode, result.stderr)
                    ?: JSONObject.NULL,
            )
            .toString()
    }

    private fun runSessionCommand(
        session: TerminalSession,
        command: String,
        timeoutMs: Int
    ): SessionCommandResult {
        synchronized(session.lock) {
            if (session.closed || !session.process.isAlive) {
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "terminal session 已关闭",
                    cwd = session.cwd,
                    timedOut = false
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            val stdoutStart = session.stdout.text().length
            val stderrStart = session.stderr.text().length
            val commandBlock = buildString {
                // 环境变量每次命令前注入：用户改 env.conf 后无需重建会话。
                val envPrefix = envPrefixFor(session.environment, surface = "session")
                if (envPrefix != null) {
                    append(envPrefix)
                    append('\n')
                }
                append(command)
                append('\n')
                append(SessionStatusProtocol.statusCommand(marker))
                append('\n')
            }
            runCatching {
                session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }.getOrElse {
                session.closed = true
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                    stderr = it.message ?: it.javaClass.simpleName,
                    cwd = session.cwd,
                    timedOut = false
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val stdoutDelta = session.stdout.text().drop(stdoutStart)
                if (session.closed || !session.process.isAlive) {
                    return SessionCommandResult(
                        exitCode = -1,
                        stdout = stdoutDelta.trimEnd(),
                        stderr = session.stderr.text().drop(stderrStart).ifBlank { "terminal session 已关闭" }.trimEnd(),
                        cwd = session.cwd,
                        timedOut = false
                    )
                }
                val status = stdoutDelta.lineSequence()
                    .firstOrNull { SessionStatusProtocol.isStatusLine(it, marker) }
                    ?.let { SessionStatusProtocol.parseStatusLine(it, marker) }
                if (status != null) {
                    val exitCode = status.exitCode
                    val cwd = status.cwd ?: session.cwd
                    val cleanedStdout = stdoutDelta
                        .lineSequence()
                        .filterNot { SessionStatusProtocol.isStatusLine(it, marker) }
                        .joinToString("\n")
                        .trimEnd()
                    val stderrDelta = session.stderr.text().drop(stderrStart).trimEnd()
                    session.stdout.clear()
                    session.stderr.clear()
                    return SessionCommandResult(
                        exitCode = exitCode,
                        stdout = cleanedStdout,
                        stderr = stderrDelta,
                        cwd = cwd,
                        timedOut = false
                    )
                }
                Thread.sleep(50)
            }

            session.closed = true
            processSupervisor.terminateProcessTree(session.process)
            return SessionCommandResult(
                exitCode = -2,
                stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                stderr = session.stderr.text().drop(stderrStart).ifBlank { "命令执行超时" }.trimEnd(),
                cwd = session.cwd,
                timedOut = true
            )
        }
    }

    private fun runCommand(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == androidWorkspace()) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val probeMarker = TerminalExecutionProbe.marker()
        // 单次命令也要注入 env.conf（与常驻会话保持一致）。
        // 注意：只能是「source 私有脚本」这种不含值的片段；拼 `export KEY='值'` 会让值出现在
        // `su -c` / `sh -c` 的 argv 里，任何进程 `ps` 都看得到（token 泄露就出在这）。
        val envPart = envPrefixFor(environment, surface = "exec") ?: ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $envPart{\n${TerminalExecutionProbe.script(probeMarker)}\n$trimmed\n}"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeout.toLong(),
            environment = environment,
        )
        val outcome = when (result.exitCode) {
            0 -> "succeeded"
            -2 -> "timed_out"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome identity=$normalizedIdentity " +
                "environment=${environment.wireName} " +
                "timeoutSeconds=$timeout commandChars=${trimmed.length} exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        val observation = TerminalExecutionProbe.extract(result.output, probeMarker, normalizedIdentity, environment)
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            observation.text + "\n[stderr]\n" + result.stderr
        } else {
            observation.text
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("runtime", observation.runtime ?: JSONObject.NULL)
            .put("status", when { processSupervisor.isClosing -> "cancelled"; result.exitCode == -2 -> "timed_out"; result.exitCode == 0 -> "exited"; else -> "failed" })
            .put("exit_code", result.exitCode)
            .put("timed_out", result.exitCode == -2)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", result.outputTruncated || rawStdout.length > MAX_OUTPUT_CHARS || (mergeStderr && result.stderrTruncated))
            .put("stderr_truncated", !mergeStderr && (result.stderrTruncated || result.stderr.length > MAX_OUTPUT_CHARS))
            .put("output_truncated", result.outputTruncated || result.stderrTruncated || rawStdout.length > MAX_OUTPUT_CHARS || result.stderr.length > MAX_OUTPUT_CHARS)
            .put(
                "environment_note",
                EnvironmentMismatchHint.note(environment.wireName, trimmed, result.exitCode, result.stderr)
                    ?: JSONObject.NULL,
            )
            .toString()
    }

    fun fileTool(name: String, args: JSONObject): String {
        return try {
            // 先按路径纠正 environment，再建 backend：文件工具的 environment 是构造时确定的，
            // 建完再改就来不及了（这正是「路径看着对、却总报文件不存在」的来源）。
            val resolution = EnvironmentPathHeuristic.resolve(
                requested = args.optString("environment", "android"),
                paths = listOf(args.optString("path")) + filePatchPaths(args),
            )
            val environment = normalizeEnvironment(resolution.environment)
            val rootfsPath = rootfsPathFor(environment)
            val identity = args.optString("identity").ifBlank {
                defaultIdentity(environment)
            }.let(::normalizeIdentity)
            environmentPreflight(identity, environment, rootfsPath)?.let { return it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            fun checkActive() {
                if (processSupervisor.isClosing) throw FileToolException("CANCELLED", "文件操作已取消")
                if (identity == "root" && !rootAvailable()) throw FileToolException("ROOT_REQUIRED", "Root 授权已不可用")
                if (System.nanoTime() >= deadline) throw FileToolException("FILE_TIMEOUT", "文件操作达到时间预算，请缩小范围")
            }
            val cwd = normalizeCwd(args.optString("cwd").takeIf(String::isNotEmpty), environment, identity)
            val backend = if (environment == TerminalEnvironment.ANDROID && identity == "user") {
                LocalFileToolBackend(
                    workspace = File(cwd),
                    home = File(executionContext.userWorkspacePath),
                    isCancelled = { checkActive(); false },
                )
            } else {
                val mounts = sharedMountsFor(environment)
                ShellFileToolBackend(
                    environment = if (environment.isLinux) "linux" else "android",
                    identity = identity,
                    cwd = cwd,
                    home = if (environment.isLinux) "/root" else USER_STORAGE,
                    ensureActive = ::checkActive,
                ) { command, input, outputLimit ->
                    checkActive()
                    runOneShotShell(
                        processSupervisor = processSupervisor,
                        identity = identity,
                        command = command,
                        timeoutSeconds = ((deadline - System.nanoTime()) / 1_000_000_000L + 1).coerceIn(1, 20),
                        stdin = input,
                        environment = environment,
                        linuxRootfsPath = rootfsPath,
                        linuxSharedMounts = mounts,
                        maxOutputBytes = outputLimit,
                    )
                }
            }
            appendEnvironmentNote(
                FileToolDispatcher.execute(backend, name, args, fileAccessLedger, fileBackupStore),
                resolution.note,
            )
        } catch (failure: FileToolException) {
            errorJson(failure.code, failure.message ?: "文件操作失败")
        } catch (failure: IllegalArgumentException) {
            // 保留原始文案：以前统一成「文件环境、路径或参数无效」，
            // 而真正的原因（例如「query 缺失」）就写在 message 里，白白丢了。
            errorJson("INVALID_ARGUMENT", failure.message?.takeIf(String::isNotBlank) ?: "文件环境、路径或参数无效")
        } catch (failure: org.json.JSONException) {
            errorJson("INVALID_ARGUMENT", failure.message ?: "文件参数缺失或类型错误")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            errorJson("CANCELLED", "文件操作已取消")
        } catch (_: java.io.IOException) {
            errorJson("FILE_IO_ERROR", "文件环境不可访问")
        }
    }

    /** `edit_files` 的每个文件都可能属于另一侧环境，逐个取出来参与判定。 */
    private fun filePatchPaths(args: JSONObject): List<String> {
        val files = args.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (index in 0 until files.length()) {
                files.optJSONObject(index)
                    ?.optString("path")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    /**
     * 追加（而不是覆盖）environment_note：失败路径自己可能已经写过一条更具体的解释，
     * 两条信息对模型都有用，合并成一句比丢掉一条更好。
     *
     * 合并规则（含 JSON null 会退化成字面量 "null" 的坑）都在 [EnvironmentNoteMerge]，有单测守着。
     */
    private fun appendEnvironmentNote(result: String, note: String?): String =
        EnvironmentNoteMerge.merge(result, note)

    private fun normalizeIdentity(identity: String): String {
        val normalized = identity.lowercase()
        require(normalized == "root" || normalized == "user") {
            "identity 仅支持 root/user"
        }
        return normalized
    }

    private fun normalizeEnvironment(environment: String): TerminalEnvironment =
        when (environment.ifBlank { TerminalEnvironment.ANDROID.wireName }.lowercase()) {
            TerminalEnvironment.ANDROID.wireName -> TerminalEnvironment.ANDROID
            SELECTED_LINUX_WIRE_NAME -> executionContext.linuxEnvironment
            else -> throw IllegalArgumentException("environment 仅支持 android/linux")
        }

    private fun environmentPreflight(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String? = rootfsPathFor(environment),
    ): String? = when {
        environment.isLinux && identity != "root" && LinuxEnvironmentPaths.backendOf(rootfsPath) != LinuxExecutionBackend.PROOT ->
            errorJson("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
        environment.isLinux && !LinuxEnvironmentPaths.rootfsReady(rootfsPath) ->
            errorJson(
                "LINUX_ENVIRONMENT_NOT_READY",
                "Linux 工具环境尚未安装，请先在设置中完成环境配置",
            )
        identity == "root" && !rootAvailable() -> errorJson("ROOT_REQUIRED", "Root 授权不可用")
        environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT && identity == "root" ->
            errorJson("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份，请使用 identity=user")
        else -> null
    }

    private fun defaultIdentity(environment: TerminalEnvironment): String =
        executionContext.identityFor(environment, rootAvailable())

    private fun androidWorkspace(): String =
        executionContext.androidWorkspacePath

    private fun normalizeCwd(cwd: String?, environment: TerminalEnvironment, identity: String): String {
        val defaultCwd = if (environment.isLinux) executionContext.linuxCwd else androidWorkspace()
        val requested = cwd.orEmpty().ifEmpty { defaultCwd }
        require('\u0000' !in requested && requested.length <= 4096) { "工作目录无效或过长" }
        val environmentPath = when {
            requested == "~" || requested.startsWith("~/") || requested.startsWith("/") -> requested
            else -> "$defaultCwd/$requested"
        }
        return if (environment.isLinux) {
            val value = when { environmentPath == "~" -> "/root"; environmentPath.startsWith("~/") -> "/root/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            value
        } else if (identity == "user") {
            val value = when { environmentPath == "~" -> defaultCwd; environmentPath.startsWith("~/") -> "$defaultCwd/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).canonicalPath
        } else normalizePath(environmentPath)
    }

    private fun normalizePath(path: String): String {
        val raw = path
        require(raw.isNotEmpty() && '\u0000' !in raw) { "path 无效" }
        val effective = when {
            raw == "~" -> USER_STORAGE
            raw.startsWith("~/") -> USER_STORAGE + "/" + raw.removePrefix("~/")
            raw.startsWith("/") -> raw
            // 相对路径按本次会话工作区解析；自定义工作区与 root 的全局目录都由 executionContext 承载。
            else -> "${executionContext.userWorkspacePath}/$raw"
        }
        // Root 可见的链接未必对 App UID 可见，交给目标 Shell 按真实命名空间解析。
        return effective
    }

    private fun startSessionProcess(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String?,
    ): Process? =
        processSupervisor.startShellProcess(
            identity = identity,
            command = null,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = rootfsPath,
            linuxSharedMounts = sharedMountsFor(environment),
        )

    /** 共享挂载只在 Linux 会话建立时解析；Android 环境不涉及。 */
    private fun sharedMountsFor(environment: TerminalEnvironment): List<SharedFolderMount> =
        if (environment.isLinux) linuxSharedMountsProvider() else emptyList()

    private fun rootfsPathFor(environment: TerminalEnvironment): String? =
        if (environment.isLinux) executionContext.linuxRootfsPath else null

    /**
     * 环境变量注入前缀：值只在 0600 脚本里，命令行里只出现路径。
     *
     * 失败时不能静默执行（用户只会看到「变量是空的」），改成往 stderr 写一行提示；
     * 但绝不回退成把值拼进命令行——那正是 `ps` 能看到 token 的老问题。
     */
    private fun envPrefixFor(environment: TerminalEnvironment, surface: String): String? {
        val outcome = runCatching { environmentScriptProvider(environment) }
        outcome.exceptionOrNull()?.let { error ->
            logger.warn("terminal env injection failed surface=$surface error=${error.javaClass.simpleName}")
            return envFailurePrefix()
        }
        val script = outcome.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return "$script; "
    }

    private fun runText(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
    ): ShellTextResult {
        val result = runProcess(
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = environment,
            maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd(),
            outputTruncated = result.outputTruncated,
            stderrTruncated = result.stderrTruncated,
        )
    }

    private fun runProcess(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        stdin: ByteArray?,
        environment: TerminalEnvironment,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): OneShotShellResult =
        runOneShotShell(
            processSupervisor = processSupervisor,
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
            maxOutputBytes = maxOutputBytes,
        )

    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) this else take(MAX_OUTPUT_CHARS) + "\n...[truncated]"

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(300))
            .toString()

    private data class ShellTextResult(
        val exitCode: Int,
        val output: String,
        val stderr: String,
        val outputTruncated: Boolean = false,
        val stderrTruncated: Boolean = false,
    )
    private data class SessionCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val cwd: String?,
        val timedOut: Boolean
    )

    private class TerminalSession(
        val id: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val rootfsPath: String?,
        var cwd: String,
        val createdAt: Long,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector
    ) {
        val lock = Any()

        var runtime: JSONObject? = null

        @Volatile
        var closed: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

}

/**
 * 守护任务对外的视图。
 *
 * 原来这里只输出 `running`，`completed_at` / `end_reason` 全缺 ——
 * 模型看到 `running=false` 也**不知道它什么时候结束、是不是异常中断**，
 * 而记录里其实一直有这些值（第十一批之后守护任务才会真的写 `completed_at`）。
 * 与命令任务的 `taskJson` 用同一套字段名，免得同一个概念两种叫法。
 *
 * 抽成顶层函数是为了能本机测（[RootShellTerminalController.daemonList] 需要真实 shell 探活）。
 */
internal fun daemonTaskJson(status: DetachedTaskStatus): JSONObject = JSONObject()
    .put("task_id", status.task.id)
    .put("pid", status.task.pid)
    .put("running", status.running)
    .put("command", status.task.command)
    .put("cwd", status.task.cwd)
    .put("identity", status.task.identity)
    .put("environment", status.task.environment.wireName)
    .put("started_at", status.task.startedAt)
    .put("completed_at", status.task.completedAt ?: JSONObject.NULL)
    .put("end_reason", status.task.endReason ?: JSONObject.NULL)
