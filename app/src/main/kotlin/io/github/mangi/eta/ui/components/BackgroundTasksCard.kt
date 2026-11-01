package io.github.mangi.eta.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.BackgroundTaskLine
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.backgroundTaskLine
import io.github.mangi.eta.agent.terminal.summarizeBackgroundTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
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
 * 只读：不提供停止 / 读日志 —— 这些动作要走 `terminal_job` / `terminal_daemon`，
 * 界面直接触发会让「谁在调度」变成两套真相（与台账卡同一条理由）。
 */
@Composable
internal fun BackgroundTasksCard(modifier: Modifier = Modifier) {
    val records = rememberDetachedTasks() ?: return
    val view = remember(records) { summarizeBackgroundTasks(records) }
    if (view.isEmpty) return
    var expanded by rememberSaveable { mutableStateOf(false) }
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
                view.running.forEach { task -> BackgroundTaskRow(task, now) }
                val rest = view.runningTotal - view.running.size
                if (rest > 0) {
                    Text(
                        text = "+$rest",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 14.dp, top = 3.dp),
                    )
                }
                view.recentFinished.forEach { task -> BackgroundTaskRow(task, now) }
                Text(
                    text = stringResource(R.string.background_tasks_card_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp, start = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun BackgroundTaskRow(task: DetachedTask, now: Long) {
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
    }
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
