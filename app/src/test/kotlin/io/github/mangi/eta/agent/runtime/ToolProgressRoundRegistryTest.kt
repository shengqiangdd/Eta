package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行中进度的轮次登记表。
 *
 * 最要紧的一条是 [lookupFromAnotherThreadWorks]：它对应一个真实缺陷 ——
 * 进度从 ACP 的 `eta-acp-*` HandlerThread 上报，而轮次原先存在编排线程的
 * ThreadLocal 里，读线程恒读到 0，全部进度被"轮次未知"分支丢弃。
 */
class ToolProgressRoundRegistryTest {
    @Test
    fun lookupFromAnotherThreadWorks() {
        val registry = ToolProgressRoundRegistry()
        registry.enter(round = 3, toolCallId = "call-acp")
        val found = IntArray(1)
        val latch = CountDownLatch(1)
        Thread {
            found[0] = registry.roundOf("call-acp")
            latch.countDown()
        }.start()
        assertTrue("跨线程查询超时", latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, found[0])
    }

    @Test
    fun lookupBeforeEnterAndAfterExitReturnsZero() {
        val registry = ToolProgressRoundRegistry()
        // 登记前来报：工具还没开始，轮次无从得知。
        assertEquals(0, registry.roundOf("call-acp"))
        registry.enter(round = 1, toolCallId = "call-acp")
        assertEquals(1, registry.roundOf("call-acp"))
        registry.exit("call-acp")
        // 结束后的迟到增量：挂回卡片会制造"工具复活"的假象。
        assertEquals(0, registry.roundOf("call-acp"))
        assertEquals(0, registry.size())
    }

    @Test
    fun concurrentToolsKeepTheirOwnRounds() {
        val registry = ToolProgressRoundRegistry()
        registry.enter(round = 1, toolCallId = "call-a")
        registry.enter(round = 1, toolCallId = "call-b")
        registry.enter(round = 2, toolCallId = "call-c")
        assertEquals(1, registry.roundOf("call-a"))
        assertEquals(1, registry.roundOf("call-b"))
        assertEquals(2, registry.roundOf("call-c"))
        registry.exit("call-a")
        // 注销一个不影响其余。
        assertEquals(0, registry.roundOf("call-a"))
        assertEquals(2, registry.roundOf("call-c"))
        assertEquals(2, registry.size())
    }

    @Test
    fun invalidEntriesAreIgnoredRatherThanPoisoningTheMap() {
        val registry = ToolProgressRoundRegistry()
        registry.enter(round = 0, toolCallId = "call-zero")
        registry.enter(round = -1, toolCallId = "call-negative")
        registry.enter(round = 1, toolCallId = "")
        assertEquals(0, registry.roundOf("call-zero"))
        assertEquals(0, registry.roundOf("call-negative"))
        assertEquals(0, registry.roundOf(""))
        assertEquals(0, registry.size())
        // 空 id 的注销不能误伤别人。
        registry.enter(round = 4, toolCallId = "call-real")
        registry.exit("")
        assertEquals(4, registry.roundOf("call-real"))
    }

    @Test
    fun sameToolCallIdReEnteringOverwritesInsteadOfDuplicating() {
        val registry = ToolProgressRoundRegistry()
        registry.enter(round = 1, toolCallId = "call-x")
        registry.enter(round = 7, toolCallId = "call-x")
        assertEquals(7, registry.roundOf("call-x"))
        assertEquals(1, registry.size())
    }
}
