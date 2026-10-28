package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/**
 * 把持久 checkpoint 的事件重建成某个 run 的可见轨迹，并放回它原来的位置。
 *
 * 为什么必须放回原位：恢复发生在重启之后，用户往往已经发出下一条消息。
 * 旧实现只做「剔除旧轨迹 + 把重建结果追加到末尾」，于是上一次被杀前的内容
 * （连同紧跟着的「任务已中断」）会跑到更新的用户消息下面，
 * 看起来像「刚发的这条又被打断了一次」。真机数据库里能直接看到这种错位：
 * 旧 run 的 tool / thinking 排在更晚的用户消息之后。
 */
internal object AgentRunReplayProjection {
    fun project(
        runId: String,
        messages: List<AgentChatMessageUi>,
        events: List<AgentEvent>,
        projector: AgentRunMessageProjector = AgentRunMessageProjector(),
    ): List<AgentChatMessageUi> {
        if (runId.isBlank()) return messages
        val supplementIds = events
            .filterIsInstance<AgentEvent.UserSupplementReceived>()
            .mapTo(mutableSetOf()) { AgentPendingResultRecovery.supplementMessageId(runId, it.index) }
        // 插入点先按回放前的列表算，再减去它前面被剔除的本 run 消息，换算到保留列表的坐标。
        val anchor = replayAnchor(runId, messages, supplementIds)
        val droppedBeforeAnchor = messages.take(anchor)
            .count { message -> isRunTraceMessage(runId, message, supplementIds) }
        val kept = messages.filterNot { message -> isRunTraceMessage(runId, message, supplementIds) }
        val insertAt = (anchor - droppedBeforeAnchor).coerceIn(0, kept.size)
        // 轨迹单独重建：补充输入要插在「这条轨迹正在流式的回复」之前，
        // 混在整个会话列表里算会把别的 run 也当成落点。
        var trace: List<AgentChatMessageUi> = emptyList()
        for (event in events) {
            trace = if (event is AgentEvent.UserSupplementReceived) {
                AgentPendingResultRecovery.mergeSupplements(
                    runId = runId,
                    supplements = listOf(
                        AgentUiHandoffPayload.Supplement(
                            index = event.index,
                            text = event.text,
                            createdAt = System.currentTimeMillis(),
                        )
                    ),
                    messages = trace,
                )
            } else {
                projector.applyEvent(runId, event, trace)
            }
        }
        if (trace.isEmpty()) return kept
        return buildList(kept.size + trace.size) {
            addAll(kept.subList(0, insertAt))
            addAll(trace)
            addAll(kept.subList(insertAt, kept.size))
        }
    }

    /**
     * 轨迹应插到哪：优先紧跟它自己的用户消息（补充输入也挂在同一条轨迹上）；
     * 没有用户消息时回到原有轨迹的第一条位置；都没有才放末尾。
     */
    private fun replayAnchor(
        runId: String,
        messages: List<AgentChatMessageUi>,
        supplementIds: Set<String>,
    ): Int {
        val promptIndex = messages.indexOfLast { message ->
            message is UserMessageUi && (message.id == "user-$runId" || message.id in supplementIds)
        }
        if (promptIndex >= 0) return promptIndex + 1
        val traceIndex = messages.indexOfFirst { message -> isRunTraceMessage(runId, message, supplementIds) }
        return if (traceIndex >= 0) traceIndex else messages.size
    }
}

/**
 * 这条消息是否属于该 run 的可见轨迹。
 *
 * 回放与插入位置必须共用同一个判据：判据分叉时，要么重建后与旧消息重叠，
 * 要么把别的 run 的消息当成自己的删掉。
 */
internal fun isRunTraceMessage(
    runId: String,
    message: AgentChatMessageUi,
    replaySupplementIds: Set<String>,
): Boolean = when (message) {
    is AgentMessageUi ->
        message.id == "assistant-$runId" || message.id.startsWith("assistant-$runId-")
    is ThinkingMessageUi -> message.id.startsWith("$runId-thinking-")
    is ToolActivityMessageUi -> message.id.startsWith("$runId-tool-")
    is SystemNoticeMessageUi ->
        message.id == "assistant-$runId" ||
            message.id.startsWith("assistant-$runId-") ||
            message.id == "interrupted-$runId"
    is UserMessageUi -> message.id in replaySupplementIds
    else -> false
}
