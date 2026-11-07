package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetachedTaskSupervisorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val supervisors = mutableListOf<DetachedTaskSupervisor>()

    private fun newSupervisor(): DetachedTaskSupervisor {
        val supervisor = DetachedTaskSupervisor(
            logger = NoopLogger,
            recordsFile = File(temporaryFolder.root, "terminal-daemons.json"),
            daemonDir = File(temporaryFolder.root, "daemon").absolutePath,
        )
        supervisors += supervisor
        return supervisor
    }

    @After
    fun tearDown() {
        // 兜底清理测试残留的 detached 进程（正常路径 stop 已经杀掉）。
        supervisors.flatMap { it.list() }.forEach { status ->
            ProcessHandle.of(status.task.pid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun deniedForegroundLeaseNeverStartsProcess() {
        val marker = File(temporaryFolder.root, "must-not-exist")
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"),
            daemonDir = File(temporaryFolder.root, "daemon").path,
            acquireUserLease = { _, _ -> false })
        val result = supervisor.start("touch ${shellQuote(marker.path)}", temporaryFolder.root.path, "user", TerminalEnvironment.ANDROID)
        assertEquals("BACKGROUND_START_NOT_ALLOWED", (result as DaemonStartResult.Failed).code)
        assertFalse(marker.exists())
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun stopDuringLeaseAcquisitionNeverStartsProcess() {
        val marker = File(temporaryFolder.root, "must-not-exist")
        var released = false
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"),
            daemonDir = File(temporaryFolder.root, "daemon").path,
            acquireUserLease = { _, stop -> stop(); true },
            releaseUserLease = { released = true })
        val result = supervisor.start("touch ${shellQuote(marker.path)}", temporaryFolder.root.path, "user", TerminalEnvironment.ANDROID)
        assertEquals("TASK_CANCELLED", (result as DaemonStartResult.Failed).code)
        assertFalse(marker.exists())
        assertTrue(released)
    }

    @Test
    fun rootStartWithoutGrantIsRejectedBeforeLaunchingShell() {
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"), rootAvailable = { false })
        val result = supervisor.start("id", "/", "root", TerminalEnvironment.ANDROID)
        assertEquals("ROOT_REQUIRED", (result as DaemonStartResult.Failed).code)
    }

    @Test
    fun startedTaskKeepsRunningAfterLauncherExits() {
        val supervisor = newSupervisor()
        val result = supervisor.start(
            command = "sleep 30",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$result", result is DaemonStartResult.Started)
        val task = (result as DaemonStartResult.Started).task

        // start 返回后宿主进程仍被托管；任务不依赖前台命令会话。
        assertTrue(ProcessHandle.of(task.pid).map { it.isAlive }.orElse(false))

        val statuses = supervisor.list()
        assertEquals(1, statuses.size)
        assertTrue(statuses.single().running)

        assertTrue(supervisor.stop(task.id))
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun logsRemainReadableAfterTaskExits() {
        val supervisor = newSupervisor()
        val result = supervisor.start(
            command = "for i in 1 2 3; do echo line\$i; done",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$result", result is DaemonStartResult.Started)
        val task = (result as DaemonStartResult.Started).task

        assertTrue("task should exit shortly", awaitExited(supervisor, task.id))

        val logs = supervisor.readLogs(task.id)
        assertTrue(logs.ok)
        assertTrue(logs.text.contains("line1"))
        assertTrue(logs.text.contains("line3"))

        // 已退出任务保留记录供查看日志，stop 负责清理。
        assertTrue(supervisor.stop(task.id))
        assertTrue(supervisor.list().isEmpty())
        assertFalse(supervisor.readLogs(task.id).ok)
    }

    @Test
    fun runningTaskIsAdoptedByNewSupervisorInstance() {
        val first = newSupervisor()
        val started = first.start(
            command = "sleep 30",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$started", started is DaemonStartResult.Started)
        val task = (started as DaemonStartResult.Started).task

        // 模拟 App 重启：新实例加载同一份记录文件，仍应认领存活进程并能停止它。
        val second = newSupervisor()
        val statuses = second.list()
        assertEquals(1, statuses.size)
        assertEquals(task.id, statuses.single().task.id)
        assertTrue(statuses.single().running)
        assertTrue(second.stop(task.id))
    }

    @Test
    fun rejectsStartBeyondCapacity() {
        val supervisor = newSupervisor()
        val started = mutableListOf<DetachedTask>()
        try {
            repeat(DetachedTaskSupervisor.MAX_TASKS) {
                val result = supervisor.start(
                    command = "sleep 30",
                    cwd = temporaryFolder.root.absolutePath,
                    identity = "user",
                    environment = TerminalEnvironment.ANDROID,
                )
                assertTrue("$result", result is DaemonStartResult.Started)
                started += (result as DaemonStartResult.Started).task
            }
            val overflow = supervisor.start(
                command = "sleep 30",
                cwd = temporaryFolder.root.absolutePath,
                identity = "user",
                environment = TerminalEnvironment.ANDROID,
            )
            assertTrue("$overflow", overflow is DaemonStartResult.Failed)
            assertEquals("MAX_TASKS_REACHED", (overflow as DaemonStartResult.Failed).code)
        } finally {
            started.forEach { supervisor.stop(it.id) }
        }
    }

    /**
     * 后台命令也要带上 env.conf：设置里配了 token，跑 `terminal_job` 时不该「变量是空的」。
     * 同时守住记录：任务列表/通知里只出现用户写的命令，不能混进临时脚本路径。
     */
    @Test
    fun commandTaskAppliesEnvPrefixWithoutRecordingIt() {
        val supervisor = newSupervisor()
        val script = File(temporaryFolder.root, ".eta-env-test.sh")
        script.writeText("export ETA_TEST_ENV=prefix_value\n")
        val prefix = ". ${shellQuote(script.path)} 2>/dev/null; rm -f ${shellQuote(script.path)}; "
        val userCommand = "printf 'seen=[%s]' \"${'$'}ETA_TEST_ENV\""
        val started = supervisor.start(
            command = userCommand,
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            commandTask = true,
            description = "env prefix",
            timeoutMs = 20_000,
            envPrefix = prefix,
        )
        assertTrue("$started", started is DaemonStartResult.Started)
        val task = (started as DaemonStartResult.Started).task
        try {
            assertEquals("记录里必须是用户写的命令", userCommand, task.command)
            val deadline = System.currentTimeMillis() + 15_000
            var logs = ""
            while (System.currentTimeMillis() < deadline) {
                supervisor.list()
                logs = supervisor.readLogs(task.id).text
                if (logs.contains("seen=[prefix_value]")) break
                Thread.sleep(100)
            }
            assertTrue("后台命令应拿到注入的变量：$logs", logs.contains("seen=[prefix_value]"))
        } finally {
            supervisor.stop(task.id)
        }
    }

    @Test
    fun stopCleansRecordOfDeadTask() {
        val supervisor = newSupervisor()
        writeRecord(pid = 4_000_000_000L, token = "any-token")
        assertEquals(1, supervisor.list().size)
        assertFalse(supervisor.list().single().running)
        assertTrue(supervisor.stop("dm_stale01"))
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun stopWithMismatchedTokenDoesNotSignalProcess() {
        // PID 复用防护依赖 /proc/<pid>/environ 的 token 校验，无 /proc 的宿主上没有可校验通道。
        assumeTrue("/proc is required for token verification", File("/proc").isDirectory)
        val supervisor = newSupervisor()
        val self = ProcessHandle.current()
        writeRecord(pid = self.pid(), token = "stale-token")

        assertTrue(supervisor.stop("dm_stale01"))
        assertTrue("own process must survive the mismatched-token stop", self.isAlive)
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun linuxTaskPathsTranslateToHostDaemonDir() {
        val supervisor = newSupervisor()
        val linuxTask = DetachedTask(
            id = "dm_linux01",
            pid = 1234,
            token = "token",
            command = "kimi web",
            cwd = "/workspace",
            identity = "root",
            environment = TerminalEnvironment.DEBIAN,
            logPath = "/workspace/daemon/dm_linux01.log",
            startedAt = 0L,
        )
        val androidTask = linuxTask.copy(
            environment = TerminalEnvironment.ANDROID,
            logPath = "/data/local/tmp/eta/daemon/dm_android01.log",
        )

        assertEquals(
            "/data/local/tmp/eta/daemon/dm_linux01.log",
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath),
        )
        assertEquals(
            "/data/local/tmp/eta/daemon/dm_linux01.pid",
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath.removeSuffix(".log") + ".pid"),
        )
        // Android 任务的路径原样保留。
        assertEquals(
            androidTask.logPath,
            supervisor.hostDaemonPath(androidTask, androidTask.logPath),
        )
    }

    /**
     * 自定义工作区下的日志路径换算。
     *
     * 真机故障：用户把终端工作区设成 `/storage/emulated/0/Download/xxx`，`buildLinuxPayload`
     * 就把那个目录 bind 成 guest 的 `/workspace`，日志真实落在 `<自定义工作区>/daemon/`。
     * 而 `startCommand` 曾把 `hostWorkspace` 写死成 `ROOT_WORKSPACE_PATH`，`hostDaemonPath`
     * 于是拼出 `/data/local/tmp/eta/daemon/` —— 一个不存在的路径。直读失败后回退到
     * `runOneShotShell`，最终表现就是 `日志不可用：exit=-1（无法启动进程）`，
     * 而日志其实好好地躺在用户自己的工作区里。
     *
     * 上一个用例（`linuxTaskPathsTranslateToHostDaemonDir`）用的是 `hostWorkspace = null`，
     * 恰好回了默认值，所以曾经全绿而故障照旧 —— 这里必须显式给一个**非默认**的工作区。
     */
    @Test
    fun linuxLogsFollowCustomWorkspaceRatherThanRootDefault() {
        val supervisor = newSupervisor()
        // 用真实存在的临时目录当自定义工作区：记录值与当前实际工作区一致时不能被改写。
        val customWorkspace = File(temporaryFolder.root, "custom-workspace").apply { mkdirs() }.absolutePath
        File(customWorkspace, "daemon").mkdirs()
        // 日志文件得真的存在：`effectiveHostWorkspace` 按**这个任务的日志文件**在哪判断，
        // 而真机上任务跑过就一定有日志。只建目录不建文件，等于模拟一个从没写过的任务。
        File(customWorkspace, "daemon/job_custom01.log").writeText("out")
        File(customWorkspace, "daemon/job_custom01.exit").writeText("0")
        val linuxTask = DetachedTask(
            id = "job_custom01",
            pid = 1234,
            token = "token",
            command = "sh tools/gate.sh",
            cwd = "/workspace",
            identity = "root",
            environment = TerminalEnvironment.DEBIAN,
            logPath = "/workspace/daemon/job_custom01.log",
            startedAt = 0L,
            hostWorkspace = customWorkspace,
        )

        assertEquals(
            "$customWorkspace/daemon/job_custom01.log",
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath),
        )
        assertEquals(
            "$customWorkspace/daemon/job_custom01.exit",
            supervisor.hostDaemonPath(linuxTask, "/workspace/daemon/job_custom01.exit"),
        )
        // 不能退回默认目录：那正是故障时的错误路径。
        assertFalse(
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath).startsWith("/data/local/tmp/eta/daemon"),
        )
    }

    /**
     * 旧记录里写错的 `hostWorkspace` 要被纠正，且不能把“日志真的还在老位置”误判成错误。
     *
     * 判据是**这个任务的日志文件**在哪个候选目录里存在，而不是 daemon 目录在不在。
     * 这条曾经写错过并真机验证了出来：`/data/local/tmp/eta/daemon` 作为目录一直存在
     * （历史任务留了 17 个文件），拿目录存在当“记录值仍可用”的依据时，
     * 17 个旧任务里有 14 个仍然指错。所以本用例**特意把旧目录建出来**，只让日志缺失。
     *
     * 两种情况必须区分开：
     * - 旧目录在、但这个任务的日志不在 —— 应改用当前实际工作区；
     * - 这个任务的日志真在旧目录 —— 用户换过工作区、旧日志留在老位置，保持原样。
     */
    @Test
    fun hostWorkspaceIsCorrectedByLogFileNotByDirectoryExists() {
        val supervisor = newSupervisor()

        val oldDir = File(temporaryFolder.root, "old-workspace").apply { mkdirs() }
        File(oldDir, "daemon").mkdirs()
        val staleTask = DetachedTask(
            id = "job_stale01",
            pid = 1,
            token = "t",
            command = "true",
            cwd = "/workspace",
            identity = "root",
            environment = TerminalEnvironment.DEBIAN,
            logPath = "/workspace/daemon/job_stale01.log",
            startedAt = 0L,
            hostWorkspace = oldDir.absolutePath,
        )

        // 1. 旧目录存在，但**这个任务的日志**不在那里 → 必须按当前实际工作区纠正
        //    （真机上 14 个旧任务是这个形态）。
        //    当前工作区在测试里取不到自定义值，但可以问代码同一个入口，避免写死路弄。
        val current = TerminalRuntime.linuxWorkspacePathFor("/workspace/rootfs")
        assertEquals(
            "目录在但日志不在时，必须按日志实际位置纠正",
            "$current/daemon/job_stale01.log",
            supervisor.hostDaemonPath(staleTask, staleTask.logPath),
        )

        // 2. 旧目录里真有这个任务的日志 → 保留记录值，否则会把能读到的历史日志变成读不到。
        File(oldDir, "daemon/job_stale01.log").writeText("x")
        assertEquals(
            "旧日志文件确实在记录值下时，不能纠正",
            "${oldDir.absolutePath}/daemon/job_stale01.log",
            supervisor.hostDaemonPath(staleTask, staleTask.logPath),
        )
    }

    @Test
    fun recordSaveAdvancesChangesSignal() {
        // 界面刷新依赖这个信号：任务结束时只改记录，不会产生新的会话消息。
        val records = File(temporaryFolder.root, "terminal-daemons.json")
        val supervisor = DetachedTaskSupervisor(
            logger = NoopLogger,
            recordsFile = records,
            daemonDir = File(temporaryFolder.root, "daemon").absolutePath,
        )
        supervisors += supervisor
        val finished = DetachedTask(
            id = "job_done01",
            pid = 1L,
            token = "token",
            command = "true",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            logPath = File(temporaryFolder.root, "daemon/job_done01.log").absolutePath,
            startedAt = 1_000L,
            commandTask = true,
            completedAt = 2_000L,
            exitCode = 0,
            completionNotified = false,
        )
        records.writeText(JSONArray().put(finished.toRecordJson()).toString())

        val before = DetachedTaskSupervisor.changes.value
        assertTrue(supervisor.markCompletionNotified("job_done01"))
        assertTrue("落盘成功必须推进信号", DetachedTaskSupervisor.changes.value > before)
    }

    private fun writeRecord(pid: Long, token: String) {
        val records = JSONArray().put(
            JSONObject()
                .put("id", "dm_stale01")
                .put("pid", pid)
                .put("token", token)
                .put("command", "fake")
                .put("cwd", temporaryFolder.root.absolutePath)
                .put("identity", "user")
                .put("environment", TerminalEnvironment.ANDROID.wireName)
                .put("log_path", File(temporaryFolder.root, "daemon/dm_stale01.log").absolutePath)
                .put("started_at", System.currentTimeMillis())
        )
        File(temporaryFolder.root, "terminal-daemons.json").writeText(records.toString())
    }

    private fun awaitExited(
        supervisor: DetachedTaskSupervisor,
        id: String,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = supervisor.list().firstOrNull { it.task.id == id } ?: return true
            if (!status.running) return true
            Thread.sleep(150)
        }
        return false
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
