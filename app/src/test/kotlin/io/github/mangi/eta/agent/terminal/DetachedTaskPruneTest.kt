package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录淘汰规则（第十六批）。
 *
 * 记录文件里躺着的不只是"历史"，还是**自动续跑的事件源**：一条带 `sessionScope` 的记录
 * 结束之后，要等某一次扫描读到它的 `completed_at` 才会触发续跑。淘汰掉一条还没被消费的
 * 完成记录 == 这条事件从未发生。原来的排序键是 `startedAt`（越老越先删），于是"跑了很久
 * 才结束的守护任务"永远排第一 —— 它一完成就被删，事件连一次扫描都赶不上。
 */
class DetachedTaskPruneTest {

    private fun status(
        id: String,
        startedAt: Long,
        completedAt: Long?,
        running: Boolean,
        commandTask: Boolean = false,
        completionNotified: Boolean = false,
    ) = DetachedTaskStatus(
        task = DetachedTask(
            id = id,
            pid = 42L,
            token = "token",
            command = "sleep 1",
            cwd = "/data/local/tmp",
            identity = "root",
            environment = TerminalEnvironment.ANDROID,
            logPath = "/data/local/tmp/eta/daemon/$id.log",
            startedAt = startedAt,
            commandTask = commandTask,
            completedAt = completedAt,
            completionNotified = completionNotified,
            sessionScope = "conv-a",
        ),
        running = running,
    )

    /** 超额时只淘汰到上限，且先淘汰**最早完成**的（不是最早启动的）。 */
    @Test
    fun prunesEarliestCompletedFirst() {
        val now = 10_000_000L
        val statuses = (0 until 40).map { index ->
            // 故意让「最老的启动时间」对应「最新的完成时间」：按 startedAt 排序会选错。
            status(
                id = "job_$index",
                startedAt = 1_000L - index,
                completedAt = 1_000L + index,
                running = false,
            )
        }
        val victims = pickPruneVictims(statuses, maxRetained = 32, nowMs = now)
        assertEquals(8, victims.size)
        assertEquals(
            "应先淘汰完成得最早的 8 条",
            (0 until 8).map { "job_$it" },
            victims.map { it.task.id },
        )
        assertTrue("刚完成的那些不能被淘汰", victims.none { it.task.id == "job_39" })
    }

    /** 运行中的、以及还没通知用户的命令任务，永远不淘汰。 */
    @Test
    fun neverPrunesRunningOrUnnotifiedCommands() {
        val now = 10_000_000L
        val statuses = listOf(
            status("running", startedAt = 1L, completedAt = null, running = true),
            status("not_notified", startedAt = 2L, completedAt = 5_000L, running = false, commandTask = true),
            status("notified", startedAt = 3L, completedAt = 6_000L, running = false, commandTask = true, completionNotified = true),
            status("daemon_done", startedAt = 4L, completedAt = 7_000L, running = false),
        )
        val victims = pickPruneVictims(statuses, maxRetained = 1, nowMs = now)
        assertEquals(listOf("notified", "daemon_done"), victims.map { it.task.id })
    }

    /**
     * 刚完成（保护期内）的记录即使最老也要留着 —— 自动续跑还没机会读到它。
     *
     * 兜底扫描是 30 秒一次，保护期取两倍余量。
     */
    @Test
    fun keepsRecordsCompletedWithinGrace() {
        val now = 10_000_000L
        val statuses = listOf(
            // 启动最早、但刚刚才结束：这是长跑的守护任务，最容易被旧排序误删。
            status("just_finished", startedAt = 1L, completedAt = now - 1_000L, running = false),
            status("long_done", startedAt = 2L, completedAt = now - 600_000L, running = false),
        )
        val victims = pickPruneVictims(statuses, maxRetained = 1, nowMs = now)
        assertEquals(listOf("long_done"), victims.map { it.task.id })
    }

    /** 没超额就一条都不动（也不会去起 shell 删日志）。 */
    @Test
    fun prunesNothingWithinRetention() {
        val statuses = (0 until 32).map { index ->
            status("job_$index", startedAt = index.toLong(), completedAt = 1_000L + index, running = false)
        }
        assertTrue(pickPruneVictims(statuses, maxRetained = 32, nowMs = 10_000_000L).isEmpty())
    }
}
