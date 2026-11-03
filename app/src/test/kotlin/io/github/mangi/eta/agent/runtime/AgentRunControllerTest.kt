package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerTest {
    @Test
    fun cancellationWakesLongRetryWait() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            started.countDown()
            try {
                controller.awaitRetryDelay(60_000L)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun modelSwitchWakesRetryWaitImmediately() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val switched = AtomicReference<Boolean?>()
        val worker = thread(name = "controller-retry-switch-test") {
            started.countDown()
            switched.set(runCatching { controller.awaitRetryDelay(60_000L) }.getOrNull())
            finished.countDown()
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // 已经真的在等：不切配置就不会提前返回（否则下面的断言证明不了任何事）。
            assertFalse(finished.await(150, TimeUnit.MILLISECONDS))

            assertTrue(controller.applyModelConfig(modelConfig("next-model")))

            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(true, switched.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun retryWaitWithoutModelSwitchRunsToDeadline() {
        val controller = AgentRunController()

        assertFalse(controller.awaitRetryDelay(60L))
    }

    @Test
    fun modelSwitchAfterCancelIsRejected() {
        val controller = AgentRunController()
        controller.cancel()

        assertFalse(controller.applyModelConfig(modelConfig("next-model")))
    }

    private fun modelConfig(model: String) = AgentModelClient.ModelConfig(
        providerId = "test",
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        model = model,
        systemPrompt = "",
    )

    @Test
    fun steeringIsQueuedOneAtATimeWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }

        assertTrue(controller.steer("  first  "))
        assertFalse(controller.steer("   "))
        assertTrue(controller.steer("second"))

        assertEquals(0, cancellations.get())
        assertEquals("first", controller.pollSteeringMessage())
        assertEquals("second", controller.pollSteeringMessage())
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun finalPollAtomicallySealsSteering() {
        val controller = AgentRunController()

        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun cancelClearsSteeringAndCancelsEachResourceOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }
        controller.steer("pending")

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancellations.get())
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.steer("late"))
    }

    @Test
    fun closedBindingIsNotCancelledLater() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        val binding = controller.register { cancellations.incrementAndGet() }

        binding.close()
        controller.cancel()

        assertEquals(0, cancellations.get())
    }

    @Test
    fun resourceRegisteredAfterCancellationIsCancelledExactlyOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.cancel()

        val binding = controller.register { cancellations.incrementAndGet() }
        controller.cancel()
        binding.close()

        assertEquals(1, cancellations.get())
    }

    @Test
    fun pauseStateIsObservableAndClearedByCancel() {
        val controller = AgentRunController()
        assertFalse(controller.isPaused)
        controller.pause()
        assertTrue(controller.isPaused)
        controller.resume()
        assertFalse(controller.isPaused)
        controller.pause()
        controller.cancel()
        assertFalse(controller.isPaused)
    }

    @Test
    fun pauseBlocksUntilResume() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-pause-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun steeringDoesNotResumeAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-steering-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.steer("补充条件"))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("补充条件", controller.pollSteeringMessage())
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelWhilePausedWakesWorkerWithCancellation() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-cancel-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun childRunStopsAtParentPauseCheckpoint() {
        val parent = AgentRunController()
        val child = AgentRunController(parent)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        parent.pause()
        val worker = thread(name = "controller-child-pause-test") {
            entered.countDown()
            runCatching(child::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(child.isPaused)
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            parent.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            parent.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun parentCancelStopsChildChecker() {
        val parent = AgentRunController()
        val child = AgentRunController(parent)
        parent.cancel()

        val failure = runCatching { child.throwIfCancelled() }.exceptionOrNull()
        assertTrue(failure is AgentRunCancelledException)
    }
}
