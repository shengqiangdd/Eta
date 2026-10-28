package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 在途 run 的 UI 事件、完整脱敏 transcript 与模型上下文快照。
 * 三者用途独立；配置和密钥不落盘，敏感工具原始数据由会话 codec 过滤。
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val contextSnapshot: AgentContextSnapshot? = null,
        val operation: String = AgentRuntimeWire.OP_CHAT,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val rewriteTargetMessageId: String? = null,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        // 恢复日志是**辅助数据**：写不进去只该失去断点续跑能力，不该让 run 起不来。
        // 返回 false 时 create() 返回 null，run 照常执行。
        return runCatching {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(
                    RuntimeInFlightRunEntity(
                        runId = runId,
                        ownerInstanceId = ownerInstanceId,
                        operation = request.operation,
                        rewriteTargetMessageId = request.rewriteTargetMessageId,
                        handoffId = handoff.id,
                        handoffSource = handoff.source,
                        handoffPayload = handoff.payload,
                        dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }.onFailure { throwable ->
            AndroidAgentLogger.warn(
                "Agent runtime checkpoint start skipped: runId=$runId type=${throwable.safeLogType()}"
            )
        }.isSuccess
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val durableEvent = event.recoveryProjection() ?: return
        runBlocking(Dispatchers.IO) {
            runCatching {
                EtaDatabase.get(context.applicationContext).runtimeRunDao().appendInFlightEvent(
                    event = RuntimeInFlightEventEntity(
                        runId = runId,
                        sortIndex = sortIndex,
                        eventJson = AgentEventJsonCodec.encode(durableEvent),
                    ),
                    updatedAt = now,
                )
            }.onFailure { throwable ->
                // `runtime_inflight_events.run_id` 有外键指向 `runtime_inflight_runs`，
                // 而 Room 2.8.5 生成的 `EtaDatabase_Impl.onOpen` 会执行
                // `PRAGMA foreign_keys = ON`（外键**是开着的**）。父行一旦被结果 ACK /
                // 中断恢复清掉，这里再写就抛 SQLITE_CONSTRAINT_FOREIGNKEY(787)。
                // 这条路径原先没有保护：一个恢复日志写入失败会把**整轮已经成功的对话**
                // 判成失败，用户看到的就是「会话跑着跑着自己结束了」。
                AndroidAgentLogger.warn(
                    "Agent runtime checkpoint append skipped: runId=$runId " +
                        "sortIndex=$sortIndex type=${throwable.safeLogType()}"
                )
            }
        }
    }

    /** 返回所有未确认 run；是否 active 或已完成由恢复协调器结合 Runtime 状态判断。 */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        contextSnapshot = AgentContextSnapshot.decode(stored.run.contextSnapshotJson),
                        operation = stored.run.operation,
                        rewriteTargetMessageId = stored.run.rewriteTargetMessageId,
                        transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.decodeTranscript(stored.run.transcriptJson)),
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun saveTranscript(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao()
                .updateTranscript(runId, AgentConversationCodec.encodeTranscriptForStorage(transcript))
        }
    }

    fun saveContext(context: Context, runId: String, snapshot: AgentContextSnapshot) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().updateContextSnapshot(runId, snapshot.encode())
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
