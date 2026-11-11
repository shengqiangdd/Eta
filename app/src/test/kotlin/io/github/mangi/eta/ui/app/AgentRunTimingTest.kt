package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首字延迟与解码耗时的采集时序。
 *
 * 这里模拟一条真实时间线：请求发出 → 首字到达 → 输出若干字 → 轮次结束。
 * 之前的实现有一个必然为 0 的 bug（起点在轮次结束才懒写入，与终点同一瞬间），
 * 就是靠这个测试能立刻发现的 —— 它断言的是**具体数值**，不是「不为 null」。
 */
class AgentRunTimingTest {

    /** 可控时钟：测试里手动推进。 */
    private class Clock(var now: Long = 0L) {
        fun advanceBy(ms: Long) { now += ms }
    }

    @Test
    fun `首字延迟是请求发出到首字的差，解码是首字到结束的差`() {
        val clock = Clock(1_000L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })

        // 请求发出 @1000
        var messages: List<AgentChatMessageUi> =
            projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), emptyList())

        // 首字到达 @1500 → 首字延迟应为 500ms
        clock.advanceBy(500)
        messages = projector.appendReasoningDelta("run", 1, 0, "首", messages)

        // 继续输出到 @3500 结束 → 解码耗时 2000ms
        clock.advanceBy(1_000)
        messages = projector.appendReasoningDelta("run", 1, 0, "续", messages)
        clock.advanceBy(1_000)
        messages = projector.finalizeThinking("run", messages)

        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("首字延迟必须从请求发出算起，不是从首字自己算", 500L, thinking.firstTokenMs)
        assertEquals(2_000L, thinking.decodeMs)
    }

    @Test
    fun `只有一次 delta 时解码耗时为 0 而不是负数`() {
        val clock = Clock(0L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> =
            projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), emptyList())
        clock.advanceBy(300)
        messages = projector.appendReasoningDelta("run", 1, 0, "一次说完", messages)
        messages = projector.finalizeThinking("run", messages)

        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals(300L, thinking.firstTokenMs)
        assertEquals(0L, thinking.decodeMs)
    }

    @Test
    fun `没有收到请求发出事件时不编造耗时`() {
        // 缺起点就应该是 null，不能退化成 0（0 会显示成「瞬间响应」）。
        val clock = Clock(5_000L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> = emptyList()
        messages = projector.appendReasoningDelta("run", 1, 0, "内容", messages)
        clock.advanceBy(100)
        messages = projector.finalizeThinking("run", messages)

        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals(null, thinking.firstTokenMs)
        assertEquals(null, thinking.decodeMs)
    }

    @Test
    fun `轮次 1 的起点不会被轮次 10 复用`() {
        // 消息 id 是 runId-thinking-<round>-<index>，前缀匹配必须带分隔符，
        // 否则 "-1-" 会命中 "-10-"，把不同轮的起点串起来。
        val clock = Clock(0L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> = emptyList()

        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), messages)
        clock.advanceBy(100)
        messages = projector.appendReasoningDelta("run", 1, 0, "第一轮", messages)

        // 轮次 10 单独建立起点，晚 1000ms
        clock.advanceBy(1_000)
        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(10), messages)
        clock.advanceBy(200)
        messages = projector.appendReasoningDelta("run", 10, 0, "第十轮", messages)
        messages = projector.finalizeThinking("run", messages)

        val byRound = messages.filterIsInstance<ThinkingMessageUi>().associateBy { it.id }
        val first = byRound.getValue("run-thinking-1-0")
        val tenth = byRound.getValue("run-thinking-10-0")
        assertEquals(100L, first.firstTokenMs)
        // 若前缀误匹配，这里会算出 1200（把轮次 1 的起点当成本轮起点）。
        assertEquals(200L, tenth.firstTokenMs)
    }

    @Test
    fun `清理后不残留起点，避免长会话累积`() {
        val clock = Clock(0L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> =
            projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), emptyList())
        clock.advanceBy(50)
        messages = projector.appendReasoningDelta("run", 1, 0, "内容", messages)
        projector.clearRun("run")

        // 清理后再来一轮同名 run：必须重新计时，不能沿用旧起点。
        clock.advanceBy(10_000)
        var again: List<AgentChatMessageUi> =
            projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), emptyList())
        clock.advanceBy(70)
        again = projector.appendReasoningDelta("run", 1, 0, "又一次", again)
        again = projector.finalizeThinking("run", again)

        val thinking = again.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("清理未生效：起点仍是上一轮的", 70L, thinking.firstTokenMs)
        assertTrue(thinking.firstTokenMs!! >= 0)
    }
}
