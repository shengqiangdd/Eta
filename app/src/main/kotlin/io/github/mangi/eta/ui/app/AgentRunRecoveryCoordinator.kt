package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeProcessIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: AgentRunCheckpointStore.Checkpoint?,
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunId: String?,
        locallyObservedRunId: String?,
        /** 服务端仍在执行的 run 总数；旧版 Runtime 缺省按「可能还在跑」处理。 */
        activeRunCount: Int = AgentRuntimeWire.UNKNOWN_RUN_COUNT,
        currentOwnerId: String = AgentRuntimeProcessIdentity.id,
    ): Plan {
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId == locallyObservedRunId }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId == locallyObservedRunId || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        // checkpoint 的 ownerInstanceId 是进程级 UUID：不是当前进程写下的，说明上一进程已经死亡。
        // 这类 checkpoint 不可能再被当前 Runtime 重新附着，必须直接按“被系统中断”恢复轨迹；
        // 否则 Runtime 查询恰好不可用时，旧任务会静默卡住，用户看到的就是“会话断了”。
        val staleFromPreviousProcess = unresolved.filter { it.ownerInstanceId != currentOwnerId }
        val currentProcessUnresolved = unresolved.filter { it.ownerInstanceId == currentOwnerId }
        val active = currentProcessUnresolved
            .takeIf { activeStateKnown }
            ?.singleOrNull { it.runId == activeRunId }
        // 只有「Runtime 明确说此刻一个 run 都不剩」时，才能把本进程剩下的
        // checkpoint 判为中断。多会话并发时 `activeRunId` 只是 UI 正在看的那一个
        // （primary）：它结束会被置空，而后台 run 仍在跑，`activeRunId == null`
        // 不能当成「全部结束」，否则正在跑的会话会被当成中断停掉。
        //
        // 2026-10-24 真机事故：原实现用 `filterNot { it.runId == active?.runId }` 删掉
        // 「非 primary」的在途记录，另一个仍在后台跑的会话下一次写事件就撞外键
        // `SQLITE_CONSTRAINT_FOREIGNKEY(787)`，整轮已经跑完的对话被判失败。
        // 同一天的第二起：primary 结束、后台仍在跑时，`activeRunId` 被置空
        // → 后台会话被判中断（界面显示已停止）且 checkpoint 被 remove。
        // 真「进程死掉」场景 ownerInstanceId 会变，走 staleFromPreviousProcess。
        val interruptedCurrent =
            if (activeStateKnown && terminalStateKnown && activeRunCount <= 0) {
                currentProcessUnresolved
            } else {
                emptyList()
            }

        return Plan(
            completed = completed,
            reattach = active,
            interrupted = (staleFromPreviousProcess + interruptedCurrent).distinctBy { it.runId },
        )
    }

    /**
     * checkpoint 里已有终态事件 = AgentLoop 已经跑完，缺的只是结果发布（进程被杀在两步之间）。
     *
     * 这种 run 不能再判「被系统中断」：真机上用户会看到一段完整回答后面又跟一条
     * 「任务已中断」，而且每次重启都会再来一条。
     */
    fun finishedBeforeResult(checkpoint: AgentRunCheckpointStore.Checkpoint): Boolean =
        checkpoint.events.any { it is AgentEvent.RunFinished }

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
