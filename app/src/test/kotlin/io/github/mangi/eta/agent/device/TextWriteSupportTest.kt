package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写后读回策略（fix185）。
 *
 * 守的是三件此前只能靠读代码确认的事：
 * 1. [ReadbackWindow] 的等待语义（命中立即返回、主线程不阻塞、不超过上限）；
 * 2. 超时常量之间的不变量（观察窗口必须覆盖主线程等待）；
 * 3. 两种「准备阶段失败」的文案不能合并成一句。
 */
class TextWriteSupportTest {

    /** 虚拟时间：睡眠只推进时钟并记账，不真睡 —— 测试必须秒级以内跑完。 */
    private class FakeTime {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        fun clock(): Long = now

        fun sleeper(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    private fun window(timeoutMs: Long, intervalMs: Long, time: FakeTime) =
        ReadbackWindow(timeoutMs, intervalMs, time::clock, time::sleeper)

    @Test
    fun `a first-try match returns immediately without sleeping`() {
        val time = FakeTime()
        var calls = 0
        assertTrue(window(300, 40, time).await(isMainThread = false) { calls++; true })
        assertEquals(1, calls)
        assertTrue(time.sleeps.isEmpty())
        assertEquals(0L, time.now)
    }

    @Test
    fun `the main thread gets exactly one attempt and never blocks`() {
        // 主线程上等待改变不了结果（Compose 的提交同样排在主线程），只会把界面卡住。
        val time = FakeTime()
        var calls = 0
        assertFalse(window(300, 40, time).await(isMainThread = true) { calls++; false })
        assertEquals(1, calls)
        assertTrue(time.sleeps.isEmpty())
        assertEquals(0L, time.now)
    }

    @Test
    fun `a later match inside the window succeeds`() {
        val time = FakeTime()
        var calls = 0
        assertTrue(window(300, 40, time).await(isMainThread = false) { calls++; calls == 5 })
        assertEquals(5, calls)
        assertEquals(listOf(40L, 40L, 40L, 40L), time.sleeps)
    }

    @Test
    fun `waiting never exceeds the timeout`() {
        // 100ms 窗口、40ms 间隔：最后一段只睡剩余 20ms，不会越过截止点再白等一个间隔。
        val time = FakeTime()
        var calls = 0
        assertFalse(window(100, 40, time).await(isMainThread = false) { calls++; false })
        assertEquals(listOf(40L, 40L, 20L), time.sleeps)
        assertEquals(100L, time.sleeps.sum())
        assertEquals(4, calls)
    }

    @Test
    fun `a zero timeout still makes exactly one attempt`() {
        val time = FakeTime()
        var calls = 0
        assertFalse(window(0, 40, time).await(isMainThread = false) { calls++; false })
        assertEquals(1, calls)
        assertTrue(time.sleeps.isEmpty())
    }

    @Test
    fun `the scroll observation window covers the main thread wait`() {
        // 这条不变量一旦反了：滚动观察会在拿到信号前过期，表现为「滚动成功了却报没滚动」。
        assertTrue(
            AgentAccessibilityTimeouts.SCROLL_EVENT_OBSERVATION_MS >=
                AgentAccessibilityTimeouts.MAIN_SYNC_SECONDS * 1_000L,
        )
        assertEquals(
            AgentAccessibilityTimeouts.MAIN_SYNC_SECONDS * 1_000L +
                AgentAccessibilityTimeouts.SCROLL_GESTURE_MS +
                AgentAccessibilityTimeouts.GESTURE_CALLBACK_GRACE_MS +
                AgentAccessibilityTimeouts.SCROLL_VERIFY_MS,
            AgentAccessibilityTimeouts.SCROLL_EVENT_OBSERVATION_MS,
        )
        // 读回窗口至少容得下一个轮询间隔，否则每次写入都白等一轮就放弃。
        assertTrue(AgentAccessibilityTimeouts.TEXT_READBACK_MS > 0L)
        assertTrue(
            AgentAccessibilityTimeouts.TEXT_READBACK_INTERVAL_MS <
                AgentAccessibilityTimeouts.TEXT_READBACK_MS,
        )
        // 上限别涨回去：这是每次文本写入都要等的真实时间。
        assertTrue(AgentAccessibilityTimeouts.TEXT_READBACK_MS <= 1_000L)
    }

    @Test
    fun `the two preparation failures say different things`() {
        // 两者都没有写过文本（准备阶段只读），但原因不同：
        // 一种连「找节点」都没开始，另一种是主线程里抛了错。合并成一句就把现场丢了。
        assertNotEquals(
            TextWriteFailures.notStartedMessage(),
            TextWriteFailures.outcomeUnknownMessage(),
        )
        assertEquals("SERVICE_TIMEOUT", TextWriteFailures.CODE)
        assertTrue(TextWriteFailures.notStartedMessage().contains("未执行"))
        assertTrue(TextWriteFailures.outcomeUnknownMessage().contains("文本未写入"))
        assertTrue(TextWriteFailures.notStartedMessage().contains("请重新观察后重试"))
        assertTrue(TextWriteFailures.outcomeUnknownMessage().contains("请重新观察后重试"))
    }

    @Test
    fun `interval must be positive`() {
        // 0 间隔会让重试变成忙等，把「等 300ms」变成「占满 300ms 的 CPU」。
        var rejected = false
        try {
            ReadbackWindow(300, 0, { 0L }, { })
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
