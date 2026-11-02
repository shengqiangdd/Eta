package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiControlCoordinatorTest {
    @Test
    fun ownershipSpansActionsAndWaitersEnterInOrderAfterRelease() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bQueued = CountDownLatch(1)
        val cQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val c = coordinator.openRun("c", AgentRunController()) { if (it == GuiControlStatus.Waiting) cQueued.countDown() }
        val order = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstGeneration = a.withAction { it }
            val bResult = pool.submit { b.withAction { order += "b"; b.release() } }
            await(bQueued)
            val cResult = pool.submit { c.withAction { order += "c"; c.release() } }
            await(cQueued)
            assertEquals(firstGeneration, a.withAction { it })
            assertTrue(order.isEmpty())
            a.release()
            bResult.get(2, TimeUnit.SECONDS)
            cResult.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("b", "c"), order)
            assertNull(coordinator.state.value.ownerRunId)
        } finally {
            a.close(); b.close(); c.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellationKeepsOwnershipUntilTheCurrentActionExits() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val aResult = pool.submit { a.withAction { entered.countDown(); await(drain) } }
            await(entered)
            val bResult = pool.submit { b.withAction { } }
            await(bQueued)
            controller.cancel()
            assertEquals("a", coordinator.state.value.ownerRunId)
            assertFalse(bResult.isDone)
            drain.countDown()
            aResult.get(2, TimeUnit.SECONDS)
            bResult.get(2, TimeUnit.SECONDS)
            assertEquals("b", coordinator.state.value.ownerRunId)
        } finally {
            drain.countDown()
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancelledWaiterCannotEnterOrBlockTheNextWaiter() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bController = AgentRunController()
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", bController) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            a.withAction { }
            val result = pool.submit { b.withAction { error("已取消的等待者不能执行") } }
            await(bQueued)
            bController.cancel()
            try {
                result.get(2, TimeUnit.SECONDS)
                error("应传播取消")
            } catch (failure: ExecutionException) {
                assertTrue(failure.cause is AgentRunCancelledException)
            }
            a.release()
            coordinator.openRun("c", AgentRunController()).use { c ->
                c.withAction { assertEquals("c", coordinator.state.value.ownerRunId) }
            }
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun userPauseSurvivesOwnerCancellationUntilExplicitResume() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val initialGeneration = a.withAction { it }
        val queued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) queued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(coordinator.pauseForUser("a"))
            assertTrue(coordinator.state.value.userPaused)
            val bResult = pool.submit<Long> { b.withAction { it } }
            await(queued)
            controller.cancel()
            assertNull(coordinator.state.value.ownerRunId)
            assertTrue(coordinator.state.value.userPaused)
            assertFalse(bResult.isDone)
            assertTrue(coordinator.resumeForUser("a"))
            assertTrue(bResult.get(2, TimeUnit.SECONDS) > initialGeneration)
            assertFalse(coordinator.state.value.userPaused)
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun releasingDuringUserPauseDoesNotLeaveTheOriginalRunPermanentlyPaused() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        coordinator.openRun("a", controller).use { a ->
            a.withAction { }
            coordinator.pauseForUser("a")
            a.release()
            assertTrue(controller.isPaused)
            assertTrue(coordinator.state.value.userPaused)
            assertNull(coordinator.state.value.ownerRunId)
            coordinator.resumeForUser("a")
            assertFalse(controller.isPaused)
            assertFalse(coordinator.state.value.userPaused)
        }
    }

    @Test
    fun pauseIsPublishedOnlyAfterTheActionDrainsAndResumeInvalidatesObservationGeneration() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Long> { a.withAction { entered.countDown(); await(drain); it } }
            await(entered)
            assertTrue(coordinator.pauseForUser("a"))
            assertFalse(coordinator.state.value.userPaused)
            drain.countDown()
            val oldGeneration = result.get(2, TimeUnit.SECONDS)
            assertTrue(coordinator.state.value.userPaused)
            assertTrue(coordinator.resumeForUser("a"))
            assertTrue(a.withAction { it } > oldGeneration)
        } finally {
            drain.countDown()
            a.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun differentDisplaysDoNotBlockEachOther() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // a 在主屏上一直不放手。
            val aResult = pool.submit {
                a.withAction(GuiControlCoordinator.MainScope) { entered.countDown(); await(drain) }
            }
            await(entered)
            val b = coordinator.openRun("b", AgentRunController())
            try {
                // b 在另一块虚拟屏上应该立刻进入，而不是排 a 的队。
                b.withAction(GuiControlCoordinator.displayScope(16)) {
                    assertEquals(
                        "b",
                        coordinator.state.value.owners[GuiControlCoordinator.displayScope(16)],
                    )
                }
                assertEquals("a", coordinator.state.value.ownerRunId)
                assertEquals("a", coordinator.state.value.owners[GuiControlCoordinator.MainScope])
            } finally {
                b.close()
            }
            drain.countDown()
            aResult.get(2, TimeUnit.SECONDS)
        } finally {
            drain.countDown()
            a.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun sameDisplayStillQueuesAcrossRuns() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) {
            if (it == GuiControlStatus.Waiting) bQueued.countDown()
        }
        val pool = Executors.newFixedThreadPool(2)
        val scope = GuiControlCoordinator.displayScope(16)
        try {
            val aResult = pool.submit {
                a.withAction(scope) { entered.countDown(); await(drain) }
                // 交还之后 B 才可能进来 —— 这个用例验的是「同一块屏排队」，不是「超时」。
                a.release()
            }
            await(entered)
            val bEntered = CountDownLatch(1)
            val bResult = pool.submit { b.withAction(scope) { bEntered.countDown() } }
            await(bQueued)
            assertFalse("同一块屏上不该并行进入", bResult.isDone)
            drain.countDown()
            aResult.get(2, TimeUnit.SECONDS)
            bResult.get(2, TimeUnit.SECONDS)
            assertEquals(0L, bEntered.count)
        } finally {
            drain.countDown()
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun waitingForABusyDisplayTimesOutInsteadOfHanging() {
        val coordinator = GuiControlCoordinator(waitTimeoutMs = 120L)
        val a = coordinator.openRun("a", AgentRunController())
        val scope = GuiControlCoordinator.MainScope
        a.withAction(scope) { }
        val b = coordinator.openRun("b", AgentRunController())
        val started = System.nanoTime()
        try {
            try {
                b.withAction(scope) { error("持锁期间不该进入") }
                error("应当超时")
            } catch (timeout: GuiControlCoordinator.GuiWaitTimeoutException) {
                assertEquals(scope, timeout.scope)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertTrue("超时过短，可能没真的等过：${elapsedMs}ms", elapsedMs >= 100L)
            }
            // 超时的等待者不能卡在队首：下一次自己拿锁要能立刻成功。
            a.release()
            b.withAction(scope) { }
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun releaseDropsEveryDisplayTheRunHeld() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        try {
            a.withAction(GuiControlCoordinator.MainScope) { }
            a.withAction(GuiControlCoordinator.displayScope(16)) { }
            assertEquals(2, coordinator.state.value.owners.size)
            a.release()
            assertTrue("交还操作权后不该还占着任何屏", coordinator.state.value.owners.isEmpty())
            assertNull(coordinator.state.value.ownerRunId)
        } finally {
            a.close()
        }
    }

    @Test
    fun userPauseCoversVirtualDisplayOwnershipToo() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        try {
            a.withAction(GuiControlCoordinator.displayScope(16)) { }
            assertTrue(coordinator.pauseForUser("a"))
            assertTrue(coordinator.state.value.userPaused)
            assertTrue(controller.isPaused)
            assertTrue(coordinator.resumeForUser("a"))
            assertFalse(controller.isPaused)
        } finally {
            a.close()
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("并发步骤未到达", latch.await(2, TimeUnit.SECONDS))
    }
}
