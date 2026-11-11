package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 速度段的来源判定。
 *
 * 这段逻辑存在的意义是**消除沉默**：速度不显示时，用户分不清
 * 「这个会话没跑过」和「跑过但记录被回收了」。前者不该打扰，后者需要一句说明。
 *
 * 所以测试重点不是「有没有数字」，而是四种情况分别落到哪个状态。
 */
class SpeedSourceTest {

    private fun assistant(tokens: Int = 40) = AgentMessageUi(
        id = "a-1",
        content = "答",
        usage = TokenUsageUi(outputTokens = tokens),
    )

    private fun timing(firstTokenMs: Int? = 800, decodeMs: Int? = 2_000, tokens: Int? = 100) =
        RunTiming(
            firstTokenMs = firstTokenMs,
            decodeTokens = decodeMs,
            speedNumeratorTokens = tokens,
            speedTokensPerSecond = if (decodeMs != null && decodeMs > 0) tokens!! * 1000.0 / decodeMs else null,
        )

    @Test
    fun `追踪命中时来源是 Trace`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            runTiming = timing(),
            lookupStatus = TraceLookupStatus.Found,
        )
        assertEquals(SpeedSource.Trace, usage.speedSource)
        assertEquals(800, usage.lastFirstTokenMs)
        assertEquals(2_000, usage.lastDecodeMs)
        assertEquals(100, usage.lastOutputTokens)
    }

    @Test
    fun `跑过但没有记录时判定为已回收`() {
        // 唯一需要向用户解释的情况：数据存在过，但被清理了。
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            runTiming = null,
            lookupStatus = TraceLookupStatus.Missing,
        )
        assertEquals(SpeedSource.Recycled, usage.speedSource)
        assertNull(usage.lastFirstTokenMs)
    }

    @Test
    fun `空会话不判定为已回收`() {
        // 从未运行过的对话显示「记录已被清理」是凭空捏造的信息。
        // 判据必须是「确实有消息」，光凭「存储里有别人的记录」不够。
        val usage = AgentSessionUsageUi.of(
            messages = emptyList(),
            runTiming = null,
            lookupStatus = TraceLookupStatus.Missing,
        )
        assertEquals(SpeedSource.None, usage.speedSource)
    }

    @Test
    fun `存储不可用时不做任何解释`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            runTiming = null,
            lookupStatus = TraceLookupStatus.NoStore,
        )
        assertEquals(
            "存储异常属于内部状态，不该在这里教育用户",
            SpeedSource.None,
            usage.speedSource,
        )
    }

    @Test
    fun `没有会话 id 时保持 None`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant()),
            runTiming = null,
            lookupStatus = TraceLookupStatus.NoConversation,
        )
        assertEquals(SpeedSource.None, usage.speedSource)
    }

    @Test
    fun `追踪数据齐全时优先于消息上的旧值`() {
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant(tokens = 999)),
            runTiming = timing(firstTokenMs = 500, decodeMs = 1_000, tokens = 50),
            lookupStatus = TraceLookupStatus.Found,
        )
        assertEquals("追踪的首字优先", 500, usage.lastFirstTokenMs)
        assertEquals("追踪的解码耗时优先", 1_000, usage.lastDecodeMs)
        assertEquals("追踪的输出 token 优先", 50, usage.lastOutputTokens)
        assertEquals(SpeedSource.Trace, usage.speedSource)
    }

    @Test
    fun `追踪缺项时不应把三项混用成半新半旧`() {
        // 只给了首字、没给速度：首字可以是新的，速度不该拿旧 decode 硬凑。
        val usage = AgentSessionUsageUi.of(
            messages = listOf(assistant(tokens = 999)),
            runTiming = RunTiming(firstTokenMs = 700),
            lookupStatus = TraceLookupStatus.Found,
        )
        assertEquals(700, usage.lastFirstTokenMs)
        assertNull("速度三项不齐，不该出现速度", usage.lastDecodeMs)
        assertEquals(SpeedSource.Trace, usage.speedSource)
    }
}
