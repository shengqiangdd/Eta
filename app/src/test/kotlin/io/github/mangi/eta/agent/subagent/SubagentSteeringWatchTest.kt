package io.github.mangi.eta.agent.subagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentSteeringWatchTest {
    @Test
    fun pendingSteeringCancelsTheChildImmediately() {
        val cancelled = CountDownLatch(1)
        val watch = SubagentSteeringWatch(
            hasPendingSteering = { true },
            cancelChild = { cancelled.countDown() },
            pollMillis = 10L,
        )
        try {
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            assertTrue(watch.wasSteered)
        } finally {
            watch.close()
        }
    }

    @Test
    fun closeStopsPollingWithoutCancellingTheChild() {
        val cancelled = AtomicBoolean(false)
        val watch = SubagentSteeringWatch(
            hasPendingSteering = { false },
            cancelChild = { cancelled.set(true) },
            pollMillis = 10L,
        )
        watch.close()
        Thread.sleep(50L)
        assertFalse(cancelled.get())
        assertFalse(watch.wasSteered)
    }
}
