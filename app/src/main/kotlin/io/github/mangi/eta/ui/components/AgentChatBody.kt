package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.runtime.AppExitCause
import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.model.AgentAskUi
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.agent.model.AgentGoal
import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTodoPlan
import io.github.mangi.eta.agent.model.AgentTranscriptPresentation
import io.github.mangi.eta.agent.model.AgentTranscriptViewPolicy
import io.github.mangi.eta.agent.model.AgentWorkCollapsePolicy
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.voice.SpeechAutoReadAloud
import io.github.mangi.eta.ui.voice.SpeechPlaybackHost
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    todoPlan: AgentTodoPlan? = null,
    /** 会话级子代理台账；null 或空台账时不显示卡片。 */
    taskLedger: AgentTaskLedger? = null,
    goal: AgentGoal? = null,
    /** 目标的会话作用域；卡片上的暂停/继续/编辑/清除直接写回它，null 表示当前没有可落盘的会话。 */
    goalScopeId: String? = null,
    pendingAsk: AgentAskUi? = null,
    onAnswerAsk: (String) -> Unit = {},
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    input: String,
    /** 外部写入 [input] 的序号：变化时输入框强制同步一次（普通打字不走这条）。 */
    inputSyncVersion: Int = 0,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    messageEdit: MessageEditUiState?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    missingPermissionIds: Set<String> = emptySet(),
    /** 权限检查是否已经出结果；未出结果时不能拿空的 id 集合去清忽略记录。 */
    permissionIssuesKnown: Boolean = false,
    /** 当前会话 id：会话内的提示按它分开记；null 表示新建会话，不落盘。 */
    noticeScopeKey: String? = null,
    journalDeferred: Boolean = false,
    messagesDeferred: Boolean = false,
    systemExit: AppExitCause = AppExitCause.NONE,
    onDismissSystemExit: () -> Unit = {},
    chatViewMode: ChatViewMode = ChatViewMode.ALL,
    onChatViewModeChange: (ChatViewMode) -> Unit = {},
    searchOpenRequests: Int = 0,
    onOpenPermissions: () -> Unit = {},
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onTogglePause: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenRunDetail: (String) -> Unit = {},
    isDrawerOpen: Boolean = false,
    /** 窗口首条消息在库里的下标；>0 表示更早的消息还在库里，可继续上翻。 */
    messageStartIndex: Int = 0,
    /** 是否还有更早的消息；通常等价于 [messageStartIndex] > 0。 */
    hasEarlierMessages: Boolean = false,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    // 上游 3.3.5 起浏览器会话按会话隔离（class + CompositionLocal 提供），不再有全局静态快照。
    val browserSnapshot = LocalAgentBrowserSession.current?.snapshots?.collectAsState()?.value
        ?: BrowserSessionSnapshot()
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        latestContextUsage(messages, modelPickerState.selectedModel)
    }

    val visibleMessages = remember(messages, messageEdit?.targetMessageId, messageEdit?.preserveFollowingMessages) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    // 收窄视图只影响这一屏画什么，不改 messages 本身：编辑、删除、重新生成、导出
    // 这些动作仍按完整消息判定，收起一部分消息不会让它们少算一轮。
    val hiddenMessageCount = remember(visibleMessages, chatViewMode) {
        countHiddenByViewMode(visibleMessages, chatViewMode)
    }
    val displayedMessages = remember(visibleMessages, chatViewMode) {
        filterForViewMode(visibleMessages, chatViewMode)
    }
    // 会话内搜索：只在当前显示的消息里匹配，因此每个命中都能真的跳过去。
    // 被收窄视图藏起来的部分单独计数，界面上如实说明「还有 N 条在收起的内容里」，
    // 而不是让用户以为搜不到。文案按模式区分：对话视图收起过程消息，轨迹视图收起正文。
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCursor by remember { mutableIntStateOf(0) }
    var scrollToken by remember { mutableIntStateOf(0) }
    var scrollRequest by remember { mutableStateOf<ChatScrollRequest?>(null) }
    // 回合导航的锚点是每一次提问。跳转复用同一套 scrollRequest / scrollToken：另建一套的话，
    // 两套滚动请求会各自动画一次，后到的那个把前一个的目标覆盖掉。
    var turnHighlightId by remember { mutableStateOf<String?>(null) }
    val turnCount = remember(visibleMessages) { chatTurnAnchorIds(visibleMessages).size }
    val jumpToTurn: (String) -> Unit = { anchorId ->
        // 跳转意味着用户要离开最新消息，必须解除跟底，否则下一次流式输出立刻把他拉回去。
        keepBottomAnchored = false
        turnHighlightId = anchorId
        scrollToken += 1
        scrollRequest = ChatScrollRequest(messageId = anchorId, token = scrollToken)
    }
    // 高亮只是「我跳到了这里」的落点提示，短暂停留后撤掉，不与搜索命中长期混淆。
    LaunchedEffect(turnHighlightId) {
        if (turnHighlightId != null) {
            delay(TurnHighlightDurationMs)
            turnHighlightId = null
        }
    }
    val resources = LocalResources.current
    val noticeText: (SystemNoticeCode) -> String = remember(resources) {
        { code -> resources.getString(systemNoticeTitleRes(code)) }
    }
    val searchMatches = remember(displayedMessages, searchQuery, noticeText) {
        matchMessageIds(displayedMessages, searchQuery, noticeText)
    }
    val searchMatchIds = remember(searchMatches) { searchMatches.toSet() }
    val hiddenSearchMatchCount = remember(messages, searchQuery, chatViewMode, searchMatches.size) {
        if (chatViewMode == ChatViewMode.ALL || searchQuery.isBlank()) {
            0
        } else {
            (matchMessageIds(messages, searchQuery, noticeText).size - searchMatches.size)
                .coerceAtLeast(0)
        }
    }

    // 打开搜索是「一次性意图」，用递增计数表达。组件首次组合时把当前计数视为已消费：
    // 会话切换会经 key(chatConversationCompositionKey) 重建本组件，若直接把 >0 当成待办，
    // 旧意图会在新会话里重放，表现为「切个会话就莫名弹出空搜索条」。
    var consumedSearchOpenRequests by remember { mutableIntStateOf(searchOpenRequests) }
    LaunchedEffect(searchOpenRequests) {
        if (searchOpenRequests > consumedSearchOpenRequests) {
            consumedSearchOpenRequests = searchOpenRequests
            searchActive = true
        }
    }

    LaunchedEffect(searchActive) {
        if (!searchActive) return@LaunchedEffect
        // 搜索期间必须脱离「跟到底部」，否则每次跳转都会被拉回最新消息。
        keepBottomAnchored = false
    }

    LaunchedEffect(searchQuery) {
        searchCursor = 0
    }

    LaunchedEffect(searchActive, searchQuery, searchCursor) {
        if (!searchActive) return@LaunchedEffect
        val target = searchMatches.getOrNull(searchCursor) ?: return@LaunchedEffect
        scrollToken += 1
        scrollRequest = ChatScrollRequest(messageId = target, token = scrollToken)
    }

    val searchUi = if (searchActive) {
        ChatSearchUi(
            query = searchQuery,
            matchCount = searchMatches.size,
            cursor = searchCursor,
            hiddenMatchCount = hiddenSearchMatchCount,
            hiddenMatchesAreReplies = chatViewMode == ChatViewMode.TRACE,
            onQueryChange = { searchQuery = it },
            onPrevious = {
                if (searchMatches.isNotEmpty()) {
                    searchCursor = (searchCursor - 1 + searchMatches.size) % searchMatches.size
                }
            },
            onNext = {
                if (searchMatches.isNotEmpty()) {
                    searchCursor = (searchCursor + 1) % searchMatches.size
                }
            },
            onClose = {
                searchActive = false
                searchQuery = ""
                searchCursor = 0
                scrollRequest = null
            },
        )
    } else {
        null
    }
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserSnapshot.available,
        browserSnapshot.lastAgentRunId,
        browserSnapshot.lastAgentToolCallId,
    ) {
        val runId = browserSnapshot.lastAgentRunId
        val toolCallId = browserSnapshot.lastAgentToolCallId
        if (!browserSnapshot.available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    SpeechPlaybackHost {
        SpeechAutoReadAloud(messages = messages, isStreaming = isStreaming)
        AgentChatScaffold(
            visibleMessages = displayedMessages,
            todoPlan = todoPlan,
            taskLedger = taskLedger,
            goal = goal,
            goalScopeId = goalScopeId,
            pendingAsk = pendingAsk,
            onAnswerAsk = onAnswerAsk,
            hasMessages = visibleMessages.isNotEmpty(),
            scrollState = scrollState,
            input = input,
            inputSyncVersion = inputSyncVersion,
            modelPickerState = modelPickerState,
            isCompacting = isCompacting,
            contextUsage = contextUsage,
            isStreaming = isStreaming,
            isPaused = isPaused,
            reasoningEffort = reasoningEffort,
            availableReasoningEfforts = availableReasoningEfforts,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            queuedMessages = queuedMessages,
            messageEdit = messageEdit,
            showEmptySuggestions = !isKeyboardVisible,
            keepBottomAnchored = keepBottomAnchored,
            onBottomAnchorChanged = { keepBottomAnchored = it },
            messageStartIndex = messageStartIndex,
            hasEarlierMessages = hasEarlierMessages,
            historyLoadState = historyLoadState,
            onLoadEarlierMessages = onLoadEarlierMessages,
            onHistoryViewportChanged = onHistoryViewportChanged,
            onSubmit = { text ->
                sentFromKeyboard = true
                // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                keepBottomAnchored = true
                onSubmit(text)
            },
            onInterruptAndSubmit = { text ->
                keepBottomAnchored = true
                onInterruptAndSubmit(text)
            },
            onSteerQueuedMessage = onSteerQueuedMessage,
            onRecallQueuedMessage = onRecallQueuedMessage,
            onRemoveQueuedMessage = onRemoveQueuedMessage,
            onReasoningEffortChange = onReasoningEffortChange,
            onCompactContext = onCompactContext,
            canCompactContext = canCompactContext,
            missingPermissionIds = missingPermissionIds,
            permissionIssuesKnown = permissionIssuesKnown,
            noticeScopeKey = noticeScopeKey,
            journalDeferred = journalDeferred,
            messagesDeferred = messagesDeferred,
            systemExit = systemExit,
            onDismissSystemExit = onDismissSystemExit,
            hiddenMessageCount = hiddenMessageCount,
            viewMode = chatViewMode,
            onShowAllMessages = { onChatViewModeChange(ChatViewMode.ALL) },
            searchUi = searchUi,
            searchMatchIds = searchMatchIds,
            highlightMessageId = if (searchActive) searchMatches.getOrNull(searchCursor) else turnHighlightId,
            scrollRequest = scrollRequest,
            turnCount = turnCount,
            turnNavigationEnabled = !searchActive,
            onJumpToTurn = jumpToTurn,
            onOpenPermissions = onOpenPermissions,
            onModelSelected = onModelSelected,
            onStop = onStop,
            onTogglePause = onTogglePause,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            onRemoveFileReference = onRemoveFileReference,
            onEditMessage = onEditMessage,
            onCancelMessageEdit = onCancelMessageEdit,
            onDeleteMessage = onDeleteMessage,
            onRegenerateMessage = onRegenerateMessage,
            onSelectReplyCandidate = onSelectReplyCandidate,
            onSuggestionClick = onSuggestionClick,
            onOpenBrowser = onOpenBrowser,
            onOpenRunDetail = onOpenRunDetail,
            currentBrowserMessageId = currentBrowserMessageId,
            modifier = modifier,
        )
    }
}

/**
 * 「只看对话」开着、而这一屏恰好全是过程消息时的占位。
 *
 * 必须和 EmptyChatState 区分开：后者表示「这个会话还没有内容」，这里表示「内容在，只是被收起了」。
 * 两者混用会让用户以为对话被清空了。
 */
@Composable
private fun ViewFilteredEmptyState(
    mode: ChatViewMode,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (mode == ChatViewMode.TRACE) {
                Icons.Rounded.Timeline
            } else {
                Icons.Rounded.FilterAlt
            },
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (mode == ChatViewMode.TRACE) {
                    R.string.chat_view_mode_trace_empty_title
                } else {
                    R.string.chat_dialog_only_empty_title
                },
            ),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (mode == ChatViewMode.TRACE) {
                    R.string.chat_view_mode_trace_empty_body
                } else {
                    R.string.chat_dialog_only_empty_body
                },
            ),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = stringResource(R.string.chat_dialog_only_show_all),
            onClick = onShowAll,
        )
    }
}

/**
 * 会话内搜索条。
 *
 * 固定列在消息区上方（不是浮层）：浮层会盖住第一屏，而用户恰恰要在那一屏里确认命中位置。
 * 命中数、上/下一个、关闭三个动作并排；命中落在被收起的过程消息里时单独说明一句 ——
 * 不说明的话，用户搜不到内容只会以为「这个功能坏了」。
 */
@Composable
private fun ChatSearchBar(
    search: ChatSearchUi,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MiuixTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchBar(
                    modifier = Modifier.weight(1f),
                    insideMargin = DpSize.Zero,
                    expanded = false,
                    onExpandedChange = {},
                    inputField = {
                        InputField(
                            query = search.query,
                            onQueryChange = search.onQueryChange,
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            label = stringResource(R.string.chat_search_hint),
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        )
                    },
                    content = {},
                )
                Text(
                    text = if (search.hasMatches) {
                        search.positionLabel
                    } else {
                        stringResource(R.string.chat_search_no_match)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (search.hasMatches) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = search.onPrevious) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.chat_search_previous),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = search.onNext) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_search_next),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = search.onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_search_close),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (search.hiddenMatchCount > 0) {
                Text(
                    text = pluralStringResource(
                        if (search.hiddenMatchesAreReplies) {
                            R.plurals.chat_search_hidden_replies
                        } else {
                            R.plurals.chat_search_hidden_matches
                        },
                        search.hiddenMatchCount,
                        search.hiddenMatchCount,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    todoPlan: AgentTodoPlan?,
    taskLedger: AgentTaskLedger?,
    goal: AgentGoal?,
    goalScopeId: String?,
    pendingAsk: AgentAskUi?,
    onAnswerAsk: (String) -> Unit,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    inputSyncVersion: Int = 0,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    isStreaming: Boolean,
    isPaused: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    messageEdit: MessageEditUiState?,
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    messageStartIndex: Int = 0,
    hasEarlierMessages: Boolean = false,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    missingPermissionIds: Set<String> = emptySet(),
    /** 权限检查是否已经出结果；未出结果时不能拿空的 id 集合去清忽略记录。 */
    permissionIssuesKnown: Boolean = false,
    /** 当前会话 id：会话内的提示按它分开记；null 表示新建会话，不落盘。 */
    noticeScopeKey: String? = null,
    journalDeferred: Boolean = false,
    messagesDeferred: Boolean = false,
    systemExit: AppExitCause = AppExitCause.NONE,
    onDismissSystemExit: () -> Unit = {},
    hiddenMessageCount: Int = 0,
    viewMode: ChatViewMode = ChatViewMode.ALL,
    onShowAllMessages: () -> Unit = {},
    searchUi: ChatSearchUi? = null,
    /** 本次搜索的全部命中 id，用于在折叠块摘要上标出「本块内 N 条命中」。 */
    searchMatchIds: Set<String> = emptySet(),
    /** 当前跳转到的那一条，渲染时高亮。 */
    highlightMessageId: String? = null,
    scrollRequest: ChatScrollRequest? = null,
    /** 会话里的回合总数（= 提问次数）；小于 2 时不显示回合导航。 */
    turnCount: Int = 0,
    /** 搜索激活时隐藏回合导航：两种跳转都会滚列表，同时出现会让人不知道点的是哪个。 */
    turnNavigationEnabled: Boolean = true,
    onJumpToTurn: (String) -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenRunDetail: (String) -> Unit = {},
    currentBrowserMessageId: String?,
    modifier: Modifier = Modifier,
) {
    val missingPermissionCount = missingPermissionIds.size
    val surfaceColor = MiuixTheme.colorScheme.surface
    val frostEnabled = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
    val messageBackdrop = rememberLayerBackdrop {
        // Backdrop 必须包含不透明底色，否则文字边缘模糊到透明区域时会出现黑边。
        drawRect(surfaceColor)
        drawContent()
    }

    val context = LocalContext.current
    // 资源从 LocalResources 取：LocalContext.getString 不感知 Configuration 变化，
    // 切语言/深色后可能拿到旧值（lint LocalContextGetResourceValueCall）。
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeUndoScope = rememberCoroutineScope()
    var noticeUndoJob by remember { mutableStateOf<Job?>(null) }
    var noticeDismissal by remember {
        mutableStateOf(
            ChatStatusNoticeDismissal(
                dismissedPermissionIssueIds = ChatNoticeDismissalPrefs.dismissedPermissionIssueIds(context),
                // 会话内的提示按会话记：切走再回来、去设置页转一圈回来，都不该又弹一次。
                dismissedKeys = noticeScopeKey
                    ?.let { ChatNoticeDismissalPrefs.dismissedKeys(context, it) }
                    .orEmpty(),
            ),
        )
    }
    // 统一入口：改状态 + 落盘，并返回「改之前」的状态（撤销要用）。null = 没变化。
    val applyNoticeDismissal: (ChatStatusNoticeDismissal) -> ChatStatusNoticeDismissal? = { next ->
        val previous = noticeDismissal
        if (next == previous) {
            null
        } else {
            noticeDismissal = next
            if (next.dismissedPermissionIssueIds != previous.dismissedPermissionIssueIds) {
                ChatNoticeDismissalPrefs.setDismissedPermissionIssueIds(context, next.dismissedPermissionIssueIds)
            }
            if (noticeScopeKey != null && next.dismissedKeys != previous.dismissedKeys) {
                ChatNoticeDismissalPrefs.setDismissedKeys(context, noticeScopeKey, next.dismissedKeys)
            }
            previous
        }
    }
    // 条件消失后清掉记忆：同一条提示下次真的再发生时还要能提醒。
    // permissionIssuesKnown 也作为 key：首次检查完成时要重新对账一次。
    LaunchedEffect(
        permissionIssuesKnown,
        missingPermissionIds,
        messagesDeferred,
        journalDeferred,
        hiddenMessageCount,
    ) {
        applyNoticeDismissal(
            noticeDismissal.reconcile(
                currentPermissionIssueIds = missingPermissionIds,
                permissionIssuesKnown = permissionIssuesKnown,
                messagesDeferred = messagesDeferred,
                journalDeferred = journalDeferred,
                hiddenMessageCount = hiddenMessageCount,
            ),
        )
    }
    val visibleStatusNotices = chatStatusNotices(
        missingPermissionCount = missingPermissionCount,
        journalDeferred = journalDeferred,
        messagesDeferred = messagesDeferred,
        hiddenMessageCount = hiddenMessageCount,
        systemExit = systemExit,
    ).filterNot { notice -> noticeDismissal.hides(notice, missingPermissionIds, viewMode) }
    val dismissNotice: (ChatStatusNoticeKind) -> Unit = { notice ->
        val before = noticeDismissal
        val after = before.dismiss(notice, missingPermissionIds, viewMode)
        if (after != before && applyNoticeDismissal(after) != null) {
            // 整行可点本来就容易误触：给一次撤销，别让误点变成「永久关掉且找不回来」。
            noticeUndoJob?.cancel()
            noticeUndoJob = noticeUndoScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.chat_notice_dismissed),
                    actionLabel = resources.getString(R.string.chat_notice_undo),
                    withDismissAction = false,
                    // 10 秒而不是 4 秒：撤销的价值全在窗口长度上 —— 整行可点太容易误触，
                    // 用户往往要先反应过来「刚才那条不见了」才去找撤销按钮。
                    duration = SnackbarDuration.Long,
                )
                // 撤销只回滚这一次关掉的那一条：期间关掉的其它提示保留，
                // 否则会出现「点了撤销，另一条又弹回来」。
                if (result == SnackbarResult.ActionPerformed) {
                    applyNoticeDismissal(noticeDismissal.undoDismissal(before = before, after = after))
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            Column {
                ChatStatusNoticeBar(
                    notices = visibleStatusNotices,
                    missingPermissionCount = missingPermissionCount,
                    hiddenMessageCount = hiddenMessageCount,
                    systemExit = systemExit,
                    onDismissSystemExit = onDismissSystemExit,
                    viewMode = viewMode,
                    onShowAllMessages = onShowAllMessages,
                    onOpenPermissions = onOpenPermissions,
                    onDismiss = dismissNotice,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                AgentChatBottomBar(
                todoPlan = todoPlan,
                taskLedger = taskLedger,
                goal = goal,
                goalScopeId = goalScopeId,
                pendingAsk = pendingAsk,
                onAnswerAsk = onAnswerAsk,
                messageBackdrop = messageBackdrop.takeIf { frostEnabled },
                input = input,
                inputSyncVersion = inputSyncVersion,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                isPaused = isPaused,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                messageEdit = messageEdit,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onTogglePause = onTogglePause,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                )
            }
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        // 消息还没载入时不去显示「空对话 + 推荐提问」：那会和底部的「正在载入」互相矛盾。
        if (!hasMessages && !messagesDeferred) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else if (visibleMessages.isEmpty()) {
            // 有消息，但被当前视图全收起来了：不能落到 EmptyChatState（那会显示「推荐提问」，
            // 等于告诉用户这段对话是空的），也不能留一屏空白。
            ViewFilteredEmptyState(
                mode = viewMode,
                onShowAll = onShowAllMessages,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
            // 搜索条固定在消息区上方而不是浮层：浮层会盖住第一屏内容，而用户恰恰要在这
            // 一屏里看命中位置。
            searchUi?.let { search ->
                ChatSearchBar(search = search)
            }
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                scrollState = scrollState,
                scrollRequest = scrollRequest,
                searchMatchIds = searchMatchIds,
                highlightMessageId = highlightMessageId,
                turnCount = turnCount,
                turnNavigationEnabled = turnNavigationEnabled,
                onJumpToTurn = onJumpToTurn,
                isStreaming = isStreaming,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = onBottomAnchorChanged,
                messageStartIndex = messageStartIndex,
                hasEarlierMessages = hasEarlierMessages,
                historyLoadState = historyLoadState,
                onLoadEarlierMessages = onLoadEarlierMessages,
                onHistoryViewportChanged = onHistoryViewportChanged,
                onSuggestionClick = onSuggestionClick,
                onOpenBrowser = onOpenBrowser,
                onOpenRunDetail = onOpenRunDetail,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onSelectReplyCandidate = onSelectReplyCandidate,
                messageActionsEnabled = !isStreaming && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (frostEnabled) Modifier.layerBackdrop(messageBackdrop) else Modifier),
            )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    scrollRequest: ChatScrollRequest? = null,
    /** 本次搜索的全部命中 id；折叠块据此在摘要上标出本块命中条数。 */
    searchMatchIds: Set<String> = emptySet(),
    /** 当前跳转到的那一条，渲染时高亮；为空表示没有正在进行的搜索。 */
    highlightMessageId: String? = null,
    turnCount: Int = 0,
    turnNavigationEnabled: Boolean = true,
    onJumpToTurn: (String) -> Unit = {},
    isStreaming: Boolean,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    messageStartIndex: Int = 0,
    hasEarlierMessages: Boolean = false,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    assistantOverlay: Boolean = false,
    onSuggestionClick: (String) -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenRunDetail: (String) -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    messageActionsEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    modifier: Modifier = Modifier,
) {
    // 上一版条目只作复用比对，不参与组合观察。
    val previousTimelineEntries = remember { arrayOf(emptyList<AgentTimelineEntry>()) }
    val timelineEntries = remember(visibleMessages) {
        visibleMessages.toTimelineEntries(previous = previousTimelineEntries[0])
    }
    SideEffect { previousTimelineEntries[0] = timelineEntries }
    LaunchedEffect(scrollRequest?.token) {
        val request = scrollRequest ?: return@LaunchedEffect
        // 过程消息在时间线里被折叠进同一个工作过程块，命中其中任意一条都滚到该块首行 ——
        // 单条过程消息没有独立 index，硬找会得到 -1 而静默不跳。
        val index = timelineEntries.indexOfFirst { entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> entry.message.id == request.messageId
                is AgentTimelineEntry.WorkProcess -> entry.messages.any { it.id == request.messageId }
            }
        }
        if (index >= 0) {
            // 远距离直接定位，近距离（同一屏附近）保留动画：动画时长随距离增长，
            // 8,435 条消息的会话里跨 192 项用直接定位实测 97ms，而跨 13 项播放动画要 807ms
            // （两者目标条目不同，不能直接相减，但动画跨几百项会明显更久）。
            if (abs(index - scrollState.firstVisibleItemIndex) > 40) {
                scrollState.scrollToItem(index)
            } else {
                scrollState.animateScrollToItem(index)
            }
        }
    }
    val activeWorkProcessKey = if (isStreaming) {
        (timelineEntries.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
    } else {
        null
    }
    // 工作过程在回合结束后什么时候收（对齐 dsh 的 collapseTiming）：
    // - completion：结束即收（默认，既有行为）；
    // - next-input：先保持展开，等用户真的发出下一条消息再收。
    // 用「这一块后面还有没有用户消息」直接推导，不引入额外状态：重启、切会话、
    // 编辑历史都不会错位，也不需要记「刚才哪一块是刚结束的」。
    val workCollapseTiming = rememberAgentWorkCollapseTiming()
    // 工作细节档位（对齐 dsh 的 transcriptView）：决定「收不收」「有没有折叠行」「运行中给不给细节」。
    // 与上面的折叠时机是两个独立维度 —— 档位管看多少，时机管什么时候收。
    val transcriptViewMode = rememberAgentTranscriptViewMode()
    val transcriptPolicy = remember(transcriptViewMode) {
        AgentTranscriptViewPolicy.forMode(transcriptViewMode)
    }
    val workKeysWithLaterUserMessage = remember(timelineEntries) {
        val result = HashSet<String>()
        var laterUserMessage = false
        for (index in timelineEntries.indices.reversed()) {
            when (val entry = timelineEntries[index]) {
                is AgentTimelineEntry.WorkProcess -> if (laterUserMessage) result += entry.key
                is AgentTimelineEntry.Message ->
                    if (entry.message is UserMessageUi) laterUserMessage = true
            }
        }
        result
    }
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    // 边界项（「载入更早」）占一个 item 索引，末尾哨兵必须跟着偏移，
    // 否则有更早消息时可滚到的地方比实际少一条，底部对不齐。
    val earlierMessagesItemCount = if (hasEarlierMessages) 1 else 0
    val bottomItemIndex = timelineEntries.size + earlierMessagesItemCount
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember(scrollState) {
        derivedStateOf { !scrollState.canScrollForward }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()

    /*
     * 底部状态条（权限提醒 / 系统退出 / 视图过滤）出现时，Scaffold 的 bottom inset 变大，
     * 消息区随之变矮。LazyColumn 以 firstVisibleItem 为锚，视口一矮，最后一条消息就被
     * 裁在输入器后面 —— 用户看到的是「状态条盖住了刚说的话」。
     *
     * 只在 inset 变大、且本来就在跟底、且用户没在拖时补一次贴底：
     * 上滑阅读历史时被拉回底部，比被遮一行更让人恼火。
     */
    val bottomInsetPx = with(LocalDensity.current) { bottomInset.toPx() }
    var previousBottomInsetPx by remember { mutableStateOf(bottomInsetPx) }
    LaunchedEffect(bottomInsetPx) {
        val grew = bottomInsetPx > previousBottomInsetPx + 0.5f
        previousBottomInsetPx = bottomInsetPx
        if (!grew || !keepBottomAnchored || isUserDragging) return@LaunchedEffect
        // 新的视口尺寸要先完成一次布局，否则滚到的还是旧的可滚动范围。
        withFrameNanos { }
        scrollState.requestScrollToItem(bottomItemIndex)
    }

    // 命中行可能落在折叠块内部：列表只能滚到「块」这一项，块内精确位置得自己量。
    // 不用 BringIntoViewRequester —— 条目高达数万像素时实测会落到命中行旁边（docs 里有记录）。
    var hitRowBounds by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var viewportBounds by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(highlightMessageId) {
        hitRowBounds = null
        if (highlightMessageId == null) return@LaunchedEffect
        // 展开动画与「跳到块首」都还在跑时量到的位置会漂移，因此按当时的真实位置
        // 反复校正；一旦落进视口差值为 0，立刻停手，不再动用户的位置。
        repeat(HitRowAlignAttempts) { attempt ->
            delay(if (attempt == 0) HitRowAlignFirstDelayMs else HitRowAlignStepDelayMs)
            val row = hitRowBounds ?: return@repeat
            val viewport = viewportBounds ?: return@repeat
            val margin = HitRowAlignMarginDp * densityScale
            // 命中行不在视口内时直接对齐到视口顶部（而不是「刚露一点」）：连续点下一条时
            // 阅读位置保持一致，也避免每次都只挪几像素。
            val visible = row.first >= viewport.first + margin && row.second <= viewport.second - margin
            if (visible) return@LaunchedEffect
            val delta = row.first - (viewport.first + margin)
            if (abs(delta) <= 1f) return@LaunchedEffect
            scrollState.scrollBy(delta)
        }
    }

    LaunchedEffect(
        isUserDragging,
        isAtBottom,
        keepBottomAnchored,
    ) {
        val next = resolveKeepBottomAnchored(
            current = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isAtBottom = isAtBottom,
        )
        if (next != keepBottomAnchored) {
            onBottomAnchorChanged(next)
        }
    }

    val tailMessage = visibleMessages.lastOrNull() as? AgentMessageUi
    val currentTailMessage by rememberUpdatedState(tailMessage)
    // 显现进度属于尾部条目的高频状态；列表作用域只观察离散化后的布尔值。
    val isTailRendering by remember {
        derivedStateOf {
            currentTailMessage?.let { message ->
                streamingMarkdownStates[message.id]?.let { state ->
                    state.revealedContent != message.content
                }
            } == true
        }
    }
    var isBottomSettling by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量再收口跟底。
            withFrameNanos { }
            withFrameNanos { }
            snapshotFlow { !scrollState.canScrollForward }.first { it }
            isBottomSettling = false
        }
    }

    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(
        bottomItemIndex,
        keepBottomAnchored,
        isUserDragging,
        isStreaming,
    ) {
        if (shouldRequestInitialBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserDragging,
            )
        ) {
            scrollState.requestScrollToItem(bottomItemIndex)
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            requestIndex?.let { targetIndex ->
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                return@let
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    scrollBy(step)
                    consumedStep = step
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    Box(modifier = modifier.clipToBounds()) {
        // 时间线会把连续过程消息折叠成「工作过程块」，所以回合归属按条目标记算，而不是按消息序 ——
        // 按消息算会让 index 与 firstVisibleItemIndex 对不上，读数永远差几格。
        val turnStarts = remember(timelineEntries) {
            timelineEntries.map { entry ->
                entry is AgentTimelineEntry.Message && entry.message is UserMessageUi
            }
        }
        val entryTurns = remember(turnStarts) { turnIndexByEntryStarts(turnStarts) }
        val currentTurn by remember(scrollState, entryTurns, isAtBottom) {
            derivedStateOf {
                currentTurnIndex(entryTurns, scrollState.firstVisibleItemIndex, atEnd = isAtBottom)
            }
        }
        val historyPaging = rememberChatHistoryPaging(
            scrollState = scrollState,
            entries = timelineEntries,
            messageStartIndex = messageStartIndex,
            messageCount = visibleMessages.size,
            hasEarlier = hasEarlierMessages,
            loadState = historyLoadState,
            enabled = !isStreaming && editTargetMessageId == null,
            canReleasePages = !isTailRendering && !isBottomSettling,
            keepBottomAnchored = keepBottomAnchored,
            onBottomAnchorChanged = onBottomAnchorChanged,
            onLoadEarlier = onLoadEarlierMessages,
            onViewportChanged = onHistoryViewportChanged,
        )
        LazyColumn(
            state = scrollState,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(historyPaging.scrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInWindow().y
                    viewportBounds = top to (top + coordinates.size.height)
                },
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = bottomInset + 16.dp,
            ),
            overscrollEffect = null,
        ) {
            if (hasEarlierMessages) {
                item(key = ChatHistoryBoundaryKey) {
                    AgentHistoryLoadStatus(
                        state = historyLoadState,
                        enabled = !isStreaming && editTargetMessageId == null,
                        onRetry = historyPaging.retry,
                    )
                }
            }
            items(
                items = timelineEntries,
                key = { it.key },
                contentType = { it.contentType() },
            ) { entry ->
                val itemModifier = Modifier.animateItem(
                    fadeInSpec = tween(durationMillis = 180),
                    placementSpec = null,
                    // 历史轮次被编辑、删除或重新生成时必须立即退出；退出动画会让已从
                    // 状态中裁掉的旧消息继续绘制，并与同位置的新流式消息短暂重叠。
                    fadeOutSpec = null,
                )
                // 只有独立消息才整条上色；工作过程块里命中时由块内那一行承担标记
                // （整块上色会让「当前是哪一条」重新不可知），块本身只用提示行说明。
                val isCurrentHit =
                    (entry as? AgentTimelineEntry.Message)?.message?.id == highlightMessageId
                val blockHitCount = (entry as? AgentTimelineEntry.WorkProcess)
                    ?.let { process -> countProcessBlockMatches(process.messages, searchMatchIds) }
                    ?: 0
                val blockHitPosition = (entry as? AgentTimelineEntry.WorkProcess)
                    ?.let { process ->
                        processBlockHitPosition(process.messages, searchMatchIds, highlightMessageId)
                    }
                    ?: 0
                // 命中必须留下可见标记：命中落在折叠块内时视口几乎不动，没有标记用户
                // 会以为「下一个」没反应。淡底 + 左侧竖条，当前那一条更重。
                val hitAccent = MiuixTheme.colorScheme.primary
                // 只有当前那一条画底色和竖条：其它块也画淡底会让「当前命中」失去焦点，
                // 那些块只在摘要下多一行「本块内 N 条匹配」。
                val entryModifier = if (isCurrentHit) {
                    itemModifier.drawBehind {
                        val corner = CornerRadius(12.dp.toPx())
                        drawRoundRect(
                            color = hitAccent.copy(alpha = if (isCurrentHit) 0.14f else 0.07f),
                            cornerRadius = corner,
                        )
                        val barWidth = 3.dp.toPx()
                        drawRoundRect(
                            color = hitAccent.copy(alpha = if (isCurrentHit) 0.85f else 0.38f),
                            size = Size(barWidth, size.height),
                            cornerRadius = CornerRadius(barWidth / 2f),
                        )
                    }
                } else {
                    itemModifier
                }
                when (entry) {
                    is AgentTimelineEntry.Message -> {
                        val message = entry.message
                        ChatMessageItem(
                            message = message,
                            assistantOverlay = assistantOverlay,
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { it.isStreaming || streamingMarkdownStates.containsKey(it.id) }
                                ?.let { agentMessage ->
                                    streamingMarkdownStates.getOrPut(agentMessage.id) {
                                        StreamingMarkdownState()
                                    }
                                },
                            onSuggestionClick = onSuggestionClick,
                            onOpenBrowser = onOpenBrowser,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            showCopyAction = message !is AgentMessageUi ||
                                message.characterEditable || message.id in finalResultMessageIds,
                            showMessageActions = message.id in finalResultMessageIds ||
                                (message is AgentMessageUi && message.characterEditable),
                            messageActionsEnabled = messageActionsEnabled,
                            isEditing = message.id == editTargetMessageId,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onSelectReplyCandidate = onSelectReplyCandidate,
                            modifier = entryModifier,
                        )
                    }

                    is AgentTimelineEntry.WorkProcess -> {
                        entry.messages.forEach { message ->
                            if (message is ThinkingMessageUi && message.isStreaming) {
                                streamingMarkdownStates.getOrPut(message.id) {
                                    StreamingMarkdownState()
                                }
                            }
                        }
                        AgentWorkProcess(
                            id = entry.key,
                            active = entry.key == activeWorkProcessKey,
                            autoExpanded = AgentTranscriptPresentation.autoExpand(
                                policy = transcriptPolicy,
                                timing = workCollapseTiming,
                                active = entry.key == activeWorkProcessKey,
                                hasUserMessageAfter = entry.key in workKeysWithLaterUserMessage,
                            ),
                            groupHeaderVisible = AgentTranscriptPresentation.grouped(
                                policy = transcriptPolicy,
                                timing = workCollapseTiming,
                                active = entry.key == activeWorkProcessKey,
                                hasUserMessageAfter = entry.key in workKeysWithLaterUserMessage,
                            ),
                            liveProcessDetail = transcriptPolicy.liveProcessDetail,
                            messages = entry.messages,
                            assistantOverlay = assistantOverlay,
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = streamingMarkdownStates,
                            modifier = entryModifier,
                            searchHitCount = blockHitCount,
                            searchHitPosition = blockHitPosition,
                            highlightMessageId = highlightMessageId,
                            onHitRowBounds = { top, bottom -> hitRowBounds = top to bottom },
                            onOpenRunDetail = onOpenRunDetail,
                        )
                    }
                }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        if (turnNavigationEnabled && turnCount > 1) {
            // currentTurn 来自上一帧的滚动状态，turnCount 来自当前帧的消息列表。编辑消息会把
            // 后续消息截掉（visibleMessagesForEdit），回合数可能从 21 掉到 5，而 currentTurn
            // 还是上一帧的 19 —— 直接拿它算目标会整体越界，「上一个 / 下一个」变成哑按钮，
            // 箭头却因为显示端夹取而亮着。这里按当前 total 夹取，与显示口径保持一致。
            val clampedTurn = currentTurn?.coerceIn(0, turnCount - 1)
            ChatTurnNavigator(
                currentTurn = clampedTurn,
                total = turnCount,
                onPrevious = {
                    moveTurnIndex(clampedTurn, turnCount, -1)?.let { target ->
                        chatTurnAnchorIds(visibleMessages).getOrNull(target)?.let(onJumpToTurn)
                    }
                },
                onNext = {
                    moveTurnIndex(clampedTurn, turnCount, 1)?.let { target ->
                        chatTurnAnchorIds(visibleMessages).getOrNull(target)?.let(onJumpToTurn)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
            )
        }

        AnimatedVisibility(
            visible = !keepBottomAnchored && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 12.dp),
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
        ) {
            IconButton(
                // 与输入框同一套浮起层次：消息背景接近按钮底色时仍能一眼认出。
                modifier = Modifier
                    .dropShadow(
                        shape = CircleShape,
                        shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f),
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        cornerRadius = IconButtonDefaults.CornerRadius,
                    ),
                onClick = {
                    onBottomAnchorChanged(true)
                    coroutineScope.launch {
                        scrollState.animateScrollToItem(bottomItemIndex)
                    }
                },
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                minWidth = 40.dp,
                minHeight = 40.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(R.string.ui_back_to_bottom_32282e),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

/** 复用普通消息条目时，一次匹配最多扫过多少个 previous 条目（含跳过的过程块）。 */
private const val MESSAGE_REUSE_SCAN_BUDGET = 32

/** 复用普通消息条目时，一次匹配最多比较多少条普通消息。 */
private const val MESSAGE_REUSE_COMPARE_BUDGET = 8

/**
 * 投影层只替换真正变化的消息实例；工作过程分组若成员实例全部未变，就复用上一版条目，
 * 让已结束的分组在正文流式刷新时保持参数同一性并跳过重组。
 */
internal fun List<AgentChatMessageUi>.toTimelineEntries(
    previous: List<AgentTimelineEntry> = emptyList(),
): List<AgentTimelineEntry> = buildList(capacity = this@toTimelineEntries.size) {
    // 容量直接给消息条数：条目数不会超过消息数，免掉 ArrayList 扩容的多次复制。
    // 索引只建在「工作过程块」上，而且只用块首条消息的 id 作键：规模 = 块数（几十），
    // 不是消息总数（上万）。旧实现对**每个**消息 id 建 Pair + HashMap 节点，
    // 长会话流式刷新时每一帧都要分配十几万字节，实测在 256MB 堆上直接崩：
    //   java.lang.OutOfMemoryError at java.util.HashMap.resize
    //     → kotlin.collections.MapsKt.toMap → AgentChatBodyKt.toTimelineEntries
    //   （data_app_crash 2026-10-31 07:17，8k+ 条消息的会话）
    val previousWorkEntries = previous.filterIsInstance<AgentTimelineEntry.WorkProcess>()
    val previousWorkByFirstId = previousWorkEntries
        .mapNotNull { entry -> entry.messages.firstOrNull()?.let { message -> message.id to entry } }
        .toMap()
    val workMessages = mutableListOf<AgentChatMessageUi>()

    // 普通消息条目也复用：流式刷新时只有最后一条在变，前面几千条的包装对象没必要每帧重建。
    // 用单调游标扫 previous，**不建 Map** —— 按消息 id 建索引正是 §32.37 那次 OOM 的成因。
    // 匹配不上时**不推进游标**，只向前看有限步：
    //   · 前插历史、或中间某条被新实例顶替 → 当前位置不等，但游标留在原处，下一条照常复用；
    //   · previous 头部少了几条（窗口滑动/删除）→ 前瞻窗口内能找到对齐点，从那里继续复用。
    // 找不到就新建：最坏只是少复用一次，输出的内容始终取自当前 message，语义不变。
    var previousMessageCursor = 0

    fun messageEntry(message: AgentChatMessageUi): AgentTimelineEntry.Message {
        var scan = previousMessageCursor
        var scanned = 0
        var compared = 0
        while (scan < previous.size &&
            scanned < MESSAGE_REUSE_SCAN_BUDGET &&
            compared <= MESSAGE_REUSE_COMPARE_BUDGET
        ) {
            val candidate = previous[scan]
            scan++
            scanned++
            if (candidate !is AgentTimelineEntry.Message) continue
            compared++
            if (candidate.message === message) {
                previousMessageCursor = scan
                return candidate
            }
        }
        return AgentTimelineEntry.Message(message)
    }

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        // 历史页可能从同一工作过程的中间开始；前插补齐后保留已显示分组的身份和滚动锚点。
        val first = workMessages.first()
        // 先按块首 id 直接命中；未命中（前插让块首变成新消息）再退回按块扫描，
        // 保证「同一工作过程前插后仍保留原 key」的语义不变。扫描只走块列表，不走消息列表。
        // 快路径：块首 id 命中，且上一版块是本块的首段或尾段 —— 只比实例，不分配集合。
        // 流式追加（块首不变）、前插历史（尾部不变）都走这条路；旧实现每帧给整块消息建 id 集合，
        // 实测 2000 条过程消息的会话里占 138KB/次（§32.39.14）。
        val alignedEntry = previousWorkByFirstId[first.id]
            ?.takeIf { entry -> isPrefixOrSuffixRun(entry.messages, workMessages) }
        // 慢路径：前插让块首变成新消息、或两段合并时，仍回退到 id 集合，语义与旧实现一致。
        val previousEntry = alignedEntry ?: run {
            val messageIds = workMessages.mapTo(HashSet()) { it.id }
            previousWorkEntries.firstOrNull { entry -> entry.messages.any { it.id in messageIds } }
                ?.takeIf { entry -> entry.messages.all { it.id in messageIds } }
        }
        val key = previousEntry?.key ?: "work-${first.id}"
        val reusable = previousEntry?.takeIf { entry ->
            entry.messages.size == workMessages.size &&
                entry.messages.indices.all { index -> entry.messages[index] === workMessages[index] }
        }
        add(reusable ?: AgentTimelineEntry.WorkProcess(key = key, messages = workMessages.toList()))
        workMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        if (message.isWorkProcessMessage()) {
            workMessages += message
        } else {
            flushWorkProcess()
            add(messageEntry(message))
        }
    }
    flushWorkProcess()
}

/**
 * short 是否与 long 的首段或尾段按**实例**逐一相同。只做指针比较，不分配集合；
 * 用来在不建 id 集合的前提下确认「上一版的工作过程块就是这一块的首/尾子串」。
 */
private fun isPrefixOrSuffixRun(
    short: List<AgentChatMessageUi>,
    long: List<AgentChatMessageUi>,
): Boolean {
    if (short.isEmpty() || short.size > long.size) return false
    val suffixStart = long.size - short.size
    var prefixMatches = true
    var suffixMatches = true
    for (index in short.indices) {
        if (prefixMatches && short[index] !== long[index]) prefixMatches = false
        if (suffixMatches && short[index] !== long[suffixStart + index]) suffixMatches = false
        if (!prefixMatches && !suffixMatches) return false
    }
    return true
}

private fun AgentTimelineEntry.contentType(): Any = when (this) {
    is AgentTimelineEntry.WorkProcess -> AgentTimelineEntry.WorkProcess::class
    is AgentTimelineEntry.Message -> message::class
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式传输期间当前轮次尚未结束，最后一轮不标记，等传输结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            else -> Unit
        }
    }
    if (!isStreaming) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

@Composable
private fun AgentChatBottomBar(
    todoPlan: AgentTodoPlan?,
    taskLedger: AgentTaskLedger?,
    goal: AgentGoal?,
    goalScopeId: String?,
    pendingAsk: AgentAskUi?,
    onAnswerAsk: (String) -> Unit,
    messageBackdrop: LayerBackdrop?,
    input: String,
    inputSyncVersion: Int = 0,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    messageEdit: MessageEditUiState?,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        if (messageBackdrop != null) {
            val blurColors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.72f))
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatBottomFrostHeight)
                    // DstIn 让真实磨砂在顶部透明、靠近输入框时逐渐变实，消除硬裁切线。
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .textureBlur(
                        backdrop = messageBackdrop,
                        shape = RectangleShape,
                        blurRadius = 20f,
                        colors = blurColors,
                    ),
            )
        } else {
            // 空白主页沿用原来的轻微渐隐，不改变主页视觉。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MiuixTheme.colorScheme.surface,
                            ),
                        )
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            goal?.let { item ->
                AgentGoalCard(
                    goal = item,
                    scopeId = goalScopeId,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            todoPlan?.let { plan ->
                AgentTodoPlanCard(plan = plan, modifier = Modifier.padding(bottom = 8.dp))
            }
            taskLedger?.let { ledger ->
                AgentTaskLedgerCard(ledger = ledger, modifier = Modifier.padding(bottom = 8.dp))
            }
            pendingAsk?.let { ask ->
                AgentAskCard(ask = ask, onAnswer = onAnswerAsk, modifier = Modifier.padding(bottom = 8.dp))
            }
            AgentChatInputBar(
                input = input,
                inputSyncVersion = inputSyncVersion,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                isPaused = isPaused,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                isEditingMessage = messageEdit != null,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                preserveFollowingMessages = messageEdit?.preserveFollowingMessages == true,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onTogglePause = onTogglePause,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val ChatBottomFrostHeight = 24.dp

/** 命中行对齐视口：先等展开动画起步，再按真实位置校正，最多尝试这么多次。 */
private const val HitRowAlignAttempts = 10
private const val HitRowAlignFirstDelayMs = 220L
private const val HitRowAlignStepDelayMs = 160L
private const val HitRowAlignMarginDp = 16f

/** 回合跳转后的落点高亮保留时长：够看清跳到哪，又不至于和搜索命中长期混在一起。 */
private const val TurnHighlightDurationMs = 1800L
private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.085f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 720f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 0.5f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 0.75f

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
): Boolean = when {
    isUserDragging -> isAtBottom
    isAtBottom -> true
    else -> current
}

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val character = LocalRoleplayChat.current
    val isCharacterConversation = character != null
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (character != null) {
                CharacterAvatar(
                    name = character.characterName,
                    avatarPath = character.avatarPath,
                    size = 88.dp,
                    shape = CircleShape,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = character.characterName,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_character_story_start),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(
                visible = showSuggestions && !isCharacterConversation,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 16.dp)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)
