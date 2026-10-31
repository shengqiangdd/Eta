package io.github.mangi.eta.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.AgentConversationLoadState
import io.github.mangi.eta.ui.components.LocalAgentBrowserSession
import io.github.mangi.eta.ui.components.LocalGuiControlWaiting
import io.github.mangi.eta.ui.components.LocalRoleplayChat
import io.github.mangi.eta.ui.components.rememberAgentGoal
import io.github.mangi.eta.ui.components.rememberTodoPlan
import io.github.mangi.eta.ui.model.pendingAskOf
import io.github.mangi.eta.ui.components.chatConversationCompositionKey
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.components.LocalAgentDraftSink
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.agent.runtime.AppExitCause

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [io.github.mangi.eta.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    onAction: (AgentHomeAction) -> Unit,
    missingPermissionIds: Set<String> = emptySet(),
    permissionIssuesKnown: Boolean = false,
    onOpenPermissions: () -> Unit = {},
    systemExit: AppExitCause = AppExitCause.NONE,
    onDismissSystemExit: () -> Unit = {},
    chatViewMode: ChatViewMode = ChatViewMode.ALL,
    onChatViewModeChange: (ChatViewMode) -> Unit = {},
    searchOpenRequests: Int = 0,
    onOpenRunDetail: (String) -> Unit = {},
    isDrawerOpen: Boolean = false,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    isLoadingConversation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        // 大会话（本机实测库里单会话可上万条）切进来时消息是异步读的：
        // 此刻 messages 还是空表，直接渲染会闪一屏「还没有消息」。
        if (isLoadingConversation) {
            AgentConversationLoadState(
                isLoading = true,
                failed = false,
                onRetry = {},
                modifier = modifier,
            )
        } else {
        val todoPlan = rememberTodoPlan(conversationKey)
        val goal = rememberAgentGoal(conversationKey)
        val pendingAsk = remember(state.messages, state.queuedMessages) {
            pendingAskOf(state.messages, hasQueuedInput = state.queuedMessages.isNotEmpty())
        }
        // 聊天区与运行时共用同一个浏览器会话：Agent 打开的页面，工具卡片里能直接看到。
        val browser = remember(conversationKey) {
            BrowserSessionRegistry.get(conversationKey ?: BrowserSessionRegistry.MANUAL_SESSION_KEY)
        }
        CompositionLocalProvider(
            LocalRoleplayChat provides state.roleplayChatUi(),
            LocalAgentBrowserSession provides browser,
            LocalGuiControlWaiting provides state.isGuiWaiting,
            LocalAgentDraftSink provides { text -> onAction(AgentHomeAction.DraftChanged(text)) },
        ) {
        AgentChatBody(
            messages = state.messages,
            todoPlan = todoPlan,
            goal = goal,
            goalScopeId = conversationKey,
            pendingAsk = pendingAsk,
            // `ask_user` 的语义是「模型到此为止，等我回答」：回答必须是下一轮用户消息，
            // 绝不能变成排队消息。运行中也不排队 —— 由 answerPendingAsk 先落消息、再停旧 run，
            // 收尾后立刻起新 run（2026-10-30 真机：回答挂在队列条上、切回会话又要重答）。
            onAnswerAsk = { text -> onAction(AgentHomeAction.AnswerAsk(text)) },
            modelPickerState = modelPickerState,
            isCompacting = state.isCompacting,
            input = state.input,
            inputSyncVersion = state.inputSyncVersion,
            isStreaming = state.isStreaming,
            isPaused = state.isPaused,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            queuedMessages = state.queuedMessages,
            messageEdit = state.messageEdit,
            onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
            onCompactContext = { onAction(AgentHomeAction.CompactContext) },
            canCompactContext = state.canCompactContext,
            journalDeferred = state.journalDeferred,
            messagesDeferred = state.messagesDeferred,
            chatViewMode = chatViewMode,
            onChatViewModeChange = onChatViewModeChange,
            searchOpenRequests = searchOpenRequests,
            missingPermissionIds = missingPermissionIds,
            permissionIssuesKnown = permissionIssuesKnown,
            // 关闭记录按会话落盘：草稿会话（null）不落盘，拿到 id 后重新判断。
            noticeScopeKey = conversationKey,
            onOpenPermissions = onOpenPermissions,
            systemExit = systemExit,
            onDismissSystemExit = onDismissSystemExit,
            onModelSelected = { onAction(AgentHomeAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentHomeAction.SubmitMessage(text)) },
            onInterruptAndSubmit = { text -> onAction(AgentHomeAction.InterruptAndSubmitMessage(text)) },
            onSteerQueuedMessage = { id -> onAction(AgentHomeAction.SteerQueuedMessage(id)) },
            onRecallQueuedMessage = { id, draft -> onAction(AgentHomeAction.RecallQueuedMessage(id, draft)) },
            onRemoveQueuedMessage = { id -> onAction(AgentHomeAction.RemoveQueuedMessage(id)) },
            onStop = { onAction(AgentHomeAction.StopRun) },
            onTogglePause = { onAction(AgentHomeAction.ToggleRunPause) },
            onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
            onSelectReplyCandidate = { id, index -> onAction(AgentHomeAction.SelectReplyCandidate(id, index)) },
            onSuggestionClick = { prompt ->
                onAction(AgentHomeAction.SubmitMessage(prompt))
            },
            onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
            onOpenRunDetail = onOpenRunDetail,
            isDrawerOpen = isDrawerOpen,
            messageStartIndex = state.messagesWindowStart,
            hasEarlierMessages = state.messagesWindowStart > 0,
            historyLoadState = historyLoadState,
            onLoadEarlierMessages = { onAction(AgentHomeAction.LoadEarlierMessages) },
            onHistoryViewportChanged = { atLatest ->
                onAction(AgentHomeAction.HistoryViewportChanged(atLatest))
            },
            modifier = modifier,
        )
        }
        }
    }
}
