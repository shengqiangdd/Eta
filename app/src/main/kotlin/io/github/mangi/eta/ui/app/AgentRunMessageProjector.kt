package io.github.mangi.eta.ui.app

import android.os.SystemClock
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.SearchSuggestionsBudget
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * 工具开始时间用**墙上时间**：要和数据库、重启后的当前时间比，
     * elapsedRealtime 在重启后归零，比不了。测试可注入固定值。
     */
    private val nowWallClock: () -> Long = { System.currentTimeMillis() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()

    /**
     * 每轮收到第一个内容碑的时刻（key = thinkingId）。
     *
     * 只在首次自增时写入：后续每个 delta 都覆盖就不是首字了。
     * 用 elapsedRealtime 而非壁钟：壁钟会因时区/校时跳变，导致耗时为负。
     */
    private val firstTokenAt = mutableMapOf<String, Long>()

    /**
     * App 与系统助手浮层共用的事件到消息投影。无可见变化的事件原样返回同一列表，
     * 调用方据此跳过保存与时间戳更新；补充输入的插入位置与持久化由调用方决定，这里不处理。
     */
    fun applyEvent(
        runId: String,
        event: AgentEvent,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = when (event) {
        is AgentEvent.AssistantBlockStart -> startAssistantBlock(runId, event, messages)
        is AgentEvent.AssistantBlockDelta -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT ->
                appendTextDelta(runId, event.round, event.index, event.delta, messages)
            AgentEvent.AssistantBlockKind.THINKING ->
                appendReasoningDelta(runId, event.round, event.index, event.delta, messages)
            AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
        }
        is AgentEvent.AssistantBlockEnd -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT ->
                finalizeTextBlock(runId, event.round, event.index, event.replacementContent, messages)
            AgentEvent.AssistantBlockKind.THINKING ->
                finalizeThinkingBlock(runId, event.round, event.index, event.replacementContent, messages)
            AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
        }
        is AgentEvent.UsageReceived -> attachUsage(runId, event.round, event.usage.toUi(), messages)
        is AgentEvent.ToolStarted ->
            startTool(runId, event, finalizeTextRound(runId, event.round, finalizeThinkingRound(runId, event.round, messages)))
        is AgentEvent.ToolFinished -> finishTool(runId, event, messages)
        is AgentEvent.ToolProgress -> updateToolProgress(runId, event, messages)
        is AgentEvent.HostedToolStarted ->
            startHostedTool(runId, event, finalizeTextRound(runId, event.round, finalizeThinkingRound(runId, event.round, messages)))
        is AgentEvent.HostedToolFinished -> finishHostedTool(runId, event, messages)
        is AgentEvent.ContextCompaction -> {
            val id = "assistant-$runId-compaction-${event.operationId}"
            messages.filterNot { it.id == id } + SystemNoticeMessageUi(
                id = id, code = SystemNoticeCode.ContextCompaction, detail = event.displayMessage,
                contextTokens = event.tokensAfter,
                running = event.phase == AgentEvent.ContextCompaction.PHASE_STARTED,
            )
        }
        is AgentEvent.ModelRetryScheduled -> scheduleModelRetry(runId, event, messages)
        is AgentEvent.RunFailed -> failRunningTools(event.reason, finalizeText(runId, finalizeThinking(runId, messages)))
        is AgentEvent.AssistantReceived -> if (event.reasoningContent.isBlank()) messages else {
            ensureCompletedThinking(runId, event.round, event.reasoningContent, messages)
        }
        is AgentEvent.GuiControlChanged -> messages
        is AgentEvent.SearchSuggestions -> attachSearchSuggestions(runId, event, messages)
        is AgentEvent.SubagentProgress -> updateSubagentProgress(runId, event, messages)
        is AgentEvent.RunFinished -> finalizeText(runId, finalizeThinking(runId, messages))
        is AgentEvent.UserSupplementReceived,
        is AgentEvent.RunStarted,
        is AgentEvent.ProviderRequestStarted,
        is AgentEvent.ProviderResponseStarted,
        is AgentEvent.ToolImagesAttached,
        is AgentEvent.RoundStarted,
        -> messages
    }

    private fun attachSearchSuggestions(
        runId: String,
        event: AgentEvent.SearchSuggestions,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = assistantMessageId(runId, event.round, event.index)
        val target = messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == targetId } ?: return messages
        if (event.html in target.searchSuggestionsHtml) return messages
        val budget = SearchSuggestionsBudget()
        messages.filterIsInstance<AgentMessageUi>()
            .filter { isAssistantMessageForRound(it.id, runId, event.round) }
            .forEachIndexed { ordinal, message ->
                message.searchSuggestionsHtml.forEach { budget.accept(event.round, ordinal, it) }
            }
        if (!budget.accept(event.round, messages.size, event.html)) return messages
        return messages.map { message ->
            if (message === target) target.copy(searchSuggestionsHtml = target.searchSuggestionsHtml + event.html) else message
        }
    }

    /**
     * 只补充 token 用量，不触碰 isStreaming：用量事件紧跟文本块结束，
     * 改回流式会让渲染在流式/静态视图间反复切换并整段重渲染。
     */
    private fun attachUsage(
        runId: String,
        round: Int,
        usage: TokenUsageUi,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (usage.isEmpty) return messages
        val targetIndex = messages.indexOfLast { message ->
            message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)
        }
        if (targetIndex < 0) return messages
        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is AgentMessageUi) message.copy(usage = usage) else message
        }
    }

    /** 回放从该 run 的空轨迹重建；仅重排有回放事件的补充输入，旧 handoff 独有的输入必须保留。 */
    fun resetForReplay(
        runId: String,
        messages: List<AgentChatMessageUi>,
        replaySupplementIndexes: Set<Int> = emptySet(),
    ): List<AgentChatMessageUi> {
        if (runId.isBlank()) return messages
        clearRun(runId)
        val replaySupplementIds = replaySupplementIndexes.mapTo(mutableSetOf()) { index ->
            AgentPendingResultRecovery.supplementMessageId(runId, index)
        }
        // 判据与插入位置共用同一个函数：两份清单迟早会分叉，
        // 分叉后要么重建轨迹与旧消息重叠，要么删掉别的 run 的消息。
        return messages.filterNot { message -> isRunTraceMessage(runId, message, replaySupplementIds) }
    }

    /**
     * 把提示插在该 run 轨迹的末尾。
     *
     * 不能直接 `messages + notice`：恢复发生在重启之后，用户可能已经发了下一条消息，
     * 追加到列表尾会让「任务已中断」落在更新的对话下面，像是刚发生的一样。
     */
    fun insertRunNotice(
        runId: String,
        notice: SystemNoticeMessageUi,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (messages.any { it.id == notice.id }) return messages
        val lastRunIndex = messages.indexOfLast { message -> isRunTraceMessage(runId, message, emptySet()) }
        if (lastRunIndex < 0) return messages + notice
        return buildList(messages.size + 1) {
            addAll(messages.subList(0, lastRunIndex + 1))
            add(notice)
            addAll(messages.subList(lastRunIndex + 1, messages.size))
        }
    }

    fun finishContextCompaction(
        runId: String,
        messages: List<AgentChatMessageUi>,
        detail: String,
    ): List<AgentChatMessageUi> = messages.map { message ->
        if (message is SystemNoticeMessageUi && message.code == SystemNoticeCode.ContextCompaction &&
            message.id.startsWith("assistant-$runId-compaction-") && message.running) {
            message.copy(detail = detail, running = false)
        } else message
    }

    fun scheduleModelRetry(
        runId: String,
        event: AgentEvent.ModelRetryScheduled,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val finalized = finalizeTextRound(
            runId, event.round, finalizeThinkingRound(runId, event.round, messages),
        )
        val notice = SystemNoticeMessageUi(
            id = "assistant-$runId-retry-${event.round}",
            code = SystemNoticeCode.ModelRetry,
            detail = event.displayMessage,
        )
        return finalized.filterNot { it.id == notice.id } + notice
    }

    fun startAssistantBlock(
        runId: String,
        event: AgentEvent.AssistantBlockStart,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = transitionVisibleBlock(
        runId = runId,
        round = event.round,
        kind = event.kind,
        index = event.index,
        messages = messages,
    )

    fun appendTextDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        // 记下首字时刻（若未记过）。
        //
        // 时钟读取包在 runCatching 里：这个方法是**渲染热路径**，每个 delta 都会进来，
        // 而时钟源在纯 JVM 单测里不可用（SystemClock 未 mock）。为了采一个可选指标
        // 把整条流式渲染弄崩，是完全不划算的取舍 —— 采不到就是 null，界面上不显示那一行。
        val tokenKey = thinkingMessageId(runId, round, index)
        if (tokenKey !in firstTokenAt) {
            runCatching { nowElapsedRealtime() }.getOrNull()?.let { firstTokenAt[tokenKey] = it }
        }

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = index,
            messages = messages,
        )
        val assistantId = assistantMessageId(runId, round, index)
        var updated = false
        val next = transitioned.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    renderMarkdown = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + AgentMessageUi(
            id = assistantId,
            content = delta,
            isStreaming = true,
            renderMarkdown = false,
        )
    }

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        // 纯空白增量不凭空建节点：模型偶尔只发一个空格或换行，落库后就是一条空「思考」。
        // 已有节点时仍照常追加 —— 段落之间的空行是正文格式的一部分，不能在这里吃掉。
        val thinkingId = thinkingMessageId(runId, round, index)
        val hasThinkingMessage = messages.any { it is ThinkingMessageUi && it.id == thinkingId }
        if (!hasThinkingMessage && delta.isBlank()) return messages

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.THINKING,
            index = index,
            messages = messages,
        )
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        val next = transitioned.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + ThinkingMessageUi(
            id = thinkingId,
            content = delta,
            isStreaming = true,
            elapsedSeconds = elapsedSeconds,
            collapsed = false,
        )
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (messages.any {
                it is ThinkingMessageUi && isThinkingMessageForRound(it.id, runId, round)
            }
        ) {
            return finalizeThinkingRound(runId, round, messages)
        }
        // 收尾事件里只有空白时补写节点，等于给这一轮加一条永远展开不出内容的「思考」。
        if (content.isBlank()) return messages

        val thinkingId = thinkingFallbackMessageId(runId, round)
        return messages.insertBeforeFirstAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }

    /** 终态不依赖各块结束事件全部到齐；缺少工具结果时只能标为未知，不能推断执行成功。 */
    fun finalizeRun(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        finalizeText(runId, finalizeThinking(runId, messages)).map { message ->
            if (
                message is ToolActivityMessageUi &&
                message.id.startsWith("$runId-tool-") &&
                message.status == ToolActivityStatusUi.Running
            ) {
                message.copy(status = ToolActivityStatusUi.Unknown)
            } else {
                message
            }
        }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        return messages.map { message ->
            if (message is ThinkingMessageUi && isThinkingMessageForRound(message.id, runId, round)) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun finalizeThinkingBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round, index)
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished(replacementContent)
            } else {
                message
            }
        }
    }

    fun finalizeText(
        runId: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRun(message.id, runId)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }

    fun finalizeTextRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 定稿时裁掉尾部空白：模型输出常以换行收尾，Markdown 渲染会把每个尾部
        // 换行节点变成一段固定间距，在正文与后续工具卡片之间形成莫名的空行。
        return messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun finalizeTextBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val assistantId = assistantMessageId(runId, round, index)
        return messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                message.copy(
                    content = replacementContent?.trimEnd() ?: message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 工具执行发生在对应 assistant 工具块完整返回之后；此时直接追加即可保留
        // 工具前说明、工具活动与下一轮结果的真实时间顺序。
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.argsPreview,
            command = event.command,
            askQuestion = event.askQuestion,
            askOptions = event.askOptions,
            // 起点写进消息本身（会随会话落盘）：切换会话 / 重启后卡片能接着算，
            // 而不是从 0 重新数秒。
            startedAtMs = nowWallClock(),
        )
        if (messages.any { it.id == message.id }) return messages
        return messages + message
    }

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        // success 字段优先；旧版本 Runtime/归档事件缺省时回退到摘要文本判断
        val status = when (event.success) {
            true -> ToolActivityStatusUi.Success
            false -> ToolActivityStatusUi.Failed
            null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
                ToolActivityStatusUi.Failed
            } else {
                ToolActivityStatusUi.Success
            }
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    resultDetail = event.detail,
                    filePath = event.filePath,
                    elapsedMs = event.elapsedMs,
                    imageCount = event.imageCount,
                )
            } else {
                message
            }
        }
    }

    /**
     * 长时工具的执行中进度。
     *
     * 只更新同一张工具卡片的运行中副标题（[ToolActivityMessageUi.progressLine]），
     * 不动 [ToolActivityMessageUi.resultSummary] —— 后者由 [finishTool] 一次性写入终态，
     * 两者混写会让"进行中"与"已完成"的文案互相覆盖。
     *
     * 幂等：detail 与上一次相同就原样返回，避免高频增量造成无意义重组。
     */
    private fun updateToolProgress(
        runId: String,
        event: AgentEvent.ToolProgress,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val detail = event.detail.trim()
        if (detail.isEmpty()) return messages
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        val targetIndex = messages.indexOfLast { message ->
            message is ToolActivityMessageUi && message.id == targetId
        }
        if (targetIndex < 0) return messages
        val target = messages[targetIndex] as ToolActivityMessageUi
        // 已结束的工具不再回填进度：终态写完之后冒出来的迟到增量只会制造"复活"的假象。
        if (target.status != ToolActivityStatusUi.Running) return messages
        if (target.progressLine == detail) return messages
        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(progressLine = detail, progressKind = event.kind)
            } else {
                message
            }
        }
    }

    fun updateSubagentProgress(
        runId: String,
        event: AgentEvent.SubagentProgress,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages
        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(command = event.snapshotJson)
            } else {
                message
            }
        }
    }

    fun startHostedTool(
        runId: String,
        event: AgentEvent.HostedToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "",
            // 与 startTool 一致：起点必须随消息走，否则托管工具（子代理等）重新进入会话后从 0 计。
            startedAtMs = nowWallClock(),
        )
        return if (messages.any { it.id == message.id }) messages else messages + message
    }

    fun finishHostedTool(
        runId: String,
        event: AgentEvent.HostedToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        return messages.map { message ->
            if (message is ToolActivityMessageUi && message.id == targetId) {
                message.copy(
                    status = if (event.success) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                    resultSummary = null,
                )
            } else {
                message
            }
        }
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun interruptRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Unknown,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun clearRun(runId: String) {
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
        // 不清理 firstTokenAt 会随每轮累积，长会话里是真正的泄漏。
        firstTokenAt.keys.removeAll { it.startsWith("$runId-thinking-") }
    }

    /**
     * 首字延迟与解码耗时（毫秒）。
     *
     * 首字 = 请求发出 → 第一个内容碑；解码 = 首字 → 本轮结束。
     * 两者相加不等于总耗时（总耗时含工具、排队），因此必须分开存。
     *
     * 任一时间点缺失就返回 null：拿不到就是拿不到，不能用 0 充当「很快」。
     */
    private fun timingFor(messageId: String, now: Long): Pair<Int?, Int?> {
        val started = thinkingStartedAt[messageId] ?: return null to null
        val first = firstTokenAt[messageId] ?: return null to null
        val firstTokenMs = ((first - started).coerceAtLeast(0)).toInt()
        val decodeMs = ((now - first).coerceAtLeast(0)).toInt()
        return firstTokenMs to decodeMs
    }

    private fun ThinkingMessageUi.finished(authoritativeContent: String? = null): ThinkingMessageUi {
        val now = nowElapsedRealtime()
        val (firstTokenMs, decodeMs) = timingFor(id, now)
        return copy(
            content = authoritativeContent ?: content,
            isStreaming = false,
            firstTokenMs = firstTokenMs ?: this.firstTokenMs,
            decodeMs = decodeMs ?: this.decodeMs,
            elapsedSeconds = thinkingStartedAt[id]?.let { startedAt ->
                ((now - startedAt) / 1000).toInt().coerceAtLeast(0)
            } ?: elapsedSeconds,
            collapsed = true,
        )
    }

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun transitionVisibleBlock(
        runId: String,
        round: Int,
        kind: AgentEvent.AssistantBlockKind,
        index: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val activeAssistantId = if (kind == AgentEvent.AssistantBlockKind.TEXT) {
            assistantMessageId(runId, round, index)
        } else {
            null
        }
        val activeThinkingId = if (kind == AgentEvent.AssistantBlockKind.THINKING) {
            thinkingMessageId(runId, round, index)
        } else {
            null
        }
        return messages.map { message ->
            when {
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.id != activeAssistantId ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )

                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) &&
                    message.id != activeThinkingId ->
                    message.finished()

                else -> message
            }
        }
    }

    private fun List<AgentChatMessageUi>.insertBeforeFirstAssistant(
        runId: String,
        round: Int,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst {
            it is AgentMessageUi && isAssistantMessageForRound(it.id, runId, round)
        }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun assistantMessageId(runId: String, round: Int, index: Int): String =
        "${assistantMessagePrefix(runId)}$round-$index"

    private fun thinkingMessageId(runId: String, round: Int, index: Int): String =
        "$runId-thinking-$round-$index"

    private fun thinkingFallbackMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round-fallback"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun isThinkingMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val prefix = "$runId-thinking-$round"
        return messageId == prefix || messageId.startsWith("$prefix-")
    }

    private fun toolActivityMessageId(runId: String, round: Int, toolCallId: String): String =
        "$runId-tool-$round-${toolCallId.ifBlank { "unknown" }}"

    companion object {
        /** 去掉该 run 的「任务已中断」提示；终态结果到达后它就是误报。 */
        fun withoutInterruptedNotice(
            runId: String,
            messages: List<AgentChatMessageUi>,
        ): List<AgentChatMessageUi> {
            val id = "interrupted-$runId"
            if (messages.none { it is SystemNoticeMessageUi && it.id == id }) return messages
            return messages.filterNot { it is SystemNoticeMessageUi && it.id == id }
        }

        /**
         * 手动压缩 run 的结果与压缩事件共用同一条时间线标记：事件标记已携带压缩前后的
         * token 信息，成功时原样保留；仍在进行中、失败或事件缺失时才改写或补写。
         */
        fun mergeCompactionResultNotice(
            runId: String,
            messages: List<AgentChatMessageUi>,
            ok: Boolean,
            detail: String,
        ): List<AgentChatMessageUi> {
            val index = messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ContextCompaction &&
                    it.id.startsWith("assistant-$runId-compaction-")
            }
            if (index < 0) {
                return messages + SystemNoticeMessageUi(
                    id = "assistant-$runId-compaction-result",
                    code = SystemNoticeCode.ContextCompaction,
                    detail = detail,
                )
            }
            val notice = messages[index] as SystemNoticeMessageUi
            if (ok && !notice.running) return messages
            return messages.mapIndexed { i, message ->
                if (i == index && message is SystemNoticeMessageUi) {
                    message.copy(detail = detail, running = false)
                } else {
                    message
                }
            }
        }

        /** 终态只能补全最后一次重试之后的回答，不能覆盖已标记失败的半截输出。 */
        fun resultTargetIndex(
            runId: String,
            messages: List<AgentChatMessageUi>,
            includeNotices: Boolean = false,
        ): Int {
            val retryIndex = lastRetryIndex(runId, messages)
            return messages.indices.lastOrNull { index ->
                val message = messages[index]
                index > retryIndex &&
                    (message is AgentMessageUi || includeNotices && message is SystemNoticeMessageUi) &&
                    isAssistantMessageForRun(message.id, runId)
            } ?: -1
        }

        fun resultFallbackId(runId: String, messages: List<AgentChatMessageUi>): String {
            val retry = messages.getOrNull(lastRetryIndex(runId, messages))
            if (retry == null) return "assistant-$runId-1"
            val round = retry.id.substringAfterLast('-').toIntOrNull()?.plus(1) ?: 1
            return "assistant-$runId-$round-result"
        }

        /**
         * 用终态补全最后一条回答：notice 为 null 时写入正文，否则替换为系统提示。
         * 同一轮已有多个文本块时，流式内容已完整，终态正文只在当前块为空时补写。
         */
        fun applyResult(
            runId: String,
            messages: List<AgentChatMessageUi>,
            content: String,
            notice: SystemNoticeCode?,
            detail: String? = null,
        ): List<AgentChatMessageUi> {
            val targetIndex = resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                val id = resultFallbackId(runId, messages)
                return messages + if (notice == null) {
                    AgentMessageUi(id = id, content = content, isStreaming = false, renderMarkdown = true)
                } else {
                    SystemNoticeMessageUi(id, notice, detail)
                }
            }
            val target = messages[targetIndex] as AgentMessageUi
            val replacement = if (notice != null) SystemNoticeMessageUi(target.id, notice, detail) else {
                val round = target.id.assistantRound(runId)
                val sameRoundBlocks = round?.let {
                    messages.count { message -> message is AgentMessageUi && message.id.assistantRound(runId) == it }
                } ?: 0
                target.copy(
                    content = if (sameRoundBlocks <= 1) content else target.content.ifBlank { content },
                    isStreaming = false,
                    renderMarkdown = true,
                )
            }
            return messages.mapIndexed { index, message -> if (index == targetIndex) replacement else message }
        }

        private fun String.assistantRound(runId: String): Int? =
            removePrefix(assistantMessagePrefix(runId))
                .takeIf { it != this }
                ?.substringBefore('-')
                ?.toIntOrNull()

        private fun lastRetryIndex(runId: String, messages: List<AgentChatMessageUi>): Int =
            messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry &&
                    it.id.startsWith("assistant-$runId-retry-")
            }

        private fun assistantMessagePrefix(runId: String): String =
            "assistant-$runId-"

        private fun isAssistantMessageForRun(messageId: String, runId: String): Boolean =
            messageId == "assistant-$runId" ||
                messageId.startsWith(assistantMessagePrefix(runId))
    }

}

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48

internal fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
        promptTokens = promptTokens,
    )
