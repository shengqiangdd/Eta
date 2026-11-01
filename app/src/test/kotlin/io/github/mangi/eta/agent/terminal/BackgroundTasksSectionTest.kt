package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 后台任务提示段。
 *
 * 这段文字每轮都会进模型上下文，所以两件事必须守住：
 * 1. **只报未结束的**，结束的任务不该变成长期噪音；
 * 2. **不带命令正文** —— 记录里的命令可能是用户在终端里手敲的（含路径、令牌），
 *    而系统提示会发给模型服务商。下面有一条专门盯着它。
 */
class BackgroundTasksSectionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun task(
        id: String,
        startedAt: Long,
        completedAt: Long? = null,
        commandTask: Boolean = false,
        command: String = "sleep 1",
        description: String = "",
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
        exitCode = if (completedAt == null) null else 0,
    )

    @Test
    fun noRecordsRenderNothing() {
        assertEquals("", BackgroundTasksSection.render(emptyList(), now = 10_000L))
    }

    @Test
    fun finishedRecordsRenderNothing() {
        // 已结束的任务留在记录里可查，但不该每轮都占模型上下文。
        val tasks = listOf(
            task("job_a", startedAt = 1_000L, completedAt = 2_000L, commandTask = true),
            task("dm_b", startedAt = 1_500L, completedAt = 3_000L),
        )
        assertEquals("", BackgroundTasksSection.render(tasks, now = 10_000L))
    }

    @Test
    fun runningJobsAndDaemonsAreCountedAndListedOldestFirst() {
        val tasks = listOf(
            task("job_new", startedAt = 2_000_000L, commandTask = true),
            task("dm_old", startedAt = 1_000_000L),
            task("job_done", startedAt = 500_000L, completedAt = 600_000L, commandTask = true),
        )

        val text = BackgroundTasksSection.render(tasks, now = 2_600_000L)

        assertTrue("只数未结束的两条", text.contains("还有 2 条未结束"))
        assertTrue(text.contains("job 1"))
        assertTrue(text.contains("daemon 1"))
        assertTrue("最久没管的排最前", text.indexOf("dm_old") < text.indexOf("job_new"))
        assertTrue("已结束的不进提示", !text.contains("job_done"))
        // 最早一条 = dm_old，now - startedAt = 1.6e6ms ≈ 26 分钟
        assertTrue(text.contains("26 分钟前"))
        assertTrue("必须写明没做存活探测", text.contains("没有做存活探测"))
        assertTrue(text.startsWith("<background_tasks>"))
        assertTrue(text.endsWith("</background_tasks>"))
    }

    @Test
    fun idsAreCappedAndTheRestAreCounted() {
        val tasks = (1..8).map { index -> task("job_$index", startedAt = index * 1_000L, commandTask = true) }

        val text = BackgroundTasksSection.render(tasks, now = 20_000L)

        assertTrue(text.contains("还有 8 条未结束"))
        assertTrue("只列前 6 个 id", text.contains("job_6"))
        assertFalse(text.contains("job_7"))
        assertTrue(text.contains("另有 2 条未列出"))
    }

    @Test
    fun commandTextNeverReachesThePrompt() {
        // 回归守卫：系统提示会被发到模型服务商，命令正文（可能含令牌）不能跟着走。
        val tasks = listOf(
            task(
                "job_secret",
                startedAt = 1_000L,
                commandTask = true,
                command = "curl -H 'Authorization: Bearer sk-live-DEADBEEF' https://example.invalid",
                description = "私有描述不该出现",
            ),
        )

        val text = BackgroundTasksSection.render(tasks, now = 2_000L)

        assertTrue(text.contains("job_secret"))
        assertFalse("命令正文不能进提示", text.contains("sk-live-DEADBEEF"))
        assertFalse(text.contains("curl"))
        assertFalse("描述也不带", text.contains("私有描述"))
    }

    @Test
    fun ageLabelRoundsToTheLargestUnit() {
        assertEquals("不到 1 分钟", BackgroundTasksSection.ageLabel(0))
        assertEquals("不到 1 分钟", BackgroundTasksSection.ageLabel(59_000L))
        assertEquals("不到 1 分钟", BackgroundTasksSection.ageLabel(-5_000L))
        assertEquals("1 分钟", BackgroundTasksSection.ageLabel(60_000L))
        assertEquals("59 分钟", BackgroundTasksSection.ageLabel(59 * 60_000L + 30_000L))
        assertEquals("2 小时", BackgroundTasksSection.ageLabel(120 * 60_000L))
        assertEquals("3 天", BackgroundTasksSection.ageLabel(3L * 86_400_000L + 5_000L))
    }

    @Test
    fun startInTheFutureDoesNotProduceNegativeAge() {
        val tasks = listOf(task("job_a", startedAt = 5_000L, commandTask = true))
        val text = BackgroundTasksSection.render(tasks, now = 1_000L)
        assertTrue(text.contains("不到 1 分钟前"))
    }

    @Test
    fun readRecordsReadsTheFileAndLeavesItUntouched() {
        val file = File(temporaryFolder.root, "terminal-daemons.json")
        val array = JSONArray()
        array.put(task("job_a", startedAt = 1_000L, commandTask = true).toRecordJson())
        array.put(task("dm_b", startedAt = 2_000L).toRecordJson())
        file.writeText(array.toString())
        val before = file.readBytes()

        val records = DetachedTaskSupervisor.readRecords(file)

        assertEquals(listOf("job_a", "dm_b"), records.map { it.id })
        assertTrue("读路径不能改写记录文件", before.contentEquals(file.readBytes()))
    }

    @Test
    fun corruptRecordsFileYieldsNothingAndStaysUntouched() {
        val file = File(temporaryFolder.root, "terminal-daemons.json")
        file.writeText("{ 这不是 JSON")
        val before = file.readBytes()

        assertTrue(DetachedTaskSupervisor.readRecords(file).isEmpty())
        assertTrue("坏文件也不在读取时“重置”", before.contentEquals(file.readBytes()))
    }

    @Test
    fun missingRecordsFileYieldsNothing() {
        assertTrue(DetachedTaskSupervisor.readRecords(File(temporaryFolder.root, "absent.json")).isEmpty())
    }
}
