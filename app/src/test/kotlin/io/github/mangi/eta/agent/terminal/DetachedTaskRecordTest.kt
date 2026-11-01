package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务记录的会话归属字段（`session_scope`）序列化。
 *
 * 为什么单独守：这个字段决定「任务结束后谁有权自动续跑」。它一旦读错（例如旧记录被
 * 解析成空串而不是 null），所有旧任务都会被当成「属于某个会话」，自动续跑就会在
 * 用户没看着的会话里花额度 —— 界面看不出任何异常。
 *
 * 兼容性要求：旧记录没有这个键，必须照常解析（不能抛异常、不能整条丢掉）。
 */
class DetachedTaskRecordTest {

    private fun task(
        sessionScope: String? = null,
        commandTask: Boolean = true,
        completedAt: Long? = null,
    ) = DetachedTask(
        id = "job_12345678",
        pid = 42,
        token = "token",
        command = "echo hi",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/job_12345678.log",
        startedAt = 1_700_000_000_000,
        commandTask = commandTask,
        description = "测试",
        completedAt = completedAt,
        sessionScope = sessionScope,
    )

    @Test
    fun sessionScopeSurvivesRoundTrip() {
        val restored = task("conv-abc").toRecordJson().toDetachedTask()
        assertEquals("conv-abc", restored.sessionScope)
        // 其余字段不能被新字段挤错位（记录是按位置读出来的旧格式）。
        assertEquals("job_12345678", restored.id)
        assertEquals(42L, restored.pid)
        assertEquals("echo hi", restored.command)
        assertEquals("测试", restored.description)
        assertTrue(restored.commandTask)
    }

    @Test
    fun nullSessionScopeIsWrittenAsJsonNull() {
        val json = task(null).toRecordJson()
        assertTrue(json.isNull("session_scope"))
        assertNull(json.toDetachedTask().sessionScope)
    }

    /** 旧记录：没有这个键。用真实旧格式构造，确保解析路径不被新键影响。 */
    @Test
    fun recordWithoutSessionScopeKeyParsesAsNoOwner() {
        val legacy = task("conv-abc").toRecordJson().apply { remove("session_scope") }
        val restored = legacy.toDetachedTask()
        assertNull(restored.sessionScope)
        assertEquals("job_12345678", restored.id)
    }

    /** 空串与字面 "null" 都按「没有归属」：这两种值在历史记录里都可能出现。 */
    @Test
    fun blankOrLiteralNullSessionScopeParsesAsNoOwner() {
        assertNull(task("").toRecordJson().toDetachedTask().sessionScope)
        val literal = task(null).toRecordJson().put("session_scope", "null")
        assertNull(literal.toDetachedTask().sessionScope)
        val blank = task(null).toRecordJson().put("session_scope", "   ")
        assertNull(blank.toDetachedTask().sessionScope)
    }

    /** 记录文件里可能残留其它字段；多出来的键不能影响解析。 */
    @Test
    fun unknownKeysAreIgnored() {
        val json: JSONObject = task("conv-xyz").toRecordJson().put("future_field", 7)
        assertEquals("conv-xyz", json.toDetachedTask().sessionScope)
    }

    /**
     * 探活确认死亡后必须补 `completed_at`。
     *
     * 原来只有命令任务会被补（`list()` 里 `if (task.commandTask && !running)`）；守护任务
     * 死了只返回 `running=false` 而记录不变 —— 记录文件永远停在「运行中」，任务结束事件、
     * 卡片、自动续跑都看不到它结束。
     */
    @Test
    fun deadTaskGetsMarkedCompleted() {
        assertTrue(shouldMarkCompleted(task(commandTask = false), probeDead = true))
        // 命令任务走同一条判定（保留原有分支的语义，只是把「该不该记」抽出来了）。
        assertTrue(shouldMarkCompleted(task(commandTask = true), probeDead = true))
    }

    /** 探活失败 / 进程还活着：不能记结束 —— 写上去就再也不能认领了。 */
    @Test
    fun aliveTaskIsNotMarkedCompleted() {
        assertFalse(shouldMarkCompleted(task(commandTask = false), probeDead = false))
    }

    /** 已经结束的记录不重写时间：重复 `list()` 不能把 `completed_at` 一直往后推。 */
    @Test
    fun finishedTaskIsNotRemarked() {
        assertFalse(shouldMarkCompleted(task(completedAt = 1_700_000_500_000), probeDead = true))
    }

    /**
     * 探活只在「有未结束的守护任务」时才该起 shell。
     *
     * 命令任务有完成线程，无需巡检；没任务时更不能隔几分钟就 fork 一个 shell ——
     * 这台设备的亮屏 CPU 本就敏感。
     */
    @Test
    fun probeOnlyNeededForUnfinishedDaemons() {
        assertFalse(needsDaemonProbe(emptyList()))
        // 只有未结束的命令任务：不需要巡检（完成线程会写 completed_at）。
        assertFalse(needsDaemonProbe(listOf(task(commandTask = true))))
        // 已结束的守护任务：不需要。
        assertFalse(needsDaemonProbe(listOf(task(commandTask = false, completedAt = 1_700_000_500_000))))
        // 有未结束的守护任务：需要。
        assertTrue(needsDaemonProbe(listOf(task(commandTask = false))))
    }
}
