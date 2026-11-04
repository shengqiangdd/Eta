package io.github.mangi.eta.agent.subagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentStallWatchTest {
    @Test
    fun noProgressBeyondTheIdleWindowCancelsTheChild() {
        val cancelled = CountDownLatch(1)
        // 时间不走：起始即「已经 20 分钟没有事件」，无需真的等 10 分钟。
        val frozen = 10_000_000L
        val watch = SubagentStallWatch(
            cancelChild = { cancelled.countDown() },
            lastActivityAtMs = { frozen },
            startedAtMs = frozen,
            now = { frozen + SubagentStallWatch.DEFAULT_IDLE_TIMEOUT_MS },
            pollMillis = 10L,
        )
        try {
            assertTrue("停滞必须真的取消子控制器", cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(SubagentStallWatch.Reason.NO_PROGRESS, watch.reason)
        } finally {
            watch.close()
        }
    }

    @Test
    fun activityKeepsTheChildAliveUntilTheHardRuntimeCap() {
        val cancelled = CountDownLatch(1)
        val clock = AtomicLong(0L)
        val watch = SubagentStallWatch(
            cancelChild = { cancelled.countDown() },
            // 每次读都等于当前时刻：模拟「一直在动」，永远不满足无进展判据。
            lastActivityAtMs = { clock.get() },
            startedAtMs = 0L,
            now = { clock.get() },
            idleTimeoutMs = 1_000L,
            maxRuntimeMs = 5_000L,
            pollMillis = 10L,
        )
        try {
            Thread.sleep(60L)
            assertTrue("一直有进展时不能因为 idle 判据被误杀", watch.reason == null)
            clock.set(5_000L)
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(SubagentStallWatch.Reason.MAX_RUNTIME, watch.reason)
        } finally {
            watch.close()
        }
    }

    @Test
    fun closeStopsTheWatchdogWithoutCancellingAnything() {
        val cancelled = AtomicBoolean(false)
        val watch = SubagentStallWatch(
            cancelChild = { cancelled.set(true) },
            lastActivityAtMs = { 0L },
            startedAtMs = 0L,
            now = { 1L },
            pollMillis = 10L,
        )
        watch.close()
        Thread.sleep(50L)
        assertTrue(!cancelled.get())
        assertNull(watch.reason)
    }

    @Test
    fun evaluatePrefersTheHardCapAndTreatsNonPositiveTimeoutsAsDisabled() {
        // 总时长先判：即使刚好也在动，超了硬上限仍要收尾。
        assertEquals(
            SubagentStallWatch.Reason.MAX_RUNTIME,
            SubagentStallWatch.evaluate(
                nowMs = 100_000L,
                lastActivityMs = 100_000L,
                startedAtMs = 0L,
                idleTimeoutMs = 10L,
                maxRuntimeMs = 50_000L,
            ),
        )
        assertEquals(
            SubagentStallWatch.Reason.NO_PROGRESS,
            SubagentStallWatch.evaluate(
                nowMs = 100_000L,
                lastActivityMs = 0L,
                startedAtMs = 0L,
                idleTimeoutMs = 60_000L,
                maxRuntimeMs = 0L,
            ),
        )
        // 两个阈值都 <= 0 = 两条判据都关闭（用户/测试显式关掉时不能凭空收尾）。
        assertNull(
            SubagentStallWatch.evaluate(
                nowMs = 10_000_000L,
                lastActivityMs = 0L,
                startedAtMs = 0L,
                idleTimeoutMs = 0L,
                maxRuntimeMs = -1L,
            ),
        )
    }

    /** 默认阈值必须比模型请求的读取超时宽，否则正常的长请求会被判成卡住。 */
    @Test
    fun defaultIdleWindowIsWiderThanTheModelReadTimeout() {
        val readTimeout = io.github.mangi.eta.agent.model.AgentHttpClient.MODEL_READ_TIMEOUT_MS
        assertTrue(
            "idle 阈值 ${SubagentStallWatch.DEFAULT_IDLE_TIMEOUT_MS}ms 必须大于读取超时 ${readTimeout}ms",
            SubagentStallWatch.DEFAULT_IDLE_TIMEOUT_MS > readTimeout,
        )
    }
}
