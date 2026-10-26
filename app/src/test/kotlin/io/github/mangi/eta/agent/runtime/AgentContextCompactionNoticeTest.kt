package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩气泡文案：Runtime 已经算好的数字必须落到用户眼前。
 *
 * 真实踩点：完成态原来写死成「上下文已压缩，用量将在下一次模型响应后更新」，
 * 把 detail 里的「摘要 X 字符；上下文 A → B 字符」整段丢掉了 ——
 * 用户点完压缩看不出省了多少；慢的时候（真机实测一次 44.9 秒）也看不到耗时。
 *
 * 纯 JVM 可跑：这里只碰 data class 的字符串拼装，不依赖 Android。
 */
class AgentContextCompactionNoticeTest {
    @Test
    fun startedKeepsRunningText() {
        val event = AgentEvent.ContextCompaction("op", AgentEvent.ContextCompaction.PHASE_STARTED)
        assertEquals(AgentEvent.ContextCompaction.RUNNING_DETAIL, event.displayMessage)
    }

    @Test
    fun completedShowsDetailAndDuration() {
        val text = AgentEvent.ContextCompaction(
            operationId = "op",
            phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
            detail = "摘要 1,204 字符；上下文 84,000 → 21,000 字符",
            charsBefore = 84_000,
            charsAfter = 21_000,
            durationMs = 44_900,
        ).displayMessage
        assertTrue(text, text.contains("84,000 → 21,000"))
        assertTrue(text, text.contains("用时 44.9 秒"))
        // 压缩后的 token 只有下一次响应才有，不能说成"已更新"。
        assertTrue(text, text.contains("待下一次响应"))
        assertFalse(text, text.contains("已更新"))
    }

    @Test
    fun completedWithoutDetailFallsBackToNeutralText() {
        val text = AgentEvent.ContextCompaction("op", AgentEvent.ContextCompaction.PHASE_COMPLETED).displayMessage
        assertEquals("上下文已压缩；压缩后用量待下一次响应", text)
    }

    @Test
    fun subSecondDurationStaysQuiet() {
        val text = AgentEvent.ContextCompaction(
            "op", AgentEvent.ContextCompaction.PHASE_COMPLETED, durationMs = 999,
        ).displayMessage
        assertFalse(text, text.contains("用时"))
    }

    @Test
    fun longDurationReadsAsMinutes() {
        val text = AgentEvent.ContextCompaction(
            "op", AgentEvent.ContextCompaction.PHASE_COMPLETED, durationMs = 125_000,
        ).displayMessage
        assertTrue(text, text.contains("用时 2 分 5 秒"))
    }

    @Test
    fun failedKeepsReasonAndDuration() {
        val text = AgentEvent.ContextCompaction(
            operationId = "op",
            phase = "failed",
            reasonCode = "CONTEXT_SUMMARY_FAILED",
            detail = "模型未返回摘要",
            durationMs = 44_900,
        ).displayMessage
        assertTrue(text, text.contains("上下文压缩失败：模型未返回摘要"))
        assertTrue(text, text.contains("原始上下文已保留"))
        assertTrue(text, text.contains("用时 44.9 秒"))
    }

    @Test
    fun skippedKeepsReasonWithoutDuration() {
        val text = AgentEvent.ContextCompaction(
            operationId = "op",
            phase = AgentEvent.ContextCompaction.PHASE_SKIPPED,
            detail = "上下文只有 1,796 字符，不值得付一次摘要请求。",
        ).displayMessage
        assertEquals("上下文只有 1,796 字符，不值得付一次摘要请求。", text)
    }
}
