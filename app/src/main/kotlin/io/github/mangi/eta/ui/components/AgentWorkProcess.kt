package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 把连续的思考与工具调用收束为一条可展开的工作过程：折叠时只是一行摘要，展开后是
 * 左侧带连线的时间线。工作过程与正文同样不加卡片外壳，让最终回答保持视觉主角。
 */
/**
 * 当前会话的 run 是否在排队等手机控制权（另一个会话正占着屏幕）。
 *
 * 用 CompositionLocal 而不是层层加参数：这条信息属于「这一刻谁在操作手机」，
 * 与消息投影无关，穿过 ChatBody → Scaffold → 时间线四层只会污染签名。
 */
internal val LocalGuiControlWaiting = staticCompositionLocalOf { false }

@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    assistantOverlay: Boolean = false,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    /** 本次会话内搜索有多少条命中落在这一块里（0 表示没有搜索或没命中）。 */
    searchHitCount: Int = 0,
    /** 当前命中是本块内第几条（1 基，0 表示当前命中不在本块）。 */
    searchHitPosition: Int = 0,
    /** 当前命中的消息 id：落在本块内时自动展开，并单独标出那一行。 */
    highlightMessageId: String? = null,
    /** 命中行在窗口坐标系里的位置（顶、底）：列表滚到块首后由调用方据此做块内定位。 */
    onHitRowBounds: ((Float, Float) -> Unit)? = null,
    /** 打开本次运行的详情（完整工具参数/结果、耗时、重试）；null 表示不显示入口。 */
    onOpenRunDetail: ((String) -> Unit)? = null,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val steps = remember(messages) { messages.toWorkSteps() }
    // null 表示自动模式；用户点过之后只认手动选择。
    var manualExpanded by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }

    // 以“是否仍是本轮末尾的工作过程”判定收起，而不是 running：思考结束到工具开始之间
    // running 会短暂为 false，按它收起会造成反复开合。展开态在组合期同步推导，与正文
    // 条目插入落在同一帧，不经 LaunchedEffect 晚一帧收起。语音浮层空间有限，自动模式不展开。
    // 搜索命中落在本块内时自动展开：命中行收在折叠块里，用户等于看不到结果。
    // 每次命中变化都把「手动展开状态」清回自动，用户随后仍可以自己收起。
    val containsHighlight = messages.any { message -> message.id == highlightMessageId }
    LaunchedEffect(highlightMessageId) {
        if (containsHighlight) manualExpanded = null
    }
    val bodyVisible = manualExpanded ?: (containsHighlight || (active && !assistantOverlay))
    // 命中可能落在块内几百行之下：只滚到块首等于「找到了但看不到」。
    // 这里只负责把命中行的位置报上去，块内定位由列表层按视口边界做（见 AgentChatBody）。

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (assistantOverlay) 12.dp else 16.dp, vertical = 2.dp),
    ) {
        AgentWorkHeader(
            messages = messages,
            working = active || running,
            expanded = bodyVisible,
            searchHitCount = searchHitCount,
            searchHitPosition = searchHitPosition,
            onClick = { manualExpanded = !bodyVisible },
            onOpenRunDetail = onOpenRunDetail,
        )
        AnimatedVisibility(
            visible = bodyVisible,
            // 搜索命中在块内时不做展开动画：命中行要立刻可见，而且动画期间行高在变，
            // 量到的位置会漂移，块内定位会算错。
            enter = if (containsHighlight) {
                EnterTransition.None
            } else {
                fadeIn(tween(160)) + expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            },
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                steps.forEachIndexed { index, step ->
                    key(step.key) {
                        AgentWorkStepNode(
                            step = step,
                            isLast = index == steps.lastIndex,
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = retainedStreamingStates,
                            highlighted = step.containsMessage(highlightMessageId),
                            onRowBounds = onHitRowBounds,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentWorkHeader(
    messages: List<AgentChatMessageUi>,
    working: Boolean,
    expanded: Boolean,
    searchHitCount: Int,
    searchHitPosition: Int,
    onClick: () -> Unit,
    onOpenRunDetail: ((String) -> Unit)? = null,
) {
    // 消息 id 里带着运行 id（`assistant-<runId>-...` / `<runId>-tool-...`），
    // 直接反解比再传一路参数更省，也不依赖消息模型新增字段。
    val traceRunId = remember(messages) { messages.traceRunId() }
    val runningTool = messages.lastOrNull { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    } as? ToolActivityMessageUi
    val summary = remember(messages) { messages.workSummary() }
    val waitingForControl = LocalGuiControlWaiting.current
    // 有执行中进度时优先显示它：argumentsSummary 是调用前的静态描述，
    // 而用户盯着折叠标题时最想知道的是"它这一秒在做什么"（ACP 委派尤其如此）。
    val runningDetail = runningTool?.progressLine?.takeIf { it.isNotBlank() }
    val baseText = when {
        // 排队等屏幕时，「正在分析任务」是假话 —— 它根本没在分析，是在等。
        waitingForControl -> stringResource(R.string.run_waiting_for_gui)
        runningTool != null -> pluralStringResource(
            R.plurals.work_processing_step,
            summary.toolCount,
            summary.toolCount,
        ) + " · " + (runningDetail
            ?: runningTool.argumentsSummary.ifBlank { toolDisplayName(runningTool.toolName) })
        working -> stringResource(R.string.work_analyzing)
        else -> workSummaryText(summary)
    }
    val text = baseText
    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WorkRowHeight)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(WorkGutterWidth), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    waitingForControl -> Icons.Rounded.Lightbulb
                    runningTool != null -> iconForTool(runningTool.toolName)
                    working -> Icons.Rounded.Lightbulb
                    else -> Icons.Rounded.Checklist
                },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (working) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        ShimmerText(
            text = text,
            active = working || waitingForControl,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (traceRunId != null && onOpenRunDetail != null) {
            Text(
                text = stringResource(R.string.work_process_details),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenRunDetail(traceRunId) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        ExpandChevron(
            expanded = expanded,
            contentDescription = stringResource(
                if (expanded) R.string.work_collapse else R.string.work_expand,
            ),
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
        )
    }
        // 命中提示必须单独一行：摘要本身已经接近整行宽，拼在后面会被省略号吃掉，
        // 而「第几条」正是解决「连点没反应」的关键信息。
        if (searchHitCount > 0) {
            Text(
                text = if (searchHitPosition > 0) {
                    stringResource(
                        R.string.chat_search_block_hits_current,
                        searchHitPosition,
                        searchHitCount,
                    )
                } else {
                    stringResource(R.string.chat_search_block_hits, searchHitCount)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = WorkGutterWidth + WorkGutterGap,
                    end = 8.dp,
                    bottom = 4.dp,
                ),
            )
        }
    }
}

@Composable
private fun workSummaryText(summary: AgentWorkSummary): String {
    val parts = buildList {
        if (summary.thinkingCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_thinking, summary.thinkingCount, summary.thinkingCount))
        }
        if (summary.toolCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_tools, summary.toolCount, summary.toolCount))
        }
        if (summary.failedCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_failed, summary.failedCount, summary.failedCount))
        }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.work_completed) }
}

@Composable
private fun AgentWorkStepNode(
    step: AgentWorkStep,
    isLast: Boolean,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    highlighted: Boolean = false,
    onRowBounds: ((Float, Float) -> Unit)? = null,
) {
    when (step) {
        is AgentWorkStep.ToolGroup -> WorkTimelineNode(
            icon = iconForTool(step.toolName),
            tone = when {
                step.running -> WorkNodeTone.Running
                step.failedCount > 0 -> WorkNodeTone.Failed
                else -> WorkNodeTone.Done
            },
            isLast = isLast,
            highlighted = highlighted,
            onRowBounds = onRowBounds,
        ) {
            ToolGroupRow(group = step, onOpenBrowser = onOpenBrowser)
        }

        is AgentWorkStep.Single -> when (val message = step.message) {
            is ToolActivityMessageUi -> WorkTimelineNode(
                icon = iconForTool(message.toolName),
                tone = message.status.nodeTone(),
                isLast = isLast,
                highlighted = highlighted,
                onRowBounds = onRowBounds,
            ) {
                ToolActivityRow(
                    message = message,
                    onOpenBrowser = onOpenBrowser,
                    showBrowserShortcut = message.id == currentBrowserMessageId,
                )
            }

            is ThinkingMessageUi -> WorkTimelineNode(
                icon = Icons.Rounded.Lightbulb,
                tone = if (message.isStreaming) WorkNodeTone.Running else WorkNodeTone.Done,
                isLast = isLast,
                highlighted = highlighted,
                onRowBounds = onRowBounds,
            ) {
                ThinkingRow(
                    message = message,
                    retainedStreamingState = retainedStreamingStates[message.id],
                    compact = true,
                )
            }

            is ToolSummaryMessageUi -> WorkTimelineNode(
                icon = Icons.Rounded.Build,
                tone = WorkNodeTone.Done,
                isLast = isLast,
                highlighted = highlighted,
                onRowBounds = onRowBounds,
            ) {
                ChatMessageItem(
                    message = message,
                    onSuggestionClick = {},
                    onRunTraceClick = {},
                    onOpenBrowser = onOpenBrowser,
                    showBrowserShortcut = false,
                    compact = true,
                )
            }

            else -> Unit
        }
    }
}

internal enum class WorkNodeTone { Running, Done, Failed }

internal fun ToolActivityStatusUi.nodeTone(): WorkNodeTone = when (this) {
    ToolActivityStatusUi.Running -> WorkNodeTone.Running
    ToolActivityStatusUi.Failed -> WorkNodeTone.Failed
    ToolActivityStatusUi.Success, ToolActivityStatusUi.Unknown -> WorkNodeTone.Done
}

/**
 * 时间线节点：左侧固定宽度的槽位放图标徽章，连线在绘制阶段画出，不参与测量。
 * 节点默认向上接到工作过程标题图标，最后一个节点不向下延伸。
 */
@Composable
internal fun WorkTimelineNode(
    icon: ImageVector,
    tone: WorkNodeTone,
    isLast: Boolean,
    connectTop: Boolean = true,
    highlighted: Boolean = false,
    onRowBounds: ((Float, Float) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = WorkGutterWidth.toPx() / 2f
                val stroke = 1.dp.toPx()
                val gap = 3.dp.toPx()
                val nodeTop = (WorkRowHeight - WorkNodeSize).toPx() / 2f
                val nodeBottom = nodeTop + WorkNodeSize.toPx()
                if (connectTop) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, nodeTop - gap), stroke)
                }
                if (!isLast) {
                    drawLine(lineColor, Offset(x, nodeBottom + gap), Offset(x, size.height), stroke)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .width(WorkGutterWidth)
                .height(WorkRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            WorkNodeBadge(icon = icon, tone = tone)
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        // 命中行单独标底色：整块上色会让「当前是哪一条」重新变得不可知。
        val hitAccent = MiuixTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (highlighted && onRowBounds != null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            val top = coordinates.positionInWindow().y
                            onRowBounds(top, top + coordinates.size.height)
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (highlighted) {
                        Modifier.drawBehind {
                            val corner = CornerRadius(8.dp.toPx())
                            drawRoundRect(
                                color = hitAccent.copy(alpha = 0.16f),
                                cornerRadius = corner,
                            )
                            val barWidth = 3.dp.toPx()
                            drawRoundRect(
                                color = hitAccent.copy(alpha = 0.85f),
                                size = Size(barWidth, size.height),
                                cornerRadius = CornerRadius(barWidth / 2f),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun WorkNodeBadge(icon: ImageVector, tone: WorkNodeTone) {
    val (background, tint) = when (tone) {
        WorkNodeTone.Running -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) to
            MiuixTheme.colorScheme.primary
        WorkNodeTone.Failed -> StatusError.copy(alpha = 0.12f) to StatusError
        WorkNodeTone.Done -> MiuixTheme.colorScheme.surfaceContainer to
            MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val pulse = rememberDrawPhasePulse(active = tone == WorkNodeTone.Running)
    Box(
        modifier = Modifier
            .size(WorkNodeSize)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint,
        )
    }
}

/** 呼吸动画只返回 State，由调用方在 graphicsLayer 中读取，帧间不触发重组。 */
@Composable
internal fun rememberDrawPhasePulse(active: Boolean): State<Float>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "work_node_pulse")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "work_node_pulse_alpha",
    )
}

/**
 * 进行中的标题用一道扫过的高光提示“正在发生”。高光在绘制阶段以 SrcAtop 叠在字形上，
 * 动画只使绘制失效；离屏层限定在这一行文字范围内。
 */
@Composable
internal fun ShimmerText(
    text: String,
    active: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shimmerModifier = if (active) {
        val highlight = MiuixTheme.colorScheme.onSurface
        val transition = rememberInfiniteTransition(label = "work_shimmer")
        val progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_600, easing = LinearEasing),
            ),
            label = "work_shimmer_progress",
        )
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = maxOf(size.width * 0.35f, 72.dp.toPx())
                val center = -band + (size.width + band * 2f) * progress.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        startX = center - band,
                        endX = center + band,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            }
    } else {
        Modifier
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(shimmerModifier),
    )
}

@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "expand_chevron",
    )
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = contentDescription,
        modifier = modifier
            .size(14.dp)
            .graphicsLayer { rotationZ = rotation },
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
    )
}

/** 时间线标题行与节点徽章共用的几何尺寸，保证图标中心与首行文字中线对齐。 */
internal val WorkRowHeight: Dp = 32.dp
internal val WorkGutterWidth: Dp = 22.dp
internal val WorkGutterGap: Dp = 8.dp
private val WorkNodeSize: Dp = 20.dp

/**
 * 从消息 id 反解运行 id。
 *
 * runId 由入口生成，形如 `run-<uuid>`；消息 id 分别是
 * `assistant-<runId>-<round>-<index>`、`<runId>-tool-<round>-<callId>`、
 * `<runId>-thinking-...`。旧版本或第三方入口的消息可能不含 runId，此时返回 null。
 */
private val TRACE_RUN_ID_PATTERN = Regex(
    """run-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""",
)

internal fun List<AgentChatMessageUi>.traceRunId(): String? {
    for (index in indices.reversed()) {
        TRACE_RUN_ID_PATTERN.find(this[index].id)?.let { return it.value }
    }
    return null
}
