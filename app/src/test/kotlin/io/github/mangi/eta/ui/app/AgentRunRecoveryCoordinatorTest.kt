package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeProcessIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun sameProcessActiveCheckpointIsReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active", owner = AgentRuntimeProcessIdentity.id)),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun terminalResultTakesPrecedenceAndKeepsCheckpointForTraceReplay() {
        val checkpoint = checkpoint("run-complete")
        val completed = completed("run-complete")

        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint),
            completedRuns = listOf(completed),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-complete",
            locallyObservedRunId = null,
        )

        assertEquals(completed, plan.completed.single().result)
        assertEquals(checkpoint, plan.completed.single().checkpoint)
        assertNull(plan.reattach)
        assertTrue(plan.interrupted.isEmpty())
    }

    /**
     * 多会话并发：primary 之外还有一个同进程的 run 在跑。
     *
     * 旧实现会把它判成「被系统中断」并 `remove()` 掉在途记录，它下一次写事件就撞
     * `SQLITE_CONSTRAINT_FOREIGNKEY(787)`，整轮已经跑完的对话被判失败（2026-10-24 真机事故）。
     */
    @Test
    fun concurrentRunIsNotInterruptedWhileAnotherRunIsActive() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-primary"), checkpoint("run-background")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-primary",
            locallyObservedRunId = null,
        )

        assertEquals("run-primary", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    /**
     * primary 结束会把 activeRunId 置空，但后台会话仍在执行。
     *
     * 2026-10-24 真机事故：用户在一个会话里发一句话后，另一个仍在跑的会话被显示成
     * 「已停止」，checkpoint 同时被删 —— 因为 `activeRunId == null` 被当成「没有 run 在跑」。
     * 服务端现在会回报仍在执行的总数，只要它大于 0 就不能判中断。
     */
    @Test
    fun backgroundRunSurvivesAfterPrimaryEnded() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-background")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = null,
            locallyObservedRunId = null,
            activeRunCount = 1,
        )

        assertNull(plan.reattach)
        assertTrue(plan.interrupted.isEmpty())
    }

    /** 只有服务端明确回报「一个 run 都不剩」时，同进程残留的 checkpoint 才判中断。 */
    @Test
    fun noRunningRunInterruptsStaleCheckpoint() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = null,
            locallyObservedRunId = null,
            activeRunCount = 0,
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertNull(plan.reattach)
    }

    @Test
    fun inactiveCheckpointIsInterruptedButLocallyObservedRunIsIgnored() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale"), checkpoint("run-local")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = null,
            locallyObservedRunId = "run-local",
            activeRunCount = 0,
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertNull(plan.reattach)
    }

    @Test
    fun unavailableRuntimeLeavesUnresolvedCheckpointUntouched() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunId = null,
            locallyObservedRunId = null,
        )

        assertTrue(plan.interrupted.isEmpty())
        assertNull(plan.reattach)
    }

    @Test
    fun knownActiveRunCanReattachEvenWhenTerminalQueryFailed() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active"), checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun checkpointFromPreviousProcessIsInterruptedEvenWhenRuntimeIsUnavailable() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-old", owner = "previous-process")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunId = null,
            locallyObservedRunId = null,
            currentOwnerId = "current-process",
        )

        assertEquals(listOf("run-old"), plan.interrupted.map { it.runId })
        assertNull(plan.reattach)
    }

    /**
     * 终态事件已落盘 = AgentLoop 跑完了，只是结果没来得及发布。
     * 再判「被系统中断」就会在完整回答后面补一条「任务已中断」。
     */
    @Test
    fun finishedCheckpointIsNotTreatedAsInterrupted() {
        val finished = checkpoint("run-finished").copy(
            events = listOf(AgentEvent.RunFinished(round = 2, contentChars = 12)),
        )
        val running = checkpoint("run-running").copy(
            events = listOf(AgentEvent.RoundStarted(round = 2, messageCount = 12)),
        )

        assertTrue(AgentRunRecoveryCoordinator.finishedBeforeResult(finished))
        assertFalse(AgentRunRecoveryCoordinator.finishedBeforeResult(running))
    }

    private fun checkpoint(
        runId: String,
        owner: String = AgentRuntimeProcessIdentity.id,
        events: List<AgentEvent> = emptyList(),
    ) = AgentRunCheckpointStore.Checkpoint(
        runId = runId,
        ownerInstanceId = owner,
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        events = events,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun completed(runId: String) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = 3L,
    )
}
