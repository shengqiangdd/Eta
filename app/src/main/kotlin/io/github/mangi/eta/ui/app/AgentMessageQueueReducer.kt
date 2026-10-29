package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * 会话内排队发送（Queue）与插话发送（Interrupt）的纯状态转换器。
 *
 * - 排队发送追加到队尾，等待当前回合自然结束后按 FIFO 顺序自动发出。
 * - 插话发送（[QueuedMessageUi.immediateInterrupt]）置入队首并请求中止当前运行，
 *   待当前运行收尾落盘后立即优先发出，避免取消与新运行之间出现历史写入竞态。
 * - 用户主动点击停止按钮时保留队列不自动发出，防止想停下时后续排队指令继续跑起来。
 */
internal object AgentMessageQueueReducer {

    /** [planDispatch] 的结论：发出 / 已转成草稿 / 发不出去。 */
    internal enum class DispatchPlan {
        /** 输入框空着，可以把正文当作草稿发出去。 */
        SendNow,

        /** 带附件的消息不能直接发，已把正文与附件放回输入框，等用户确认。 */
        MovedToDraft,

        /** 当前不能发，消息已放回队列（新状态里一定有它）。 */
        Deferred,
    }

    /**
     * 决定一条「已被调用方从队列里弹出」的消息该怎么发。
     *
     * 为什么要有这个纯函数：调用方（run 收尾接力、队列条「立即插话发送」）都已经把它弹出来了，
     * 发不出去时只 `return false` 而不放回去，消息就既不在队列也不在输入框 —— 静默消失。
     * 压缩中、带附件但草稿位被占，这两个口子以前都是这样。放进纯函数才能用单测钉住
     * 「每条发不出去的路径都把消息放回了某处」。
     */
    fun planDispatch(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): Pair<AgentChatUiState, DispatchPlan> {
        fun deferred(): Pair<AgentChatUiState, DispatchPlan> {
            if (state.queuedMessages.any { it.id == item.id }) {
                return state to DispatchPlan.Deferred
            }
            return state.copy(
                queuedMessages = listOf(item.copy(immediateInterrupt = false)) + state.queuedMessages,
            ) to DispatchPlan.Deferred
        }
        // 正在跑 / 正在压缩：不可能再开一轮。上游都先确认过空闲，这里是兜底，
        // 消息放回队首等这次收尾；清掉插话标记，别让它反复尝试打断。
        if (state.isStreaming || state.isCompacting) return deferred()
        // 编辑态下 input 存的就是「正在改的那条」：写进去会把用户刚改的内容顶掉，
        // 若这条编辑是「保留后续消息」模式，还会被当成编辑正文写进历史。
        if (state.messageEdit != null) return deferred()
        if (item.pendingImages.isEmpty() && item.pendingFileReferences.isEmpty()) {
            if (state.input.isNotBlank()) {
                // 用户正在打字：消息放回**队尾**等他，input 与 messageEdit 一个字都不动。
                // 不要走 [enqueue]：那是「发送成功后」的收尾，会把草稿一起清掉。
                return state.copy(
                    queuedMessages = state.queuedMessages + item.copy(immediateInterrupt = false),
                ) to DispatchPlan.Deferred
            }
            return state to DispatchPlan.SendNow
        }
        if (state.input.isBlank() && state.pendingImages.isEmpty() &&
            state.pendingFileReferences.isEmpty()
        ) {
            return state.copy(
                input = item.content,
                pendingImages = item.pendingImages,
                pendingFileReferences = item.pendingFileReferences,
            ) to DispatchPlan.MovedToDraft
        }
        // 带附件的消息只能走草稿位，而草稿位被文字/附件占着：以前这里什么都不做就丢了。
        return deferred()
    }

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
        )
    }

    /**
     * 将队列中的指定消息提升为立即插话。
     *
     * - 若当前仍在流式运行（非压缩）：将其移至队首并标记 [QueuedMessageUi.immediateInterrupt]，等待当前运行取消后自动发出。
     * - 若当前已空闲：直接从队列弹出并返回，由调用方立即发起新运行。
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming && !state.isCompacting) {
            val promoted = target.copy(immediateInterrupt = true)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false)
        }
    }

    /**
     * 将排队中的消息撤回输入框继续编辑；若输入框已有未发送草稿则保留并合并，不覆盖用户正在输入的内容。
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    /**
     * 用户正在打字时，队首那条「插话中」其实永远发不出去。
     *
     * 调用方（run 收尾接力）见到草稿非空就整段跳过，[pollNextToSendAfterRun] 根本不会被调用，
     * 插话标记会一直留着 —— 队列条一直显示「插话中」，消息却好端端躺在队列里不动。
     * 真机实测就是这样卡住的。这里把标记降级回「排队中」：仍然不抢草稿位，但界面不再说谎。
     *
     * 带附件的消息不走这条路径（它们只能进草稿位，草稿位被占时本来就要等）。
     */
    fun demoteBlockedInterruptHead(state: AgentChatUiState): AgentChatUiState {
        val head = state.queuedMessages.firstOrNull() ?: return state
        if (!head.immediateInterrupt) return state
        if (state.input.isBlank()) return state
        if (head.pendingImages.isNotEmpty() || head.pendingFileReferences.isNotEmpty()) return state
        return state.copy(
            queuedMessages = listOf(head.copy(immediateInterrupt = false)) + state.queuedMessages.drop(1),
        )
    }

    /**
     * 当前运行收尾后决定是否自动弹出下一条排队消息：
     * - 队首为 [QueuedMessageUi.immediateInterrupt] 时，无论本次是否因取消结束，都立即弹出执行。
     * - 用户主动点击停止按钮（[stoppedByUser]）时，保留剩余队列，不自动发出。
     * - 其余情况（正常结束）弹出队首第一条消息自动发出。
     */
    fun pollNextToSendAfterRun(
        state: AgentChatUiState,
        stoppedByUser: Boolean,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val first = state.queuedMessages.firstOrNull() ?: return state to null
        if (!first.immediateInterrupt && stoppedByUser) {
            return clearInterruptFlags(state) to null
        }
        val remaining = state.queuedMessages.drop(1)
        return state.copy(queuedMessages = remaining) to first.copy(immediateInterrupt = false)
    }

    /**
     * run 收尾时要不要冻结队列。
     *
     * 拆成纯函数是为了能单测：之前这段「意图合并」写在 AppState 里，三个条件
     * （用户点过停止 / 插话触发的停止 / error 恰好是「已停止」）只能靠真机试。
     */
    fun isQueueFrozen(
        explicitlyStoppedByUser: Boolean,
        stoppedForInterrupt: Boolean,
        errorLooksStopped: Boolean,
    ): Boolean = !stoppedForInterrupt && (explicitlyStoppedByUser || errorLooksStopped)
}
