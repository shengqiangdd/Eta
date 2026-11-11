package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量卡片只能统计内存里的消息，必须如实说明范围。
 *
 * 大会话不会全部载入（真机 28 个会话里 21 个超过窗口，最大的 49036 条
 * 而窗口只有 500 条）。不标注的话，卡片上那串「输入 N tok」会被当成
 * 整个会话的成本 —— 实测偏差可达两个数量级。
 *
 * 反过来，多数会话本来就没被截断，每张卡片都挂一句「可能不全」是噪音。
 * 所以只在真的截断时才说。
 */
class UsageScopeTest {

    private fun assistant(output: Int = 100) =
        AgentMessageUi(id = "a", content = "答", usage = TokenUsageUi(outputTokens = output))

    @Test
    fun `完整会话不标注部分范围`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            messagesWindowStart = 0,
        )
        assertFalse("没被截断就不该出现范围提示", usage.partialScope)
    }

    @Test
    fun `更早的消息未加载时标注部分范围`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            messagesWindowStart = 1200,
        )
        assertTrue("从第 1200 条开始加载，说明前面还有 1200 条没算进去", usage.partialScope)
    }

    @Test
    fun `从第一条开始但可能仍有更晚的未加载`() {
        // windowStart == 0 表示「起点就是会话开头」，但用户可能还没往下翻到最新。
        // 这种不算「统计了部分」—— 缺的是还没发生的消息，不是被裁掉的历史。
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            messagesWindowStart = 0,
        )
        assertFalse(usage.partialScope)
    }

    @Test
    fun `默认值保持不标注，不影响既有调用方`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant()))
        assertFalse(
            "新参数必须有安全的默认值：多数调用方拿不到窗口信息",
            usage.partialScope,
        )
    }

    @Test
    fun `范围标记不影响统计本身`() {
        val full = AgentSessionUsageUi.of(listOf(assistant(300), assistant(200)))
        val partial = AgentSessionUsageUi.of(
            listOf(assistant(300), assistant(200)),
            messagesWindowStart = 4000,
        )
        assertEquals("截断与否只影响提示，不该改变已加载消息的统计", full.outputTokens, partial.outputTokens)
        assertEquals(full.totalOutputTokens, partial.totalOutputTokens)
    }
}
