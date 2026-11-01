package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务结束事件段。
 *
 * 与 [BackgroundTasksSection] 的用例成对：那一段盯「还在跑的」，这一段盯「跑完了但还没告知的」。
 * 三件事必须守住：
 * 1. 水位线之前的收尾**不能重报**（否则每轮都刷同一批事件，白烧上下文）；
 * 2. 状态词要如实，不认识就原样带上，不猜「成功」；
 * 3. 不带命令正文与描述 —— 系统提示会发到模型服务商。
 */
class BackgroundEventsTest {

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
    fun runningRecordsProduceNoEvents() {
        val records = listOf(
            task("job_a", startedAt = 1_000L, commandTask = true),
            task("dm_b", startedAt = 2_000L),
        )
        assertTrue(BackgroundEvents.collect(records, deliveredUntil = null).isEmpty())
    }

    @Test
    fun finishedRecordsBecomeEventsOldestFirst() {
        val records = listOf(
            task("job_new", startedAt = 1_000L, completedAt = 9_000L, commandTask = true, exitCode = 0),
            task("dm_old", startedAt = 1_000L, completedAt = 5_000L, endReason = "stopped"),
        )

        val events = BackgroundEvents.collect(records, deliveredUntil = null)

        assertEquals(listOf("dm_old", "job_new"), events.map { it.taskId })
        assertFalse(events[0].commandTask)
        assertEquals("stopped", events[0].status)
        assertTrue(events[1].commandTask)
        assertEquals("completed", events[1].status)
        assertEquals(0, events[1].exitCode ?: -1)
    }

    @Test
    fun deliveredWatermarkHidesAlreadyReported() {
        val records = listOf(
            task("job_old", startedAt = 1_000L, completedAt = 5_000L, commandTask = true, exitCode = 0),
            task("job_new", startedAt = 2_000L, completedAt = 8_000L, commandTask = true, exitCode = 0),
        )

        val events = BackgroundEvents.collect(records, deliveredUntil = 5_000L)

        assertEquals(listOf("job_new"), events.map { it.taskId })
    }

    @Test
    fun watermarkExactlyAtCompletionStillHidesIt() {
        // 水位线就是「已告知到的结束时刻」，相等也是已告知。
        val records = listOf(task("job_a", startedAt = 1_000L, completedAt = 5_000L, commandTask = true, exitCode = 0))
        assertTrue(BackgroundEvents.collect(records, deliveredUntil = 5_000L).isEmpty())
    }

    @Test
    fun emptyEventListRendersNothing() {
        assertEquals("", BackgroundEvents.render(emptyList(), now = 10_000L))
    }

    @Test
    fun renderShowsKindStatusAndAge() {
        val records = listOf(
            task("job_a", startedAt = 1_000L, completedAt = 1_000L, commandTask = true, exitCode = 0),
            task("dm_b", startedAt = 1_000L, completedAt = 61_000L, endReason = "stopped"),
        )

        val section = BackgroundEvents.render(BackgroundEvents.collect(records, null), now = 121_000L)

        assertTrue(section.startsWith("<background_events>"))
        assertTrue(section.endsWith("</background_events>"))
        assertTrue(section.contains("- job_a（job）：已完成 · 2 分钟前"))
        assertTrue(section.contains("- dm_b（daemon）：已停止 · 1 分钟前"))
        assertTrue("必须写明只是记录", section.contains("不代表任务成功"))
    }

    @Test
    fun renderCapsAtSixAndCountsTheRest() {
        val records = (1..9).map { index ->
            task("job_$index", startedAt = index.toLong(), completedAt = index.toLong(), commandTask = true, exitCode = 0)
        }

        val section = BackgroundEvents.render(BackgroundEvents.collect(records, null), now = 10_000L)

        assertTrue(section.contains("共 9 条"))
        assertTrue(section.contains("- job_1（job）"))
        assertTrue(section.contains("- job_6（job）"))
        assertFalse("第 7 条不该单独列出", section.contains("- job_7（job）"))
        assertTrue(section.contains("另有 3 条未列出"))
    }

    @Test
    fun statusTextKeepsUnknownWords() {
        assertEquals("已完成", BackgroundEvents.statusText("completed"))
        assertEquals("失败", BackgroundEvents.statusText("failed"))
        assertEquals("已停止", BackgroundEvents.statusText("stopped"))
        assertEquals("已超时", BackgroundEvents.statusText("timed_out"))
        assertEquals("已中断", BackgroundEvents.statusText("interrupted"))
        assertEquals("已结束（weird）", BackgroundEvents.statusText("weird"))
    }

    @Test
    fun renderNeverIncludesCommandOrDescription() {
        val records = listOf(
            task(
                "job_a",
                startedAt = 1_000L,
                completedAt = 2_000L,
                commandTask = true,
                command = "echo super-secret-token",
                description = "私密描述",
                exitCode = 0,
            ),
        )

        val section = BackgroundEvents.render(BackgroundEvents.collect(records, null), now = 10_000L)

        assertFalse(section.contains("super-secret-token"))
        assertFalse(section.contains("私密描述"))
    }
}
