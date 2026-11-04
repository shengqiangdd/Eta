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
        sessionScope: String? = null,
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
        sessionScope = sessionScope,
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

    @Test
    fun otherSessionsTasksAreHiddenButCounted() {
        val view = summarizeBackgroundTasks(
            listOf(
                task("mine", 1_000L, commandTask = true, sessionScope = "scope-a"),
                task("theirs", 2_000L, commandTask = true, sessionScope = "scope-b"),
                task("theirs_done", 500L, completedAt = 9_000L, commandTask = true, sessionScope = "scope-b"),
            ),
            viewerScope = "scope-a",
        )

        assertEquals("只列本会话", listOf("mine"), view.running.map { it.id })
        assertEquals(0, view.finished)
        assertEquals("另有 2 条属于别的会话，得说出来", 2, view.foreignTotal)
    }

    @Test
    fun scopedViewMatchesToolListing() {
        // 面板与 `terminal_job list` 必须同一口径：这条断言就是那个口径本身。
        // 两边分叉时的表现是「面板显示执行中 2、工具查 0 条」，用户只会以为工具坏了。
        val records = listOf(
            task("a", 1_000L, commandTask = true, sessionScope = "scope-a"),
            task("b", 2_000L, commandTask = true, sessionScope = "scope-b"),
        )

        assertEquals(1, summarizeBackgroundTasks(records, "scope-a").runningTotal)
        assertEquals(1, summarizeBackgroundTasks(records, "scope-b").runningTotal)
    }

    @Test
    fun noScopedViewerKeepsLegacyBehaviour() {
        // 语音一次性入口这类调用方没有会话概念，限制它只会让功能坏掉。
        val records = listOf(
            task("a", 1_000L, commandTask = true, sessionScope = "scope-a"),
            task("b", 2_000L, commandTask = true, sessionScope = "scope-b"),
        )

        val view = summarizeBackgroundTasks(records, viewerScope = null)

        assertEquals(2, view.runningTotal)
        assertEquals(0, view.foreignTotal)
    }

    @Test
    fun unscopedTasksStayVisibleToEveryone() {
        // 旧记录没有归属；升完级突然消失比偶尔多看到一条难受得多。
        val view = summarizeBackgroundTasks(
            listOf(task("legacy", 1_000L, commandTask = true)),
            viewerScope = "scope-a",
        )

        assertEquals(listOf("legacy"), view.running.map { it.id })
        assertEquals(0, view.foreignTotal)
    }

    @Test
    fun foreignOnlyViewDoesNotKeepTheCard() {
        // 面板不展示别的会话的任务（用户明确不需要），所以只剩别的会话的记录时卡片应该消失。
        // 这不是「把人家的也删了」：记录、`terminal_job list` 都不受影响，只是本会话不显示。
        val view = summarizeBackgroundTasks(
            listOf(task("theirs", 1_000L, commandTask = true, sessionScope = "scope-b")),
            viewerScope = "scope-a",
        )

        assertEquals(0, view.runningTotal)
        assertEquals(0, view.finished)
        assertEquals(1, view.foreignTotal)
        assertTrue("只剩别的会话的记录时本会话卡片不该出现", view.isEmpty)
    }

    @Test
    fun pendingScopeDoesNotClaimOtherSessionsTasks() {
        // 新建对话在发出第一条消息前没有会话 id（goalScopeId 为空）。旧行为把它当成
        // 「没有会话概念」的老调用方，于是别的会话的任务变成本会话「执行中 1」——
        // 真机上就是这么显示一个自己根本没派过的任务的。
        val view = summarizeBackgroundTasks(
            records = listOf(
                task("theirs", 1_000L, commandTask = true, sessionScope = "scope-b"),
                task("legacy", 2_000L, commandTask = true),
            ),
            viewerScope = null,
            scopePending = true,
        )

        assertEquals("只认无归属的旧记录", listOf("legacy"), view.running.map { it.id })
        assertEquals(1, view.foreignRunning)
        assertEquals(0, view.foreignFinished)
    }

    @Test
    fun foreignCountsSayWhetherOtherSessionsAreStillRunning() {
        // 「其他会话 1」不够：那条可能早就结束了。旧版一律这么写，真机上被读成
        // 「别的会话正在占资源」。执行中与已结束必须分开数。
        val view = summarizeBackgroundTasks(
            listOf(
                task("live", 1_000L, commandTask = true, sessionScope = "scope-b"),
                task("done", 500L, completedAt = 9_000L, commandTask = true, sessionScope = "scope-b"),
            ),
            viewerScope = "scope-a",
        )

        assertEquals(2, view.foreignTotal)
        assertEquals(1, view.foreignRunning)
        assertEquals(1, view.foreignFinished)
    }
}
