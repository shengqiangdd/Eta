package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    private fun task(sessionScope: String? = null) = DetachedTask(
        id = "job_12345678",
        pid = 42,
        token = "token",
        command = "echo hi",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/job_12345678.log",
        startedAt = 1_700_000_000_000,
        commandTask = true,
        description = "测试",
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
}
