package io.github.mangi.eta.ui.components

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import io.github.mangi.eta.agent.terminal.AutoResumeMaxPerChain
import io.github.mangi.eta.agent.terminal.BackgroundResumeMemory
import io.github.mangi.eta.agent.terminal.BackgroundTaskLine
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.backgroundTaskLine
import io.github.mangi.eta.agent.terminal.backgroundResumePrompt
import io.github.mangi.eta.agent.terminal.canOfferManualResume
import io.github.mangi.eta.agent.terminal.summarizeBackgroundTasks
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 读取本进程的后台任务记录（`terminal_job` / `terminal_daemon`）。
 *
 * 与 [rememberTaskLedger] 同一机制：依赖 [DetachedTaskSupervisor.changes] 而不是消息条数 ——
 * 任务结束时只改磁盘上的记录，会话消息不变，按消息刷新永远看不到「跑完了」。
 * 读取用只读快照 `readRecords`：不探活、不认领、不清理，界面不该有副作用。
 */
@Composable
private fun rememberDetachedTasks(): List<DetachedTask>? {
    val context = LocalContext.current
    val changes by DetachedTaskSupervisor.changes.collectAsState()
    var tasks by remember { mutableStateOf<List<DetachedTask>?>(null) }
    LaunchedEffect(context, changes) {
        tasks = withContext(Dispatchers.IO) {
            DetachedTaskSupervisor.readRecords(DetachedTaskSupervisor.defaultRecordsFile(context))
        }
    }
    return tasks
}

/**
 * 底部常驻的后台任务卡：收起一行汇总，点开看谁在跑、最近谁结束了。
 *
 * 为什么要有它：第五、六批把「还有谁在跑」「哪些结束了」写进了系统提示，模型知道了，
 * 但用户仍要去终端页翻。这个卡片是同一份记录的只读视图。
 *
 * 只提供只读日志预览；停止任务仍要走 `terminal_job` / `terminal_daemon`，
 * 界面直接触发会让「谁在调度」变成两套真相（与台账卡同一条理由）。
 *
 * 「继续」是这个卡片唯一会发消息的入口：要用户点一下。自动续跑由进程级 watcher 负责，
 * 且默认关、有链长上限（判定在 `planAutomationTick`）。
 *
 * 自动续跑**完全不在这个组件里**：任务结束由进程级 `BackgroundAutoResumeWatcher`
 * 触发（用户切到别的会话 / 桌面也能续）。watcher 用同一份进程级状态去重与预算；卡片只读它，
 * 让用户知道「为什么不再自动续了」。
 */
@Composable
internal fun BackgroundTasksCard(
    canSend: Boolean,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Compose 约定 Modifier 必须是第一个可选参数（lint `ModifierParameter`），所以作用域键排在它后面。
    scopeId: String? = null,
) {
    val records = rememberDetachedTasks() ?: return
    val view = remember(records) { summarizeBackgroundTasks(records) }
    if (view.isEmpty) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var inspectedTask by remember { mutableStateOf<DetachedTask?>(null) }
    // 时间不能只在记录变化时算一次：运行中的任务显示的是「已运行多久」，
    // 静止的 00:05 挂十分钟就是界面在说假话（与 `rememberRunningSeconds` 同一条教训）。
    // 因此展开期间按秒重算；只剩已结束的行时改成 30 秒（那里是「多久以前」，一秒一跳纯属白重组）。
    var now by remember(records) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(records, expanded, view.runningTotal) {
        if (!expanded) return@LaunchedEffect
        val step = if (view.runningTotal > 0) 1_000L else 30_000L
        while (true) {
            now = System.currentTimeMillis()
            delay(step)
        }
    }

    val context = LocalContext.current
    // 两个开关在设置页里改（写的是**本地** Agent prefs）；这里按组合时读一次，回到本页会重新读。
    // 注意不能用 `Prefs.isEnabled`：它只对 `LOCAL_AGENT_KEYS` 里的键读本地，其余键一律读 remote，
    // 而这两个键属于界面行为、不在那份名单里 —— 用错就会永远读成默认值（开关看起来没反应）。
    val resumeButtonEnabled = remember(context) {
        localSwitch(context, Prefs.Keys.AGENT_BACKGROUND_RESUME_BUTTON)
    }
    val autoResumeEnabled = remember(context) {
        localSwitch(context, Prefs.Keys.AGENT_BACKGROUND_RESUME_AUTO)
    }
    /** 这个会话作用域里已经续过哪些任务：进程级 + 落盘（手动点「继续」也算）。
     *  不能用 `remember`：真机实测一轮跑完后它会丢，按钮又出现 —— 两个防线同时失效。 */
    val resumeScope = remember(context, scopeId) {
        BackgroundResumeMemory.scopeFor(
            store = if (scopeId.isNullOrBlank()) null else BackgroundResumeStore.forApp(context),
            scopeId = scopeId.orEmpty(),
        )
    }
    // 先把磁盘记忆读进来再判定：`isLoaded` 之前的集合是空的，拿它判「没续过」会重复续。
    LaunchedEffect(resumeScope) { resumeScope.loadFromStore() }
    val resumed = resumeScope.resumed
    val promptTemplate = stringResource(R.string.background_tasks_resume_prompt)
    val title = stringResource(R.string.background_run_channel)
    val runningLabel = stringResource(R.string.tool_status_running)
    val finishedLabel = stringResource(R.string.background_tasks_finished)
    val primary = MiuixTheme.colorScheme.primary
    // 计数全拼进同一个 Text：Row 里并排几个 Text 时，后面的会被挤成零宽（台账卡踩过）。
    val header = buildAnnotatedString {
        append(title)
        if (view.runningTotal > 0) {
            withStyle(SpanStyle(color = primary)) { append(" · $runningLabel ${view.runningTotal}") }
        }
        if (view.finished > 0) append(" · $finishedLabel ${view.finished}")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = header,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.todo_plan_collapse else R.string.todo_plan_expand,
                ),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                view.running.forEach { task ->
                    BackgroundTaskRow(task, now, onResume = null, onOpen = { inspectedTask = task })
                }
                val rest = view.runningTotal - view.running.size
                if (rest > 0) {
                    Text(
                        text = "+$rest",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 14.dp, top = 3.dp),
                    )
                }
                val resumeLabel = stringResource(R.string.background_tasks_resume)
                view.recentFinished.forEach { task ->
                    // 归属规则与自动续跑同一个函数：两处分别写就会只修一处（真机上就是这样漏的）。
                    val canResumeThis = canOfferManualResume(
                        task = task,
                        scopeId = scopeId.orEmpty(),
                        buttonEnabled = resumeButtonEnabled,
                        canSend = canSend,
                        handled = resumed,
                    )
                    BackgroundTaskRow(
                        task = task,
                        now = now,
                        resumeLabel = resumeLabel,
                        onResume = if (canResumeThis) {
                            {
                                // 记账同步、落盘进程级（第十八批）：不用 `rememberCoroutineScope()`，
                                // 用户点完立刻离开会话也不会把这次点击取消掉。
                                resumeScope.resumeManually(task.id)
                                onResume(backgroundResumePrompt(promptTemplate, task))
                            }
                        } else {
                            null
                        },
                        onOpen = { inspectedTask = task },
                    )
                }
                if (autoResumeEnabled) {
                    // 链长与水位线都落盘在 watcher 那边；这里只读同一份进程级状态，不自己算。
                    val chain = resumeScope.autoResumed
                    val autoText = when {
                        chain <= 0 -> null
                        chain >= AutoResumeMaxPerChain ->
                            stringResource(R.string.background_tasks_auto_resume_paused)
                        else -> stringResource(
                            R.string.background_tasks_auto_resume_used,
                            chain,
                            AutoResumeMaxPerChain,
                        )
                    }
                    if (autoText != null) {
                        Text(
                            text = autoText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp, start = 14.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.background_tasks_card_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp, start = 14.dp),
                )
            }
        }
    }

    inspectedTask?.let { selected ->
        // records 会在任务结束时刷新；用最新记录覆盖打开时的那份快照，否则日志轮询永远不会停。
        val current = records.firstOrNull { it.id == selected.id } ?: selected
        BackgroundTaskLogSheet(task = current, onDismiss = { inspectedTask = null })
    }
}

@Composable
private fun BackgroundTaskRow(
    task: DetachedTask,
    now: Long,
    resumeLabel: String = "",
    onResume: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val line = backgroundTaskLine(task, now)
    val runningLabel = stringResource(R.string.tool_status_running)
    val status = if (line.running) runningLabel else daemonStatusText(line.status)
    // 运行中报「已运行多久」（mm:ss），结束报「多久以前」。两者都用系统本地化格式，不写死中文。
    val time = if (line.running) {
        DateUtils.formatElapsedTime(line.ageMs / 1000)
    } else {
        DateUtils.getRelativeTimeSpanString(
            now - line.ageMs,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(lineColor(line)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = line.id,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${line.kind} · $status · $time",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onResume != null) {
            Text(
                text = resumeLabel,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .clickable(onClick = onResume)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 后台任务的只读详情：命令 + 日志尾部。
 *
 * 运行中的任务每秒刷新一次；已结束的任务只读一次。日志文件可能很大，只读最后 [MAX_LOG_BYTES]，
 * 并明确标注“只显示最后 16 KB”，避免用户把截断当成全部输出。
 */
@Composable
private fun BackgroundTaskLogSheet(task: DetachedTask, onDismiss: () -> Unit) {
    var log by remember(task.id) { mutableStateOf<String?>(null) }
    var truncated by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(task.id, task.completedAt) {
        while (true) {
            val loaded = withContext(Dispatchers.IO) { readLogTail(File(task.logPath), MAX_LOG_BYTES) }
            log = loaded.first
            truncated = loaded.second
            if (task.completedAt != null) break
            delay(1_000L)
        }
    }

    OverlayBottomSheet(
        show = true,
        title = task.id,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.background_tasks_command),
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            SelectionContainer {
                Text(
                    text = task.command,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.background_tasks_log),
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            val text = log?.trimEnd()
            if (text.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.background_tasks_log_empty),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                )
            } else {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (truncated) {
                Text(
                    text = stringResource(R.string.background_tasks_log_truncated),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun readLogTail(file: File, maxBytes: Int): Pair<String, Boolean> {
    if (!file.isFile) return "" to false
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8) to (start > 0L)
        }
    }.getOrDefault("" to false)
}

private const val MAX_LOG_BYTES = 16 * 1024

/**
 * 读一个本地 Agent 开关。
 *
 * 与设置页 `SwitchPref` 写的是同一份 prefs（`eta_agent_preferences`）；
 * 这里不用 `Prefs.isEnabled`，因为它只对 `LOCAL_AGENT_KEYS` 里的键读本地，其余一律读 remote。
 */
private fun localSwitch(context: Context, key: String): Boolean {
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    return runCatching { Prefs.localAgentPreferences(context).getBoolean(key, default) }
        .getOrDefault(default)
}

/**
 * 状态码到文案：复用已有的 `daemon_status_*` 三语键；不认识的码原样显示，不猜成「失败」。
 */
@Composable
private fun daemonStatusText(status: String): String = when (status) {
    "completed" -> stringResource(R.string.daemon_status_completed)
    "failed" -> stringResource(R.string.daemon_status_failed)
    "timed_out" -> stringResource(R.string.daemon_status_timed_out)
    "stopped" -> stringResource(R.string.daemon_status_stopped)
    "interrupted" -> stringResource(R.string.daemon_status_interrupted)
    else -> status
}

@Composable
private fun lineColor(line: BackgroundTaskLine): Color = when {
    line.running -> StatusRunning
    line.status == "completed" -> StatusSuccess
    line.status == "failed" || line.status == "timed_out" -> StatusError
    else -> StatusIdle
}
