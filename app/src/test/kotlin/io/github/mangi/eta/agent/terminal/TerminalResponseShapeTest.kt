package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal` 返回体的体积约定。
 *
 * 真机实测：`echo x` 的输出是 **1 个字符**，完整返回 **230 字符**。
 * 其中 `status`/`timed_out`/`stdout_truncated`/`stderr_truncated`/`output_truncated`/`stderr`
 * 在正常退出时全是可推导的默认值，一场几十轮的会话要重复几百次。
 *
 * 这里把「什么该出现、什么不该出现」钉死，免得以后又顺手加回去。
 */
class TerminalResponseShapeTest {

    /** 复刻 `exec` 结果组装里「只在该出现时出现」的那部分。 */
    private fun build(
        exitCode: Int = 0,
        stdout: String = "x",
        stderr: String = "",
        stdoutTruncated: Boolean = false,
        stderrTruncated: Boolean = false,
    ): JSONObject {
        val status = when {
            exitCode == -2 -> "timed_out"
            exitCode == 0 -> "exited"
            else -> "failed"
        }
        return JSONObject()
            .put("ok", exitCode == 0)
            .put("exit_code", exitCode)
            .put("stdout", stdout)
            .apply {
                if (status != "exited") put("status", status)
                if (exitCode == -2) put("timed_out", true)
                if (stderr.isNotEmpty()) put("stderr", stderr)
                if (stdoutTruncated || stderrTruncated) {
                    if (stdoutTruncated) put("stdout_truncated", true)
                    if (stderrTruncated) put("stderr_truncated", true)
                    put("output_truncated", true)
                }
            }
    }

    @Test
    fun successfulCommandOmitsDerivableFields() {
        val result = build()

        assertTrue(result.getBoolean("ok"))
        assertEquals(0, result.getInt("exit_code"))
        assertFalse("正常退出不需要 status", result.has("status"))
        assertFalse("正常退出不需要 timed_out", result.has("timed_out"))
        assertFalse("stderr 为空时不该出现", result.has("stderr"))
        assertFalse("没截断就不该出现截断标记", result.has("stdout_truncated"))
        assertFalse(result.has("output_truncated"))
    }

    @Test
    fun failureStillSaysWhatHappened() {
        val result = build(exitCode = 1, stderr = "command not found")

        assertFalse(result.getBoolean("ok"))
        assertEquals("failed", result.getString("status"))
        assertEquals("command not found", result.getString("stderr"))
    }

    @Test
    fun timeoutIsExplicit() {
        val result = build(exitCode = -2)

        assertEquals("timed_out", result.getString("status"))
        assertTrue("超时必须显式标记，否则模型会以为命令没跑完是别的原因", result.getBoolean("timed_out"))
    }

    @Test
    fun truncationIsExplicitWhenItHappens() {
        val result = build(stdoutTruncated = true)

        assertTrue(result.getBoolean("stdout_truncated"))
        assertTrue("任一方向截断都要有总标记", result.getBoolean("output_truncated"))
        assertFalse("stderr 没截断就不该跟着标", result.has("stderr_truncated"))
    }

    @Test
    fun successPayloadIsSubstantiallySmallerThanTheOldShape() {
        val slim = build().toString()
        // 旧形状里这几个字段无条件存在
        val fat = JSONObject(slim)
            .put("status", "exited")
            .put("timed_out", false)
            .put("stderr", "")
            .put("stdout_truncated", false)
            .put("stderr_truncated", false)
            .put("output_truncated", false)
            .toString()

        assertTrue("瘦身后必须真的更小：slim=${slim.length} fat=${fat.length}", slim.length < fat.length)
    }
}
