package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal_daemon list` 的对外视图。
 *
 * 这里守的不是「字段好看」，而是一个真出现过的缺陷：守护任务被 kill 之后，
 * `daemonList()` 只输出 `running=false`，`completed_at` / `end_reason` / `description` 全缺。
 * 记录里明明有这些值（第十一批让守护任务第一次真的写 `completed_at`），
 * 模型却只能看到「它现在没跑」，既不知道什么时候结束，也不知道是不是异常中断。
 *
 * 与命令任务的 `taskJson` 用同一套字段名（`completed_at` / `end_reason`），
 * 所以这里连命名一起断言 —— 同一个概念两种叫法，迟早会有人读错。
 */
class DaemonTaskViewTest {

    private fun task(
        commandTask: Boolean = false,
        completedAt: Long? = null,
        endReason: String? = null,
    ) = DetachedTask(
        id = "dm_12345678",
        pid = 4242,
        token = "token",
        command = "sleep 900",
        cwd = "/workspace",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/dm_12345678.log",
        startedAt = 1_700_000_000_000,
        commandTask = commandTask,
        completedAt = completedAt,
        endReason = endReason,
    )

    /** 还在跑：`completed_at` 必须是 JSON null（不能是 0，也不能缺键）。 */
    @Test
    fun runningDaemonHasNullCompletion() {
        val json = daemonTaskJson(DetachedTaskStatus(task(), running = true))
        assertTrue(json.getBoolean("running"))
        assertTrue(json.isNull("completed_at"))
        assertTrue(json.isNull("end_reason"))
    }

    /**
     * 已结束：`running=false`，且**必须**带结束时间与原因。
     *
     * 这就是原来缺的那两个键 —— 没有它们，模型只知道「没在跑」。
     */
    @Test
    fun finishedDaemonCarriesCompletionAndReason() {
        val json = daemonTaskJson(
            DetachedTaskStatus(
                task(completedAt = 1_700_000_500_000, endReason = "interrupted"),
                running = false,
            )
        )
        assertFalse(json.getBoolean("running"))
        assertEquals(1_700_000_500_000, json.getLong("completed_at"))
        assertEquals("interrupted", json.getString("end_reason"))
    }

    /** 字段名与命令任务一致：改名的代价是调用方悄悄读不到（null），不会报错。 */
    @Test
    fun fieldNamesMatchCommandTasks() {
        val json = daemonTaskJson(DetachedTaskStatus(task(), running = true))
        listOf("task_id", "running", "started_at", "completed_at", "end_reason")
            .forEach { key -> assertTrue("缺少字段 $key", json.has(key)) }
    }
}
