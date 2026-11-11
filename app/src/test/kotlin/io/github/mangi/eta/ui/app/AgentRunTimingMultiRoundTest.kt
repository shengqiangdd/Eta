package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真实运行形态下的采集：**一次 run 里有多个轮次**（每轮一次请求，中间夹工具调用）。
 *
 * 之前那批测试只模拟「单轮、单请求」，于是下面这些情况一条都没覆盖到，
 * 而真机上恰好全是这种形态 —— 主包 4 条数据全是 0，canary 单轮那次却是对的。
 */
class AgentRunTimingMultiRoundTest {

    private class Clock(var now: Long = 0L) {
        fun advanceBy(ms: Long) { now += ms }
    }

    @Test
    fun `多轮各自的请求都建立起点，互不覆盖`() {
        val clock = Clock(1_000L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> = emptyList()

        // 第 1 轮：请求 @1000，首字 @1300
        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), messages)
        clock.advanceBy(300)
        messages = projector.appendReasoningDelta("run", 1, 0, "第一轮思考", messages)

        // 第 2 轮（工具调用后）：请求 @5000，首字 @5600
        clock.advanceBy(3_700)
        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(2), messages)
        clock.advanceBy(600)
        messages = projector.appendReasoningDelta("run", 2, 0, "第二轮思考", messages)

        messages = projector.finalizeThinking("run", messages)

        val byId = messages.filterIsInstance<ThinkingMessageUi>().associateBy { it.id }
        assertEquals(
            "第 1 轮的首字延迟被第 2 轮的起点覆盖了",
            300L,
            byId.getValue("run-thinking-1-0").firstTokenMs,
        )
        assertEquals(
            "第 2 轮的首字延迟算错",
            600L,
            byId.getValue("run-thinking-2-0").firstTokenMs,
        )
    }

    @Test
    fun `轮次键被后续轮次复用时不产生 0 值`() {
        // 真机上观察到的现象：first_token_ms=0。
        // coerceAtLeast(0) 会把「起点晚于首字」的负差值悄悄压成 0，
        // 于是界面显示「瞬间响应」——比不显示错得多。
        val clock = Clock(0L)
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { clock.now })
        var messages: List<AgentChatMessageUi> = emptyList()

        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), messages)
        clock.advanceBy(200)
        messages = projector.appendReasoningDelta("run", 1, 0, "思考", messages)
        // 同一轮又来一次请求（重试），起点被刷新到首字之后
        clock.advanceBy(5_000)
        messages = projector.applyEvent("run", AgentEvent.ProviderRequestStarted(1), messages)
        clock.advanceBy(100)
        messages = projector.finalizeThinking("run", messages)

        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        // 重试后本轮的首字应被重置；要么给出重试后的真实值，要么给 null。
        // 无论如何不能是 0 —— 0 表示「瞬间响应」，那不是事实。
        assertEquals(
            "重试后算出了 0，界面会显示成瞬间响应",
            null,
            thinking.firstTokenMs?.takeIf { it == 0L },
        )
    }
}
