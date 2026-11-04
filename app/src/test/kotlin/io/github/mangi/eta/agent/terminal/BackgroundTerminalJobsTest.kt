package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundTerminalJobsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hosts = mutableListOf<BackgroundTerminalJobs>()
    private val notifications = AtomicInteger()

    private fun supervisor() = DetachedTaskSupervisor(NoopLogger, File(temporary.root, "tasks.json"),
        daemonDir = File(temporary.root, "logs").path, rootAvailable = { false })

    private fun host(supervisor: DetachedTaskSupervisor) = BackgroundTerminalJobs(supervisor, NoopLogger,
        onCompleted = { notifications.incrementAndGet() }).also { hosts += it }

    private fun controller(
        supervisor: DetachedTaskSupervisor,
        jobs: BackgroundTerminalJobs,
        scopeId: String = "",
    ) = RootShellTerminalController(
        NoopLogger,
        detachedSupervisor = supervisor,
        backgroundJobs = jobs,
        backgroundScopeId = scopeId,
        rootAvailable = { false },
    )

    @After fun closeHosts() { hosts.forEach { it.close() } }

    @Test fun commandContinuesAfterReplyAndItsResultIsVisibleToTheNextRun() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val first = controller(supervisor, jobs)
        val started = JSONObject(first.executeTool("terminal_job", JSONObject().put("action", "start")
            .put("command", "sleep 0.3; printf '完成\\n'").put("description", "生成报告").put("cwd", temporary.root.path)))
        assertTrue(started.toString(), started.getBoolean("ok"))
        first.closeAll()
        val second = controller(supervisor, jobs)
        try {
            val jobId = started.getString("job_id")
            val waited = JSONObject(second.executeTool("terminal_job", JSONObject().put("action", "wait")
                .put("job_id", jobId).put("wait_ms", 5000)))
            assertFalse(waited.toString(), waited.getBoolean("running"))
            val result = JSONObject(second.executeTool("terminal_job", JSONObject().put("action", "read").put("job_id", jobId)))
            assertEquals("completed", result.getString("status"))
            assertEquals(0, result.getInt("exit_code"))
            assertEquals("完成\n", result.getString("output"))
            assertEquals("完成\n", File(result.getString("output_path")).readText())
            assertEquals(1, notifications.get())
            assertEquals(1, JSONObject(jobs.list(false)).getJSONArray("tasks").length())
            assertEquals(0, JSONObject(jobs.list(true)).getJSONArray("tasks").length())
        } finally { second.closeAll() }
    }

    @Test fun fullOutputSurvivesPreviewTruncationAndHostRecreation() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val started = JSONObject(jobs.start(supervisor, "head -c 70000 /dev/zero | tr '\\000' x", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "长日志", 5000))
        val id = started.getString("job_id")
        jobs.waitFor(id, 5000) { false }
        val result = JSONObject(jobs.read(id, 1024))
        assertEquals("completed", result.getString("status"))
        assertEquals(1024, result.getString("output").length)
        assertTrue(result.getBoolean("output_truncated"))
        assertEquals(70000L, File(result.getString("output_path")).length())
        assertEquals(1, notifications.get())
        jobs.close()
        val restored = host(supervisor())
        restored.resume()
        val reread = JSONObject(restored.read(id))
        assertEquals("completed", reread.getString("status"))
        assertEquals(result.getString("output_path"), reread.getString("output_path"))
        assertEquals(1, notifications.get())
    }

    @Test fun failureTimeoutAndStopRemainDistinguishable() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val failed = JSONObject(jobs.start(supervisor, "printf problem; exit 7", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "失败任务", 5000)).getString("job_id")
        jobs.waitFor(failed, 5000) { false }
        val failure = JSONObject(jobs.read(failed))
        assertEquals("failed", failure.getString("status"))
        assertEquals(7, failure.getInt("exit_code"))
        val expired = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "超时任务", 100)).getString("job_id")
        jobs.waitFor(expired, 5000) { false }
        assertEquals("timed_out", JSONObject(jobs.read(expired)).getString("status"))
        val stopped = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "停止任务", 0)).getString("job_id")
        assertEquals("stopped", JSONObject(jobs.stop(stopped)).getString("status"))
        assertEquals("stopped", JSONObject(jobs.read(stopped)).getString("status"))
        assertEquals(3, notifications.get())
    }

    @Test fun workingDirectoryFailureIsPreservedInTheLog() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val started = JSONObject(jobs.start(supervisor, "printf must-not-run", File(temporary.root, "missing").path,
            "user", TerminalEnvironment.ANDROID, "错误目录", 5000))
        val id = started.getString("job_id")
        jobs.waitFor(id, 5000) { false }
        val result = JSONObject(jobs.read(id))
        assertEquals("failed", result.getString("status"))
        assertTrue(result.getInt("exit_code") != 0)
        assertTrue(result.getString("output").contains("missing"))
        assertFalse(result.getString("output").contains("must-not-run"))
    }

    @Test fun fastCommandsKeepTheirResultsAndNotifyOnce() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        repeat(12) { index ->
            val started = JSONObject(jobs.start(supervisor, "printf '$index'", temporary.root.path,
                "user", TerminalEnvironment.ANDROID, "快速命令", 5000))
            assertTrue(started.toString(), started.getBoolean("ok"))
            val id = started.getString("job_id")
            jobs.waitFor(id, 5000) { false }
            assertEquals(index.toString(), JSONObject(jobs.read(id)).getString("output"))
        }
        assertEquals(12, notifications.get())
    }

    @Test fun cancellingOrTimingOutTheWaitDoesNotStopTheCommand() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val id = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "等待测试", 0)).getString("job_id")
        val timeout = JSONObject(jobs.waitFor(id, 10) { false })
        assertTrue(timeout.getBoolean("wait_timed_out"))
        assertEquals("CANCELLED", JSONObject(jobs.waitFor(id, 1000) { true }).getString("code"))
        assertTrue(JSONObject(jobs.read(id)).getBoolean("running"))
        jobs.stop(id)
    }

    /**
     * 后台任务归属隔离的端到端回归。
     *
     * 回归点：任务记录是**一份全局文件**，`list()` 曾经完全不过滤，于是会话 A 面板里
     * 会出现会话 B 派的活；更糟的是只要知道 id，`read` / `stop` 照样能操作别人的活。
     * 纯函数 `BackgroundTaskOwnershipTest` 只守住了判定本身，这里守住的是**接线**：
     * scopeId 有没有真的从工具入口一路传进 list/read/stop/wait。
     */
    @Test fun otherSessionCannotListReadStopOrWaitForeignJobs() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val owner = controller(supervisor, jobs, scopeId = "conv-a")
        val intruder = controller(supervisor, jobs, scopeId = "conv-b")
        try {
            val started = JSONObject(
                owner.executeTool(
                    "terminal_job",
                    JSONObject().put("action", "start")
                        .put("command", "sleep 30")
                        .put("description", "A 的任务")
                        .put("cwd", temporary.root.path),
                )
            )
            assertTrue(started.toString(), started.getBoolean("ok"))
            val jobId = started.getString("job_id")

            // 会话 B 的列表里不能出现它，但必须告诉 B「还有别人的任务」——否则 B 会以为系统丢了任务。
            val intruderList = JSONObject(intruder.executeTool("terminal_job", JSONObject().put("action", "list")))
            assertEquals(0, intruderList.getJSONArray("tasks").length())
            assertEquals(1, intruderList.getInt("hidden_tasks"))
            assertTrue(intruderList.getString("hidden_note").contains("include_other=true"))

            // 知道 id 也没用：读、停、等三个入口都必须挡住。
            val read = JSONObject(intruder.executeTool("terminal_job",
                JSONObject().put("action", "read").put("job_id", jobId)))
            assertEquals("JOB_NOT_FOUND", read.getString("code"))
            assertFalse(read.toString(), read.getBoolean("ok"))
            assertTrue(read.getString("message").contains("它可能属于其他会话"))

            assertEquals("JOB_NOT_FOUND", JSONObject(intruder.executeTool("terminal_job",
                JSONObject().put("action", "stop").put("job_id", jobId))).getString("code"))
            assertEquals("JOB_NOT_FOUND", JSONObject(intruder.executeTool("terminal_job",
                JSONObject().put("action", "wait").put("job_id", jobId).put("wait_ms", 100))).getString("code"))

            // 被拒之后任务必须还在跑：挡住的是「别人的手脚」，不是「别人的任务」。
            val ownerList = JSONObject(owner.executeTool("terminal_job", JSONObject().put("action", "list")))
            assertEquals(1, ownerList.getJSONArray("tasks").length())
            assertTrue(JSONObject(owner.executeTool("terminal_job",
                JSONObject().put("action", "read").put("job_id", jobId))).getBoolean("running"))

            // 排查问题时的明确出口：include_other=true 能看到全部，且能正常读。
            val global = JSONObject(intruder.executeTool("terminal_job",
                JSONObject().put("action", "list").put("include_other", true)))
            assertEquals(1, global.getJSONArray("tasks").length())
            assertEquals(jobId, global.getJSONArray("tasks").getJSONObject(0).getString("job_id"))
            assertFalse(global.has("hidden_tasks"))

            owner.executeTool("terminal_job", JSONObject().put("action", "stop").put("job_id", jobId))
        } finally {
            owner.closeAll()
            intruder.closeAll()
        }
    }

    /**
     * 旧记录没有归属字段，升级后必须仍然可见、可停。
     *
     * 否则用户升级完发现自己之前在后台跑的活凭空消失、也停不掉 —— 而那条记录里
     * 并没有任何能用来判断归属的信息，拒绝它只是单方面制造损失。
     */
    @Test fun legacyJobsWithoutScopeStayUsable() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        // 不传 scopeId 的 controller 等价于旧入口（语音 / 外部直调），写出的任务没有归属。
        val legacy = controller(supervisor, jobs)
        val scoped = controller(supervisor, jobs, scopeId = "conv-a")
        try {
            val jobId = JSONObject(legacy.executeTool("terminal_job",
                JSONObject().put("action", "start").put("command", "sleep 30")
                    .put("description", "旧任务").put("cwd", temporary.root.path))).getString("job_id")
            val seen = JSONObject(scoped.executeTool("terminal_job", JSONObject().put("action", "list")))
            assertEquals(1, seen.getJSONArray("tasks").length())
            assertFalse(seen.has("hidden_tasks"))
            assertEquals("stopped", JSONObject(scoped.executeTool("terminal_job",
                JSONObject().put("action", "stop").put("job_id", jobId))).getString("status"))
        } finally {
            legacy.closeAll()
            scoped.closeAll()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
