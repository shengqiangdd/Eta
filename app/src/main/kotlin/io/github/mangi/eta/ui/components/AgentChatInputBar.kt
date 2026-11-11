package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentBusySendMode
import io.github.mangi.eta.agent.model.AgentBusySendPolicy
import io.github.mangi.eta.agent.model.AgentSubmitDedupPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.voice.SpeechDictationButton
import io.github.mangi.eta.ui.voice.SpeechInputFeedback
import io.github.mangi.eta.ui.voice.rememberSpeechInput
import androidx.compose.ui.text.TextRange
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.markdown.EditorialLineHeightStyle
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@Composable
internal fun AgentChatInputBar(
    input: String,
    // Compose 约定：modifier 必须是第一个可选参数，否则 lint ModifierParameter 报错。
    modifier: Modifier = Modifier,
    /** 外部写入 [input] 的序号：变化时强制同步一次，避免草稿/撤回在界面上不生效。 */
    inputSyncVersion: Int = 0,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    /** 跨轮用量累计；null 时胶囊 tooltip 不展示这一段。 */
    sessionUsage: AgentSessionUsageUi? = null,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    focusInputOnSync: Boolean = true,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onSubmit: (String) -> Unit,
    /** 运行中「插话」：送入当前 run、不结束它；空闲时退回 [onSubmit]。 */
    onSteerAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    /** 草稿同步：只写内存里的会话状态，不逐字触发聊天区重组。 */
    onDraftChange: (String) -> Unit = {},
    /** 待发送的引用；null 表示没有引用。 */
    quotedMessage: ComposerQuoteUi? = null,
    onClearQuote: () -> Unit = {},
) {
    // 繁忙时发送行为（对齐 dsh 的 BusyEnterBehavior）：运行中点击发送是排队还是插话。
    // 设置页写同一个键，这里监听变化即时生效，不需要重进页面。
    val context = LocalContext.current
    val agentPrefs = remember(context) { Prefs.localAgentPreferences(context) }
    var busySendMode by remember(agentPrefs) {
        mutableStateOf(AgentBusySendMode.fromId(Prefs.getLocalAgentString(Prefs.Keys.AGENT_BUSY_SEND_MODE)))
    }
    DisposableEffect(agentPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_BUSY_SEND_MODE) {
                busySendMode = AgentBusySendMode.fromId(
                    agentPrefs.getString(Prefs.Keys.AGENT_BUSY_SEND_MODE, null),
                )
            }
        }
        agentPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { agentPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) {
        // 聊天页恢复时不要把焦点自动交给输入框：系统会因此弹出输入法，
        // 对正在打游戏的用户是致命打扰。等用户点击输入框时再聚焦。
        focusManager.clearFocus(force = true)
    }
    val textFieldState = rememberTextFieldState(initialText = input)
    // 输入框是本地状态：普通打字刻意不回灌（否则每个字符都要重排整段聊天）。
    // 但「外部要把一段文字放进输入框」的场景（撤回排队消息、运行中拒绝保存编辑）
    // 只改 AppState.input 是看不见的 —— 用一个显式版本号触发一次同步。
    LaunchedEffect(inputSyncVersion) {
        if (textFieldState.text.toString() != input) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
    }
    // 草稿必须跟着会话走：切换会话、收到恢复提示时，输入框不能凭本地状态被清空。
    // 这里不让草稿进入 Compose 状态（否则每个字符都会重排整段聊天），只交给 AppState 的普通 map。
    val draftSink = LocalAgentDraftSink.current
    LaunchedEffect(textFieldState, draftSink) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            draftSink?.invoke(text)
            onDraftChange(text)
        }
    }
    // 虚拟屏中文输入走进程内注入：直接改 TextFieldState，不经过 IME、不碰剪贴板。
    DisposableEffect(Unit) {
        AgentComposerBridge.attach { text ->
            textFieldState.setTextAndPlaceCursorAtEnd(textFieldState.text.toString() + text)
        }
        onDispose { AgentComposerBridge.detach() }
    }
    val dictation = rememberSpeechInput { recognized ->
        textFieldState.edit {
            val range = selection
            replace(range.min, range.max, recognized)
            selection = TextRange(range.min + recognized.length)
        }
    }
    LaunchedEffect(isStreaming, isEditingMessage) { dictation.cancel() }
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val canSend = textFieldState.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    // 上一次真的交给发送路径的文本与时刻：只用来认「连点两下」的第二次，
    // 不参与任何显示（否则每个字符都要重组整段聊天）。
    var lastSubmit by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage) {
        // 编辑态由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        if (isEditingMessage || wasEditingMessage) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage) {
        if (isEditingMessage || (versionChanged && input.isNotEmpty() && focusInputOnSync)) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
    }

    LaunchedEffect(isStreaming, isCompacting) {
        // 只取消听写。过去这里在 isStreaming 变 true 时清空本地草稿：
        // 恢复一条「任务已中断」的 run 也会把 isStreaming 推成 true，
        // 用户正在打的字就被一条系统提示清掉了。清空只由真正消费掉草稿的地方做
        // （见 AgentChatInputBar 主按钮），这里不碰文字。
        dictation.cancel()
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        SpeechInputFeedback(dictation)
        AnimatedVisibility(
            visible = queuedMessages.isNotEmpty(),
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            AgentChatQueueStrip(
                queuedMessages = queuedMessages,
                isStreaming = isStreaming,
                isEditingMessage = isEditingMessage,
                onSteer = onSteerQueuedMessage,
                onRecall = { id -> onRecallQueuedMessage(id, textFieldState.text.toString()) },
                onRemove = onRemoveQueuedMessage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // 引用条：发送前能一眼看到「这次在回哪一句」，不需要时一键取消。
        quotedMessage?.takeUnless { it.isBlank }?.let { quote ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 26.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MiuixTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_quote_chip_title, quote.author),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = quote.excerpt.lineSequence().firstOrNull().orEmpty(),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onClearQuote,
                    minWidth = ChatInputActionSize,
                    minHeight = ChatInputActionSize,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_quote_clear),
                        modifier = Modifier.size(ChatInputActionIconSize),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    stringResource(R.string.chat_edit_preserve_following)
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 与输入框内正文同一起点（容器 10dp + 文本 8dp）。
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
        }

        val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
        val couiDark = CouiTheme.isDark
        val couiInputShape = remember { CouiShape(24.dp, 2.3f) }
        val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
        val miuixInputBg = if (!isDark && MiuixTheme.colorScheme.surfaceContainer == MiuixTheme.colorScheme.surface) {
            Color(0xFFF5F6F8)
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCoui) {
                            Modifier
                                .dropShadow(
                                    shape = couiInputShape,
                                    shadow = Shadow(
                                        radius = 16.dp,
                                        color = Color.Black,
                                        alpha = if (couiDark) 0.22f else 0.045f,
                                    ),
                                )
                                .clip(couiInputShape)
                                .background(
                                    if (couiDark) Color(0xFF1C1E22) else Color(0xFFF6F7F9),
                                    couiInputShape,
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (couiDark) Color(0x20FFFFFF) else Color(0x0E000000),
                                    shape = couiInputShape,
                                )
                        } else {
                            Modifier
                                .dropShadow(
                                    shape = InputContainerShape,
                                    shadow = Shadow(
                                        radius = 12.dp,
                                        color = Color.Black,
                                        alpha = if (isDark) 0.20f else 0.05f,
                                    ),
                                )
                                .squircleSurface(
                                    color = miuixInputBg,
                                    cornerRadius = 22.dp,
                                )
                                .squircleBorder(
                                    width = 0.5.dp,
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    cornerRadius = 22.dp,
                                )
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    // 占位文字与输入正文共用同一行高，首字输入时基线不跳动。
                    val inputTextStyle = MiuixTheme.textStyles.body1.copy(
                        lineHeight = 22.sp,
                        lineHeightStyle = EditorialLineHeightStyle,
                    )
                    val character = LocalRoleplayChat.current
                    if (textFieldState.text.isBlank()) {
                        Text(
                            text = when {
                                // 角色对话的占位文字跟随角色，避免“交给 Eta”打断沉浸感。
                                character != null && isStreaming ->
                                    stringResource(R.string.chat_character_typing, character.characterName)
                                character != null -> stringResource(R.string.chat_character_input_hint, character.characterName)
                                isStreaming -> stringResource(R.string.chat_eta_working)
                                else -> stringResource(R.string.chat_input_hint)
                            },
                            style = inputTextStyle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 1,
                            maxHeightInLines = 6,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            AgentAttachmentPickerButton(
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onAttachImage = onAttachImage,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            if (availableReasoningEfforts.isNotEmpty()) {
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    // 运行中也能改档位：变更会作为热切换下发给 Runtime，从下一个模型回合生效。
                                    enabled = true,
                                    popupAnchorTopPx = inputContainerTopPx,
                                    popupMaxHeight = thinkingPopupMaxHeight,
                                    onEffortChange = onReasoningEffortChange,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (showContextUsage) {
                        if (showContextUsage && contextUsage.contextTokens != null) {
                            AgentContextUsageButton(
                                usage = contextUsage,
                                onCompact = onCompactContext,
                                canCompact = canCompactContext && !isStreaming,
                                sessionUsage = sessionUsage,
                            )
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        SpeechDictationButton(dictation, enabled = !isStreaming)

                        Spacer(modifier = Modifier.width(2.dp))

                        AgentModelPickerButton(
                            state = modelPickerState,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                        )

                        // 参考 dsh：运行中只留两个显式按钮，不再有常驻小图标，也没有长按。
                        // 左键随状态在「暂停 / 继续 / 停止」之间切换，右键是主操作（停止或排队发送）。
                        // dsh 的输入栏没有任何长按（packages/client 全仓无 onLongPress），
                        // 隐藏手势既不可发现，也会和输入框的文本选择手势打架。
                        if (isStreaming) {
                            val runningActionGlyph = when {
                                isPaused -> ChatRunningActionGlyph.Resume
                                canSend -> ChatRunningActionGlyph.Stop
                                else -> ChatRunningActionGlyph.Pause
                            }
                            ChatActionButton(
                                description = stringResource(
                                    when (runningActionGlyph) {
                                        ChatRunningActionGlyph.Pause -> R.string.chat_pause_run
                                        ChatRunningActionGlyph.Resume -> R.string.chat_resume_run
                                        ChatRunningActionGlyph.Stop -> R.string.chat_stop
                                    }
                        val showStreamingSend = isStreaming && !isCompacting && canSend
                        val submitCurrentDraft: (Boolean) -> Unit = { interrupt ->
                            if (canSend) {
                                dictation.cancel()
                                val submittedText = textFieldState.text.toString()
                                textFieldState.clearText()
                                if (interrupt) {
                                    onInterruptAndSubmit(submittedText)
                                } else {
                                    onSubmit(submittedText)
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showStreamingSend,
                            enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.78f) + expandHorizontally(tween(160)),
                            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f) + shrinkHorizontally(tween(140)),
                        ) {
                            IconButton(
                                onClick = onStop,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(SendButtonVisualSize)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = stringResource(R.string.chat_stop),
                                        modifier = Modifier.size(StopIconSize),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        val showPrimaryStop = isStreaming && !showStreamingSend
                        val sendCircleSize = 32.dp
                        val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
                        val sendButtonColor by animateColorAsState(
                            targetValue = when {
                                showPrimaryStop -> MiuixTheme.colorScheme.onSurface
                                canSend -> MiuixTheme.colorScheme.primary
                                else -> ink.copy(alpha = if (isDark) 0.08f else 0.06f)
                            },
                            animationSpec = tween(durationMillis = 160),
                            label = "send_button_color",
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    enabled = isStreaming || canSend,
                                    onClick = {
                                        if (showPrimaryStop) {
                                            onStop()
                                        } else {
                                            submitCurrentDraft(false)
                                        }
                                    },
                                    onLongClick = if (showStreamingSend) {
                                        {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            submitCurrentDraft(true)
                                        }
                                    } else {
                                        null
                                    },
                                ),
                                onClick = {
                                    when (runningActionGlyph) {
                                        // 暂停与继续是同一个开关：停顿中再点一下就继续。
                                        ChatRunningActionGlyph.Pause,
                                        ChatRunningActionGlyph.Resume,
                                        -> onTogglePause()

                                        ChatRunningActionGlyph.Stop -> onStop()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = when (runningActionGlyph) {
                                        ChatRunningActionGlyph.Pause -> Icons.Rounded.Pause
                                        ChatRunningActionGlyph.Resume -> Icons.Rounded.PlayArrow
                                        ChatRunningActionGlyph.Stop -> Icons.Rounded.Stop
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(
                                        if (runningActionGlyph == ChatRunningActionGlyph.Stop) StopIconSize else SendIconSize
                                    ),
                                    tint = if (runningActionGlyph == ChatRunningActionGlyph.Resume) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }

                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        // 主按钮：空闲=发送；运行中+有草稿=按「繁忙时发送行为」排队或插话；
                        // 运行中+空输入=停止（暂停中也一样）。
                        val primaryGlyph = when {
                            isStreaming && !canSend -> ChatPrimaryGlyph.Stop
                            else -> ChatPrimaryGlyph.Send
                        }
                        // 暂停态与编辑态一律排队：插话虽不打断 run，但会改掉任务方向，
                        // 这两种状态下都不是用户想要的结果（暂停要保现场，编辑内容本身发不出去）。
                        val steersNow = AgentBusySendPolicy.steers(
                            streaming = isStreaming,
                            paused = isPaused,
                            editingMessage = isEditingMessage,
                            mode = busySendMode,
                        )
                        val primaryDescription = stringResource(
                            when {
                                primaryGlyph == ChatPrimaryGlyph.Stop -> R.string.chat_stop
                                isEditingMessage && preserveFollowingMessages -> R.string.chat_save_message
                                steersNow -> R.string.chat_send_steer
                                isStreaming -> R.string.chat_send_queue
                                else -> R.string.chat_send
                            }
                        )
                        ChatActionButton(
                            description = primaryDescription,
                            onClick = {
                                when {
                                    canSend -> {
                                        val submittedText = textFieldState.text.toString()
                                        // 刻意**不**在这里 clearText()：这一下只代表「点了发送」，
                                        // 不代表提交一定成立 —— 压缩中、消息未载入、只读归档会话、
                                        // 编辑态运行中这几条路径都会直接 return，先清再发就是当场吞字
                                        // （真机复现过：文字没了，也没有任何提示）。
                                        // 清空改由真正消费掉草稿的一侧做，并且抬 inputSyncVersion
                                        // 让输入框跟着同步（enqueue、发送成功、撤回排队消息）。
                                        // 代价是「点到发送」与「输入框真清空」之间有一帧，
                                        // 这一帧里连点两下会把同一段文字发两遍，所以按相同文本去重。
                                        val now = SystemClock.uptimeMillis()
                                        val duplicate = AgentSubmitDedupPolicy.isDuplicate(
                                            previousText = lastSubmit?.first,
                                            previousAtMs = lastSubmit?.second ?: 0L,
                                            text = submittedText,
                                            nowMs = now,
                                        )
                                        if (!duplicate) {
                                            lastSubmit = submittedText to now
                                            dictation.cancel()
                                            // 发送后收起键盘（dsh 0.2.2 的手机端做法）。本页刻意
                                            // 不自动弹键盘（见上方 LaunchedEffect），发送完就该把屏幕
                                            // 还给内容；否则输入法会一直压着聊天区，得手动按返回键。
                                            // 只在真正提交时收：被去重拦下的第二次连点不动键盘。
                                            keyboard?.hide()
                                            focusManager.clearFocus(force = true)
                                            if (steersNow) {
                                                onSteerAndSubmit(submittedText)
                                            } else {
                                                onSubmit(submittedText)
                                            }
                                        }
                                    }
                                    isStreaming -> onStop()
                                }
                            },
                            enabled = isStreaming || canSend,
                        ) {
                            // 保留统一的点击区域，仅让可见圆形与相邻操作图标保持同一尺寸。
                            val sendButtonColor by animateColorAsState(
                                targetValue = when {
                                    primaryGlyph == ChatPrimaryGlyph.Stop -> MiuixTheme.colorScheme.onSurface
                                    canSend -> MiuixTheme.colorScheme.primary
                                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                                },
                                animationSpec = tween(durationMillis = 160),
                                label = "send_button_color",
                            )
                            Box(
                                modifier = Modifier
                                    .size(sendCircleSize)
                                    .clip(CircleShape)
                                    .background(sendButtonColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = primaryGlyph,
                                    transitionSpec = {
                                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                            .togetherWith(
                                                fadeOut(tween(90)) +
                                                    scaleOut(tween(110), targetScale = 0.72f)
                                            )
                                    },
                                    label = "send_stop_icon",
                                ) { glyph ->
                                    Icon(
                                        imageVector = when (glyph) {
                                            ChatPrimaryGlyph.Stop -> Icons.Rounded.Stop
                                            ChatPrimaryGlyph.Send -> Icons.Rounded.ArrowUpward
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(
                                            if (glyph == ChatPrimaryGlyph.Stop) StopIconSize else SendIconSize
                                        ),
                                        modifier = Modifier.size(if (stopIcon) 12.dp else 17.dp),
                                        tint = when {
                                            glyph == ChatPrimaryGlyph.Stop -> MiuixTheme.colorScheme.surface
                                            canSend -> MiuixTheme.colorScheme.onPrimary
                                            else -> ink.copy(alpha = if (isDark) 0.28f else 0.24f)
                                        },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

}

/**
 * 思考强度胶囊：仅含开关两态时单击直接切换，支持多档强度时展开菜单并在胶囊上直接标明英文档位。
 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val interactive = enabled && options.size > 1
    val binaryToggleTarget = remember(options, effort) {
        if (options.size == 2 && ReasoningEffort.OFF in options) {
            options.firstOrNull { it != effort }
        } else {
            null
        }
    }
    val anchor = rememberCouiMenuAnchor()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(interactive) {
        if (!interactive) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }

    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val ink = colors.onSurface.copy(alpha = 1f)
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary
        } else {
            ink.copy(alpha = if (isDark) 0.58f else 0.54f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    val bgColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary.copy(alpha = if (isDark) 0.18f else 0.10f)
        } else {
            ink.copy(alpha = if (isDark) 0.06f else 0.038f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary.copy(alpha = if (isDark) 0.30f else 0.22f)
        } else {
            ink.copy(alpha = if (isDark) 0.10f else 0.065f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_border",
    )

    val chipLabel = when (effort) {
        ReasoningEffort.OFF -> stringResource(R.string.reasoning_chip_off)
        ReasoningEffort.DEFAULT -> stringResource(R.string.reasoning_chip_default)
        else -> effort.displayName
    }
    val accessibilityLabel = stringResource(R.string.chat_reasoning_effort, effort.displayName)
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .couiMenuAnchor(anchor)
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(bgColor, CircleShape)
                .border(0.5.dp, borderColor, CircleShape)
                .then(
                    if (isCoui) {
                        Modifier.couiButtonFeedback(isDark, interactionSource, enabled = interactive)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = interactive,
                    role = Role.Button,
                    onClick = {
                        if (binaryToggleTarget != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEffortChange(binaryToggleTarget)
                        } else {
                            anchor.open { showPopup = true }
                        }
                    },
                )
                .semantics {
                    contentDescription = accessibilityLabel
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier.size(14.5.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = chipLabel,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
            )
        }
        if (binaryToggleTarget == null) {
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    val menuItems = remember(options, effort) {
                        options.map { option ->
                            CouiMenuItem(
                                value = option,
                                text = option.displayName,
                                selected = option == effort,
                            )
                        }
                    }
                    Menu(
                        expanded = showPopup && interactive,
                        onDismissRequest = { showPopup = false },
                        items = menuItems,
                        onItemClick = { item ->
                            showPopup = false
                            onEffortChange(item.value)
                        },
                        anchor = anchor,
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    WindowListPopup(
                        show = showPopup && interactive && popupAnchorTopPx > 0,
                        popupPositionProvider = popupPositionProvider,
                        alignment = PopupPositionProvider.Align.TopStart,
                        onDismissRequest = { showPopup = false },
                        maxHeight = popupMaxHeight,
                    ) {
                        val dismiss = LocalDismissState.current
                        ListPopupColumn {
                            options.forEachIndexed { index, option ->
                                DropdownImpl(
                                    text = option.displayName,
                                    optionSize = options.size,
                                    isSelected = option == effort,
                                    index = index,
                                    onSelectedIndexChange = {
                                        onEffortChange(option)
                                        dismiss?.invoke()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val imagePreviewer = LocalChatImagePreviewer.current
    val itemBounds = remember { HashMap<String, Rect>() }
    var previousCount by remember { mutableIntStateOf(images.size) }
    LaunchedEffect(images.size) {
        if (images.size > previousCount && images.isNotEmpty()) {
            listState.animateScrollToItem(images.lastIndex)
        }
        previousCount = images.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = images,
            key = { _, item -> item.id },
        ) { index, image ->
            Box(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(durationMillis = 120),
                    )
                    .attachmentItemEntrance()
                    .size(64.dp)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) itemBounds[image.id] = coords.boundsInWindow()
                    }
                    .graphicsLayer {
                        alpha = if (imagePreviewer?.isPresenting(PendingImagesGroupKey, index) == true) 0f else 1f
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .then(
                        if (imagePreviewer != null) {
                            Modifier.clickable {
                                imagePreviewer.open(
                                    images = images.map { it.dataUrl },
                                    initialIndex = index,
                                    groupKey = PendingImagesGroupKey,
                                    sourceBoundsProvider = { page -> images.getOrNull(page)?.let { itemBounds[it.id] } },
                                    sourceCornerRadius = 12.dp,
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.chat_view_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 可见圆点保持小巧，触控区放大到右上角 32dp，避免误触缩略图。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clickable(interactionSource = null, indication = null) { onRemoveImage(image.id) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ui_remove_image_089db3),
                            modifier = Modifier.size(11.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** 运行中左侧按钮的图形：暂停、继续、停止。 */
private enum class ChatRunningActionGlyph { Pause, Resume, Stop }

/** 主按钮：发送（含排队）或停止。 */
private enum class ChatPrimaryGlyph { Send, Stop }

/**
 * 输入栏的显式动作按钮。
 *
 * dsh 的输入栏（ui-conversation/src/client/skeleton/InputBar.tsx）没有任何长按：
 * 运行中空输入时主按钮就是停止，有草稿时主按钮是发送，其余动作都是独立按钮。
 * 所以这里只做单击，不再用 combinedClickable 把「暂停 / 打断即发」藏进长按。
 */
@Composable
private fun ChatActionButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ChatInputActionSize)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
private const val PendingImagesGroupKey = "pending_images_strip"
