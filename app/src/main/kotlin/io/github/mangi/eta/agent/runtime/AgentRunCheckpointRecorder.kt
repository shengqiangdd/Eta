package io.github.mangi.eta.agent.runtime

import android.content.Context

/** 将高频文本增量合并后写入 checkpoint，结构化边界则同步落盘。 */
internal class AgentRunCheckpointRecorder private constructor(
    context: Context,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private val appContext = context.applicationContext
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    /** 同一工具的上一条已落盘进度；用于把连续进度折叠成一条，理由见 [appendProgress]。 */
    private var lastProgress: AgentEvent.ToolProgress? = null
    private var lastFlushNanos = nanoTime()

    fun accept(event: AgentEvent) {
        val checkpointEvent = event.recoveryProjection() ?: return
        if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
            val pending = pendingDelta
            if (
                pending != null &&
                pending.round == checkpointEvent.round &&
                pending.kind == checkpointEvent.kind &&
                pending.index == checkpointEvent.index
            ) {
                pendingDelta = pending.copy(
                    deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                    delta = pending.delta + checkpointEvent.delta,
                )
            } else {
                flushPendingDelta()
                pendingDelta = checkpointEvent
            }
            val elapsed = nanoTime() - lastFlushNanos
            if (
                pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                elapsed >= MAX_BUFFERED_DELTA_NANOS
            ) {
                flushPendingDelta()
            }
            return
        }

        flushPendingDelta()
        if (checkpointEvent is AgentEvent.ToolProgress) {
            appendProgress(checkpointEvent)
        } else {
            append(checkpointEvent)
        }
    }

    /**
     * 执行中进度按「同一工具的连续进度」折叠：只更新最后一条的 detail。
     *
     * 不折叠的后果很具体：一次十分钟的 ACP 委派按 250ms 节流也会产出上百条进度，
     * 每条都写一行 JSONL —— 恢复日志会比真正的对话历史还大，
     * 而中间态对"刚才执行到哪了"没有任何回答价值。
     */
    private fun appendProgress(event: AgentEvent.ToolProgress) {
        val previous = lastProgress
        if (
            previous != null &&
            previous.round == event.round &&
            previous.toolCallId == event.toolCallId &&
            previous.kind == event.kind
        ) {
            append(previous.copy(detail = event.detail, elapsedMs = event.elapsedMs))
            return
        }
        append(event)
        lastProgress = event
    }

    /** 把最后一段增量提交到日志；日志由结果 ACK 或中断恢复负责删除。 */
    fun seal() {
        flushPendingDelta()
    }

    fun discard() {
        pendingDelta = null
        lastProgress = null
        AgentRunCheckpointStore.remove(appContext, runId)
    }

    private fun flushPendingDelta() {
        val event = pendingDelta ?: return
        pendingDelta = null
        append(event)
        lastFlushNanos = nanoTime()
    }

    private fun append(event: AgentEvent) {
        AgentRunCheckpointStore.append(
            context = appContext,
            runId = runId,
            sortIndex = nextSortIndex++,
            event = event,
        )
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            return AgentRunCheckpointRecorder(
                context = context,
                runId = request.runId,
                nanoTime = nanoTime,
            )
        }
    }
}
