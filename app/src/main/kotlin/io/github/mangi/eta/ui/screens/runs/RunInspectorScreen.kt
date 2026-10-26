package io.github.mangi.eta.ui.screens.runs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunExport
import io.github.mangi.eta.agent.runtime.AgentRunFileChanges
import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.agent.trace.AgentRunTrajectory
import androidx.core.content.FileProvider
import io.github.mangi.eta.ui.components.EtaCard
import java.io.File
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行详情：回答「Agent 到底做了什么、慢在哪一步、为什么重试」。
 *
 * [runId] 为空时列出最近的运行；否则展示单次运行的时间线。
 * 数据来自 [AgentRunTraceStore]（每次运行一个 JSONL），与消息流解耦：
 * 消息只保留给人看的摘要，这里保留工具参数 / 结果原文（有上限）与每步耗时。
 */
@Composable
internal fun RunInspectorScreen(
    runId: String?,
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    if (runId == null) {
        RunListScreen(onBack = onBack, onOpenRun = onOpenRun)
    } else {
        RunDetailScreen(runId = runId, onBack = onBack)
    }
}

@Composable
private fun RunListScreen(onBack: () -> Unit, onOpenRun: (String) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val runs by produceState<List<AgentRunTraceStore.Summary>?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { AgentRunTraceStore.list(60) }
    }
    MiuixScaffoldPage(title = stringResource(R.string.route_runs), onBack = onBack) {
        item("runs_intro") {
            EtaCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(stringResource(R.string.runs_intro_body), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.runs_intro_retention), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EtaTextButton(
                        text = stringResource(R.string.runs_refresh),
                        onClick = { refresh += 1 },
                    )
                    EtaTextButton(
                        text = stringResource(R.string.runs_clear),
                        onClick = { showClearDialog = true },
                        enabled = !runs.isNullOrEmpty(),
                    )
                }
            }
        }
        val list = runs
        if (list == null) {
            item("runs_loading") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) { Text(stringResource(R.string.runs_loading)) }
            }
            return@MiuixScaffoldPage
        }
        if (list.isEmpty()) {
            item("runs_empty") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.runs_empty))
                }
            }
            return@MiuixScaffoldPage
        }
        list.forEach { run ->
            item("run_${run.runId}") {
                RunSummaryCard(
                    run = run,
                    onClick = { onOpenRun(run.runId) },
                    onDelete = { pendingDelete = run.runId },
                )
            }
        }
        item("runs_footer") { Spacer(Modifier.height(12.dp)) }
    }

    pendingDelete?.let { id ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.runs_delete_title),
            summary = stringResource(R.string.runs_delete_summary),
            onDismissRequest = { pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    AgentRunTraceStore.delete(id)
                    refresh += 1
                },
            )
        }
    }
    if (showClearDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.runs_clear_title),
            summary = stringResource(R.string.runs_clear_summary),
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.runs_clear),
                destructive = true,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    AgentRunTraceStore.clear()
                    refresh += 1
                },
            )
        }
    }
}

@Composable
private fun RunSummaryCard(
    run: AgentRunTraceStore.Summary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val resources = LocalResources.current
    EtaCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(statusColor(run.ok)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = run.model.ifBlank { stringResource(R.string.runs_unknown_model) },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatTime(run.startedAt))
                        if (run.durationMs > 0) {
                            append(" · ")
                            append(resources.getString(R.string.runs_summary_took, formatDuration(run.durationMs)))
                        }
                        append(" · ")
                        append(
                            when (run.ok) {
                                null -> resources.getString(R.string.runs_status_running)
                                true -> resources.getString(R.string.runs_status_done)
                                false -> resources.getString(R.string.runs_status_failed)
                            },
                        )
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = stringResource(R.string.runs_view),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallMetric(stringResource(R.string.runs_metric_rounds), run.rounds.toString())
            SmallMetric(
                stringResource(R.string.runs_metric_tools),
                if (run.toolFailures > 0) {
                    pluralStringResource(
                        R.plurals.runs_metric_tools_with_failures,
                        run.toolFailures,
                        run.toolCalls,
                        run.toolFailures,
                    )
                } else {
                    run.toolCalls.toString()
                },
            )
            SmallMetric(stringResource(R.string.runs_metric_retries), run.retries.toString())
            SmallMetric("token", formatCount(run.totalTokens))
        }
        if (!run.error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = run.error,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EtaTextButton(text = stringResource(R.string.action_delete), onClick = onDelete)
        }
    }
}

@Composable
private fun RunDetailScreen(runId: String, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val trace by produceState<AgentRunTraceStore.Trace?>(initialValue = null, runId, refresh) {
        value = withContext(Dispatchers.IO) { AgentRunTraceStore.read(runId) }
    }
    // 轨迹投影只依赖这次读取的记录：切换 runId / 刷新时重建，不做额外 IO。
    val trajectory = remember(trace) { trace?.let { AgentRunTrajectory.build(it) } }
    var selectedTurn by rememberSaveable(runId) { mutableStateOf(-1) }
    val selected = selectedTurn.takeIf { it >= 0 }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var exporting by remember(runId) { mutableStateOf(false) }
    var exportError by remember(runId) { mutableStateOf<String?>(null) }
    MiuixScaffoldPage(title = stringResource(R.string.run_detail_title), onBack = onBack) {
        val current = trace
        val built = trajectory
        if (current == null || built == null) {
            item("trace_loading") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    insideMargin = PaddingValues(14.dp),
                ) { Text(stringResource(R.string.run_detail_loading)) }
            }
            return@MiuixScaffoldPage
        }
        item("trace_summary") {
            EtaCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor(current.ok)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = current.model.ifBlank { stringResource(R.string.runs_unknown_model) },
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current.provider.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "· ${current.provider}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 指标用 FlowRow：8 项挤在两行里，窄屏或长数值（"12m34s"）会互相顶出去。
                // 让它按可用宽度自然换行，比按固定四列硬塞更稳。
                MetricFlow {
                    SmallMetric(
                        stringResource(R.string.runs_metric_duration),
                        formatDuration(current.durationMs),
                        Modifier.weight(1f),
                    )
                    SmallMetric(stringResource(R.string.runs_metric_rounds), current.rounds.toString(), Modifier.weight(1f))
                    SmallMetric(
                        stringResource(R.string.runs_metric_tools),
                        if (current.toolFailures > 0) {
                            pluralStringResource(
                                R.plurals.runs_metric_tools_with_failures,
                                current.toolFailures,
                                current.toolCalls,
                                current.toolFailures,
                            )
                        } else {
                            current.toolCalls.toString()
                        },
                        Modifier.weight(1f),
                    )
                    SmallMetric(stringResource(R.string.runs_metric_retries), current.retries.toString(), Modifier.weight(1f))
                    SmallMetric("token", formatCount(current.totalTokens), Modifier.weight(1f))
                    SmallMetric(stringResource(R.string.runs_metric_output), formatCount(current.outputTokens), Modifier.weight(1f))
                    SmallMetric(
                        stringResource(R.string.runs_metric_speed),
                        "${formatSpeed(current.outputTokensPerSecond)} tok/s",
                        Modifier.weight(1f),
                    )
                    SmallMetric(stringResource(R.string.runs_metric_started), formatTime(current.startedAt), Modifier.weight(1f))
                }
                if (current.outputTokensPerSecond == null && current.outputTokens > 0) {
                    // 有 token 但没有耗时/用量记录时说明白，别让「— tok/s」看起来像 bug。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.run_detail_speed_note),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (current.truncated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.run_detail_truncated),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                if (!current.error.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = current.error,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaTextButton(text = stringResource(R.string.runs_refresh), onClick = { refresh += 1 })
                    EtaTextButton(
                        text = if (exporting) stringResource(R.string.runs_exporting) else stringResource(R.string.action_export),
                        onClick = {
                            if (exporting) return@EtaTextButton
                            exporting = true
                            exportError = null
                            scope.launch {
                                val exported = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val changes = AgentRunFileChanges.forApp(context).load(runId)
                                        val markdown = AgentRunExport.markdown(current, changes)
                                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val file = File(dir, AgentRunExport.fileName(runId))
                                        file.writeText(markdown, Charsets.UTF_8)
                                        file
                                    }
                                }
                                val failure = exported.exceptionOrNull()
                                if (failure != null) {
                                    exportError = resources.getString(
                                        R.string.runs_export_failed,
                                        failure.message ?: failure::class.java.simpleName,
                                    )
                                } else {
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.files",
                                            exported.getOrThrow(),
                                        )
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.runs_export_subject))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(send, resources.getString(R.string.runs_export_chooser))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.onFailure {
                                        exportError = resources.getString(
                                            R.string.runs_share_failed,
                                            it.message ?: it::class.java.simpleName,
                                        )
                                    }
                                }
                                exporting = false
                            }
                        },
                    )
                }
                exportError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        item("file_changes") {
            RunFileChangesCard(
                runId = runId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item("trajectory_overview") {
            TrajectoryOverviewCard(
                trajectory = built,
                selectedTurn = selected,
                onSelectTurn = { turn -> selectedTurn = turn ?: -1 },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val visibleTurns = built.turns.filter { selected == null || it.index == selected }
        visibleTurns.forEach { turn ->
            item("turn_${turn.index}") { TrajectoryTurnHeader(turn) }
            turn.entries.forEach { ref ->
                // 轮开始记录由上面的轮标题承担，避免同一条信息出现两次。
                if (ref.entry.kind == AgentRunTraceStore.KIND_ROUND) return@forEach
                item("entry_${ref.index}") {
                    TraceEntryCard(
                        entry = ref.entry,
                        next = current.entries.getOrNull(ref.index + 1),
                        offsetMs = ref.offsetMs,
                    )
                }
            }
        }
        item("trace_footer") { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * 轮标题：轨迹视图的分组边界。原来只有「第 N 轮」一张卡片，
 * 这里补上这一轮的总用时、工具数与 token，先看总账再看明细。
 */
@Composable
private fun TrajectoryTurnHeader(turn: AgentRunTrajectory.Turn) {
    val resources = LocalResources.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.runs_turn_title, turn.index + 1),
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = buildString {
                append(formatDuration(turn.durationMs))
                if (turn.toolCalls > 0) {
                    append(" · ")
                    append(
                        if (turn.toolFailures > 0) {
                            resources.getQuantityString(
                                R.plurals.runs_turn_tools_failed,
                                turn.toolCalls,
                                turn.toolCalls,
                                turn.toolFailures,
                            )
                        } else {
                            resources.getQuantityString(R.plurals.runs_turn_tools, turn.toolCalls, turn.toolCalls)
                        },
                    )
                }
                if (turn.tokens > 0) append(" · ").append(formatCount(turn.tokens)).append(" token")
                // 单轮速度：只看模型生成的那段时间，工具耗时不算进去。
                turn.tokensPerSecond?.let { append(" · ").append(formatSpeed(it)).append(" tok/s") }
                if (turn.retries > 0) {
                    append(" · ")
                    append(resources.getQuantityString(R.plurals.runs_turn_retries, turn.retries, turn.retries))
                }
            },
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun TraceEntryCard(
    entry: AgentRunTraceStore.Entry,
    next: AgentRunTraceStore.Entry?,
    offsetMs: Long,
) {
    // 工具参数/结果默认收起：一次运行可能有几十个工具调用，全展开会把时间线淹没。
    var expanded by rememberSaveable(entry.at, entry.kind, entry.round) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val resources = LocalResources.current
    val gapMs = next?.let { (it.at - entry.at).coerceAtLeast(0) }
    EtaCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (entry.kind) {
            AgentRunTraceStore.KIND_ROUND -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.runs_turn_title, entry.round ?: 0),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append("+").append(formatDuration(offsetMs))
                        entry.messageCount?.let {
                            append(" · ")
                            append(resources.getQuantityString(R.plurals.runs_context_messages, it, it))
                        }
                    },                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            AgentRunTraceStore.KIND_REQUEST -> DetailLine(
                title = stringResource(R.string.runs_entry_request),
                detail = stringResource(R.string.runs_entry_request_detail, formatDuration(gapMs ?: 0)),
            )
            AgentRunTraceStore.KIND_RESPONSE -> DetailLine(
                title = stringResource(R.string.runs_entry_response),
                detail = stringResource(
                    R.string.runs_entry_response_detail,
                    entry.httpCode ?: 0,
                    formatDuration(gapMs ?: 0),
                ),
            )
            AgentRunTraceStore.KIND_THINKING -> DetailLine(
                title = stringResource(R.string.runs_entry_thinking),
                detail = stringResource(
                    R.string.runs_entry_thinking_detail,
                    formatCount(entry.chars ?: 0),
                    formatDuration(gapMs ?: 0),
                ),
            )
            AgentRunTraceStore.KIND_TEXT -> DetailLine(
                title = stringResource(R.string.runs_entry_text),
                detail = stringResource(R.string.runs_entry_text_detail, formatCount(entry.chars ?: 0)),
            )
            AgentRunTraceStore.KIND_USAGE -> DetailLine(
                title = stringResource(R.string.runs_entry_usage),
                detail = stringResource(
                    R.string.runs_entry_usage_detail,
                    formatCount(entry.contextTokens ?: 0),
                    formatCount(entry.inputTokens ?: 0),
                    formatCount(entry.outputTokens ?: 0),
                    formatCount(entry.cachedTokens ?: 0),
                ),
            )
            AgentRunTraceStore.KIND_RETRY -> DetailLine(
                title = stringResource(R.string.runs_entry_retry, entry.attempt ?: 0, entry.maxAttempts ?: 0),
                detail = stringResource(
                    R.string.runs_entry_retry_detail,
                    formatDuration(entry.delayMs ?: 0),
                    entry.code.orEmpty(),
                ),
                tone = TraceTone.Warning,
            )
            AgentRunTraceStore.KIND_COMPACT -> DetailLine(
                title = stringResource(
                    if (entry.phase == "completed") R.string.runs_entry_compact_done else R.string.runs_entry_compact_running,
                ),
                detail = buildString {
                    append(
                        if (entry.charsBefore != null || entry.charsAfter != null) {
                            resources.getString(
                                R.string.runs_entry_compact_chars_detail,
                                formatCount(entry.charsBefore ?: 0),
                                formatCount(entry.charsAfter ?: 0),
                            )
                        } else {
                            // 旧追踪没有字符数，只能回退到 token 口径。
                            resources.getString(
                                R.string.runs_entry_compact_detail,
                                formatCount(entry.before ?: 0),
                                formatCount(entry.after ?: 0),
                            )
                        },
                    )
                    // 耗时只在真正测到时显示：旧追踪没有 ms，不补 0（伪造的 0 比不显示更误导）。
                    entry.elapsedMs?.takeIf { it > 0 }?.let { elapsed ->
                        append(" · ").append(
                            resources.getString(R.string.runs_entry_compact_elapsed, formatDuration(elapsed)),
                        )
                    }
                },
            )
            AgentRunTraceStore.KIND_TOOL -> ToolEntry(entry = entry, expanded = expanded, onToggle = { expanded = !expanded }, onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
            })
            AgentRunTraceStore.KIND_DONE -> DetailLine(
                title = stringResource(if (entry.ok == true) R.string.runs_entry_done else R.string.runs_entry_ended),
                detail = buildString {
                    append(resources.getString(R.string.runs_entry_done_detail, formatDuration(offsetMs)))
                    entry.error?.let { append(" · ").append(it) }
                },
                tone = if (entry.ok == true) TraceTone.Normal else TraceTone.Warning,
            )
            else -> Unit
        }
    }
}

@Composable
private fun DetailLine(
    title: String,
    detail: String,
    tone: TraceTone = TraceTone.Normal,
) {
    val detailColor = when (tone) {
        TraceTone.Normal -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        TraceTone.Warning -> MiuixTheme.colorScheme.error
    }
    // 值太长时改成上下两行。
    //
    // 原来一律是「标题 —— Spacer(weight) —— 值」的三段式：值没有宽度约束，
    // 一旦长到要换行（token 用量那行有四个数字），换行后的文字会直接压在标题上。
    // 这不是"稍微挤一点"，是真读不了，所以按长度分两种布局。
    if (detail.length > DETAIL_INLINE_MAX_CHARS) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(2.dp))
            Text(text = detail, style = MiuixTheme.textStyles.footnote1, color = detailColor)
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.weight(1f))
            Text(
                text = detail,
                modifier = Modifier.weight(1f, fill = false),
                style = MiuixTheme.textStyles.footnote1,
                color = detailColor,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun ToolEntry(
    entry: AgentRunTraceStore.Entry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val resources = LocalResources.current
    val clipboardText = buildString {
        append(entry.name.orEmpty())
        append("\n\n")
        append(resources.getString(R.string.runs_clipboard_arguments))
        append("\n")
        append(entry.arguments.orEmpty())
        append("\n\n")
        append(resources.getString(R.string.runs_clipboard_result))
        append("\n")
        append(entry.result.orEmpty())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.ok == false) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.primary,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.name.orEmpty(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (entry.elapsedMs != null) append(formatDuration(entry.elapsedMs))
                    if (entry.elapsedMs != null) append(" · ")
                    append(
                        if (entry.ok == false) {
                            resources.getString(R.string.runs_tool_failed)
                        } else {
                            resources.getString(R.string.runs_tool_success)
                        },
                    )
                    entry.resultChars?.let {
                        if (it > 0) append(" · ").append(resources.getString(R.string.runs_tool_chars, formatCount(it)))
                    }
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (!expanded) {
            entry.arguments?.takeIf(String::isNotBlank)?.let { args ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = args.lineSequence().firstOrNull().orEmpty().take(160),
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        CodeBlock(title = stringResource(R.string.runs_tool_arguments), text = entry.arguments.orEmpty())
        Spacer(Modifier.height(6.dp))
        CodeBlock(title = stringResource(R.string.runs_tool_result), text = entry.result.orEmpty())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EtaTextButton(text = stringResource(R.string.runs_tool_copy), onClick = {
                onCopy(clipboardText)
            })
        }
    }
}

@Composable
private fun CodeBlock(title: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text.ifBlank { stringResource(R.string.runs_empty_value) },
            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 指标容器：按可用宽度自动换行。
 *
 * 原来固定两行 Row，每行最多 4 项；数值一长（"12m34s"、"1.2M token"）就会把
 * 最后一项顶出屏幕，而且每项宽度不统一，看起来是歪的。FlowRow 让每项保持自然宽度。
 */
@Composable
private fun MetricFlow(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        // 固定三列：每项等宽，标签与数值才会各自对齐。
        // 自然宽度会让 "用时/3m1s" 和 "开始/10-21 21:25:16" 宽窄不一，看起来是歪的。
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** 超过这个长度就换行显示，避免长值在横排里压到标题。 */
private const val DETAIL_INLINE_MAX_CHARS = 24

/** 速度显示：数据不足时给「—」，不要用 0 冒充。 */
private fun formatSpeed(value: Double?): String = when {
    value == null -> "—"
    value >= 100 -> String.format(Locale.US, "%.0f", value)
    else -> String.format(Locale.US, "%.1f", value)
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(text = value, style = MiuixTheme.textStyles.footnote1)
    }
}

private enum class TraceTone { Normal, Warning }

@Composable
private fun statusColor(ok: Boolean?) = when (ok) {
    true -> MiuixTheme.colorScheme.primary
    false -> MiuixTheme.colorScheme.error
    null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val totalSeconds = ms / 1000
        "${totalSeconds / 60}m${totalSeconds % 60}s"
    }
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1000.0)
    else -> value.toString()
}

@Suppress("unused")
private fun formatBytes(size: Long): String = when {
    size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
    size >= 1024 -> String.format(Locale.US, "%.0f KB", size / 1024.0)
    else -> "$size B"
}
