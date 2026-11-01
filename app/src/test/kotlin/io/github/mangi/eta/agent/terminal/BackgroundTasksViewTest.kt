package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务卡的投影。
 *
 * 卡片本身要真机截图才能看，本机跑不了；这几条能守住的是「卡片拿到的是裁剪过的素材」：
 * 计数、排序、上限、不把命令正文带进渲染路径。渲染文本（本地化时间）不在这一层测。
 */
class BackgroundTasksViewTest {

    private fun task(
        id: String,
        startedAt: Long,
        completedAt: Long? = null,
        commandTask: Boolean = false,
        command: String = "sleep 1",
        description: String = "",
        exitCode: Int? = null,
        endReason: String? = null,
    ) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = command,
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = startedAt,
        commandTask = commandTask,
        description = description,
        completedAt = completedAt,
        exitCode = exitCode,
        endReason = endReason,
    )

    @Test
    fun emptyRecordsMeanEmptyView() {
        val view = summarizeBackgroundTasks(emptyList())
        assertTrue(view.isEmpty)
        assertEquals(0, view.runningTotal)
        assertEquals(0, view.finished)
        assertTrue(view.running.isEmpty())
        assertTrue(view.recentFinished.isEmpty())
    }

    @Test
    fun countsSplitJobsAndDaemons() {
        val view = summarizeBackgroundTasks(
            listOf(
                task("job_a", 1_000L, commandTask = true),
                task("dm_b", 2_000L),
                task("dm_c", 3_000L),
                task("job_done", 500L, completedAt = 4_000L, commandTask = true, exitCode = 0),
            ),
        )

        assertEquals(2, view.runningDaemons)
        assertEquals(1, view.runningJobs)
        assertEquals(3, view.runningTotal)
        assertEquals(1, view.finished)
        assertFalse(view.isEmpty)
    }

    @Test
    fun runningRowsAreOldestFirstAndCapped() {
        val records = (1..7).map { index -> task("job_$index", startedAt = index.toLong() * 1_000L, commandTask = true) }

        val view = summarizeBackgroundTasks(records)

        assertEquals("计数是全部，不是列出的条数", 7, view.runningTotal)
        assertEquals(BACKGROUND_TASKS_MAX_RUNNING_ROWS, view.running.size)
        assertEquals(listOf("job_1", "job_2", "job_3", "job_4", "job_5", "job_6"), view.running.map { it.id })
    }

    @Test
    fun recentFinishedAreNewestFirstAndCapped() {
        val records = (1..5).map { index ->
            task("job_$index", startedAt = index.toLong(), completedAt = index.toLong() * 10_000L, commandTask = true, exitCode = 0)
        }

        val view = summarizeBackgroundTasks(records)

        assertEquals("已结束计数是全部", 5, view.finished)
        assertEquals(BACKGROUND_TASKS_MAX_FINISHED_ROWS, view.recentFinished.size)
        assertEquals(listOf("job_5", "job_4", "job_3"), view.recentFinished.map { it.id })
    }

    @Test
    fun runningLineUsesStartTime() {
        val line = backgroundTaskLine(task("job_a", 1_000L, commandTask = true), now = 61_000L)

        assertEquals("job_a", line.id)
        assertEquals("job", line.kind)
        assertEquals("running", line.status)
        assertTrue(line.running)
        assertEquals(60_000L, line.ageMs)
    }

    @Test
    fun finishedLineUsesCompletionTimeAndRealStatus() {
        val line = backgroundTaskLine(
            task("dm_b", 1_000L, completedAt = 5_000L, exitCode = null, endReason = "stopped"),
            now = 65_000L,
        )

        assertEquals("daemon", line.kind)
        assertEquals("stopped", line.status)
        assertFalse(line.running)
        assertEquals(60_000L, line.ageMs)
    }

    @Test
    fun futureTimestampsClampToZero() {
        // 设备改过时间时，记录里的开始时间可能比现在还晚；负数年龄不能显示成「-3 分钟」。
        val line = backgroundTaskLine(task("job_a", 100_000L, commandTask = true), now = 50_000L)
        assertEquals(0L, line.ageMs)
    }

    @Test
    fun lineCarriesNoCommandText() {
        val line = backgroundTaskLine(
            task("job_a", 1_000L, commandTask = true, command = "echo super-secret-token", description = "私密描述"),
            now = 2_000L,
        )
        assertFalse(line.toString().contains("super-secret-token"))
        assertFalse(line.toString().contains("私密描述"))
    }
}
