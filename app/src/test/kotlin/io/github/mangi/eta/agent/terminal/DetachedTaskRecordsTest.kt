package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 记录文件的读取契约（第十六批）。
 *
 * 同一份 `detached-tasks.json` 有两条读取路径：
 * - `loadTasksLocked()`（实例，API/探活/裁剪用）：读全量，**没有**大小上限；
 * - `readRecords()`（companion，卡片 / `<background_tasks>` 提示 / 自动续跑 watcher 用）：
 *   原来超过 256 KiB 直接返回**空列表**。
 *
 * 两条路径分叉的后果不是"少看到几条"，而是整条链路静默停摆：watcher 读到空 → 不扫描；
 * `needsDaemonProbe` 读到空 → 不探活 → 守护任务的 `completed_at` 永远不写 → 更不会有事件。
 */
class DetachedTaskRecordsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun task(id: String, completedAt: Long?) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = "sleep 1",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = 100L,
        completedAt = completedAt,
        sessionScope = "conv-a",
    )

    /**
     * 正常增长（不是文件损坏）超过旧门槛后，记录仍然要能读出来。
     *
     * 复现方式与真机一致：只是记录条数多，每条都合法。
     */
    @Test
    fun oversizedRecordsFileIsStillReadable() {
        val file = temporaryFolder.newFile("detached-tasks.json")
        val array = JSONArray()
        repeat(800) { index -> array.put(task("job_$index", 1_000L + index).toRecordJson()) }
        file.writeText(array.toString())
        assertTrue(
            "前置：构造的文件必须超过旧门槛 256 KiB（实际 ${file.length()} 字节）",
            file.length() > 256L * 1024,
        )
        val records = DetachedTaskSupervisor.readRecords(file)
        assertEquals("超过门槛返回空 = 后台任务、探活、自动续跑一起静默失效", 800, records.size)
        assertEquals("conv-a", records.first().sessionScope)
        assertEquals(1_000L, records.first().completedAt)
    }
}
