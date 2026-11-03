package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作过程之外单独出现的工具条目（例如旧会话或语音浮层），沿用时间线节点外观。
 */
@Composable
internal fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (compact) {
        ToolActivityRow(message, onOpenBrowser, showBrowserShortcut, modifier)
        return
    }
    Box(modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        WorkTimelineNode(
            icon = iconForTool(message.toolName),
            tone = message.status.nodeTone(),
            isLast = true,
            connectTop = false,
        ) {
            ToolActivityRow(message, onOpenBrowser, showBrowserShortcut)
        }
    }
}

/**
 * 工具的折叠形态是两行：做了什么，以及一行结论。命令、完整结果和浏览器预览只在展开后
 * 组合，长会话中成百上千个工具行保持轻量。
 */
@Composable
internal fun ToolActivityRow(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val running = message.status == ToolActivityStatusUi.Running
    val elapsedSeconds = rememberRunningSeconds(message.id, running, message.startedAtMs)
    if (message.toolName == AgentSubagentToolCatalog.TOOL_NAME) {
        val snapshot = remember(message.command, message.argumentsSummary, message.resultSummary, message.status) {
            SubagentActivitySnapshot.decode(message.command) ?: SubagentActivitySnapshot(
                subagentType = message.argumentsSummary.substringBefore(" · ").ifBlank { "subagent" },
                description = message.argumentsSummary.substringAfter(" · ", message.argumentsSummary),
                status = when (message.status) {
                    ToolActivityStatusUi.Running -> SubagentActivitySnapshot.STATUS_RUNNING
                    ToolActivityStatusUi.Failed -> SubagentActivitySnapshot.STATUS_FAILED
                    else -> SubagentActivitySnapshot.STATUS_COMPLETED
                },
                output = message.resultSummary.orEmpty(),
            )
        }
        SubagentActivityRow(
            message = message,
            snapshot = snapshot,
            elapsedSeconds = elapsedSeconds,
            modifier = modifier,
        )
        return
    }
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 只有「当前浏览器」条目订阅实时会话快照，避免每个工具行都跟随快照重组。
    val browser = LocalAgentBrowserSession.current
    val browserSnapshot = if (showBrowserShortcut && browser != null) {
        browser.snapshots.collectAsState().value
    } else {
        null
    }
    val failed = message.status == ToolActivityStatusUi.Failed
    val title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) }
    val outcome = remember(message.status, message.resultSummary) { message.outcomeLine() }
    val browserLine = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading -> stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() -> "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // 执行中或中断后优先显示最后一条进展：ACP 这类长时工具的中间过程必须看得见，
    // 否则卡片只有一行不变的标题，用户无法判断它是否卡住。
    // 成功时让位给结论（结果已完整），失败时让位给失败原因。
    val progressLine = remember(message.progressLine, message.status) {
        message.progressLine?.takeIf { line ->
            line.isNotBlank() && (
                message.status == ToolActivityStatusUi.Running ||
                    message.status == ToolActivityStatusUi.Unknown
                )
        }
    }
    val subtitle = progressLine ?: browserLine ?: outcome
    // 结果只有一行且已作为结论显示时，展开不会带来新信息，不提供展开入口。
    val resultDetail = message.resultSummary?.trim()?.takeIf { it.isNotEmpty() && it != outcome }
    val hasDetail = !message.command.isNullOrBlank() ||
        resultDetail != null ||
        !message.resultDetail.isNullOrBlank() ||
        message.elapsedMs != null ||
        showBrowserShortcut

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(10.dp))
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (showTitle) {
                    ShimmerText(
                        text = title,
                        active = running,
                        style = MiuixTheme.textStyles.body2,
                        color = if (running) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = if (message.isTerminalTool() && browserLine == null) {
                            MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MiuixTheme.textStyles.footnote2
                        },
                        color = if (failed) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                        maxLines = if (progressLine != null) PROGRESS_SUBTITLE_LINES else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (showTitle) 1.dp else 0.dp),
                    )
                }
            }
            if (elapsedSeconds != null && elapsedSeconds >= RUNNING_TIMER_THRESHOLD_SECONDS) {
                Text(
                    text = stringResource(R.string.tool_elapsed_seconds, elapsedSeconds),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (hasDetail) {
                ExpandChevron(
                    expanded = expanded,
                    contentDescription = stringResource(
                        if (expanded) R.string.tool_details_collapse else R.string.tool_details_expand,
                    ),
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            ToolActivityDetail(
                message = message,
                browserSnapshot = browserSnapshot,
                showBrowserShortcut = showBrowserShortcut,
                onOpenBrowser = onOpenBrowser,
            )
        }
    }
}

/**
 * 连续调用同一工具的分组：标题汇总次数，展开后每次调用只占一行，失败的调用仍可单独展开。
 */
@Composable
internal fun ToolGroupRow(
    group: AgentWorkStep.ToolGroup,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val running = group.running
    val latest = group.calls.last()
    val title = toolDisplayName(group.toolName) + " · " +
        pluralStringResource(R.plurals.tool_group_calls, group.calls.size, group.calls.size)
    val subtitle = when {
        running -> latest.argumentsSummary.takeIf(String::isNotBlank)
        group.failedCount > 0 -> pluralStringResource(
            R.plurals.work_summary_failed,
            group.failedCount,
            group.failedCount,
        )
        else -> null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ShimmerText(
                    text = title,
                    active = running,
                    style = MiuixTheme.textStyles.body2,
                    color = if (running) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (!running && group.failedCount > 0) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.tool_details_collapse else R.string.tool_details_expand,
                ),
                modifier = Modifier.padding(start = 6.dp, end = 8.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                group.calls.forEach { call ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 13.dp, end = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(call.status.dotColor()),
                        )
                        ToolActivityRow(
                            message = call,
                            onOpenBrowser = onOpenBrowser,
                            showBrowserShortcut = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolActivityDetail(
    message: ToolActivityMessageUi,
    browserSnapshot: BrowserSessionSnapshot?,
    showBrowserShortcut: Boolean,
    onOpenBrowser: () -> Unit,
) {
    var previewPath by remember(message.id) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!message.command.isNullOrBlank()) {
            ToolCommandBlock(command = message.command, context = message.argumentsSummary)
        }
        message.elapsedMs?.let { ms ->
            Text(
                text = stringResource(R.string.tool_activity_elapsed, formatElapsed(ms)),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // 编辑类工具的可展开 diff：行号 + 增删行，等宽显示；不是所有工具都有。
        message.resultDetail?.takeIf(String::isNotBlank)?.let { diff ->
            SelectionContainer {
                Text(
                    text = diff,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            cornerRadius = 10.dp,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        val result = message.resultSummary?.takeIf(String::isNotBlank)
        if (result != null) {
            SelectionContainer {
                Text(
                    text = result,
                    style = if (message.isTerminalTool()) {
                        MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MiuixTheme.textStyles.footnote2
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = MAX_RESULT_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            cornerRadius = 10.dp,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        if (showBrowserShortcut) {
            browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                BrowserPagePreview(snapshot = snapshot)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    text = stringResource(R.string.ui_open_current_browser_58358e),
                    onClick = onOpenBrowser,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                    textStyle = MiuixTheme.textStyles.body2,
                )
            }
        }
        message.filePath?.takeIf { it.isNotBlank() }?.let { path ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    text = stringResource(R.string.tool_activity_view_file),
                    onClick = { previewPath = path },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                    textStyle = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
    previewPath?.let { path ->
        FilePreviewDialog(path = path, onDismiss = { previewPath = null })
    }
}

private fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/**
 * 运行计时只在工具执行中组合，每秒仅使这一小段文字重组。
 *
 * 起点优先用 [startedAtMs]（随消息落盘的墙上时间）：切换会话、进程重建后仍能接着算。
 * 旧数据没有这个值时才退回界面侧近似起点（[rememberSaveable]），
 * 那种情况下重建会从 0 开始 —— 这是可接受的降级，但**不能再当成默认可行为**：
 * 用户看到的「秒数重新计数」就是它。
 */
@Composable
internal fun rememberRunningSeconds(id: String, running: Boolean, startedAtMs: Long?): Int? {
    if (!running) return null
    val fallbackStart = rememberSaveable(id) { System.currentTimeMillis() }
    val start = startedAtMs?.takeIf { it > 0L } ?: fallbackStart
    fun currentSeconds(): Int =
        ((System.currentTimeMillis() - start) / 1_000).toInt().coerceAtLeast(0)
    var seconds by remember(id, start) { mutableIntStateOf(currentSeconds()) }
    LaunchedEffect(id, start) {
        while (true) {
            seconds = currentSeconds()
            delay(1_000)
        }
    }
    return seconds
}

private fun ToolActivityMessageUi.isTerminalTool(): Boolean =
    toolName in setOf("terminal", "terminal_job", "terminal_daemon", "run_command")

@Composable
private fun ToolActivityStatusUi.dotColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    ToolActivityStatusUi.Failed -> StatusError
    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
}

private const val RUNNING_TIMER_THRESHOLD_SECONDS = 3

/**
 * 运行中进度最多显示几行。
 *
 * 一行不够用：ACP 委派期间整个外部代理回合都包在一次工具调用里，
 * 只给一行时用户看到的就是「卡着不动的标题」，看不到代理在说什么。
 */
private const val PROGRESS_SUBTITLE_LINES = 3
private const val MAX_RESULT_LINES = 12

/**
 * 浏览器工具的实时页面预览：迷你地址条 + 当前视口截图。
 *
 * 页面加载中按 1.2 秒刷新；加载完成后再抓一帧稳定画面就停止，
 * 不为了一个已经静止的预览每 4 秒唤醒截图/解码（这是实打实的 CPU 与电量）。
 * 组合销毁即停止，不做后台轮询；截图不可用时退化为图标占位。
 */
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
) {
    val browser = LocalAgentBrowserSession.current ?: return
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(browser, snapshot.url, snapshot.isLoading) {
        do {
            val image = withContext(Dispatchers.IO) {
                browser.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
            }
            if (image != null) preview = image
            if (snapshot.isLoading) delay(1_200L)
        } while (snapshot.isLoading)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isLoading) StatusRunning else StatusSuccess),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.outline,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 32.dp,
                minHeight = 32.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(14.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        SelectionContainer {
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
