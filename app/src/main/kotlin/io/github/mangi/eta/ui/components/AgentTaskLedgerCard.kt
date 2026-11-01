package io.github.mangi.eta.ui.components

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
import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskRecord
import io.github.mangi.eta.agent.model.AgentTaskStatus
import io.github.mangi.eta.agent.runtime.AgentTaskLedgerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 卡片里最多列几条；再多用一行 `+N` 收尾（完整列表在 `tasks` 工具里）。 */
private const val MAX_CARD_ROWS = 8

/**
 * 读取某个会话的子代理任务台账。
 *
 * 与 [rememberTodoPlan] 同一机制：依赖 [AgentTaskLedgerStore.changes] 而不是消息条数 ——
 * 子代理结束时只改磁盘上的状态，消息列表不会变，按消息刷新永远看不到「跑完了」。
 */
@Composable
internal fun rememberTaskLedger(scopeId: String?): AgentTaskLedger? {
    val context = LocalContext.current
    val store = remember(context) { AgentTaskLedgerStore.forApp(context) }
    val changes by AgentTaskLedgerStore.changes.collectAsState()
    var ledger by remember(scopeId) { mutableStateOf<AgentTaskLedger?>(null) }
    LaunchedEffect(scopeId, changes) {
        val id = scopeId?.takeIf(String::isNotBlank)
        ledger = if (id == null) null else withContext(Dispatchers.IO) { store.load(id) }
    }
    return ledger?.takeIf { !it.isEmpty }
}

/**
 * 底部常驻的子代理任务卡：收起一行汇总，点开看每条的 id 与状态。
 *
 * 为什么要有它：台账进系统提示后模型知道了，但用户还看不到 —— `subagent` 的结果只挂在
 * 各自的工具消息里，会话一长就滚出屏幕，也回答不了「刚才那个还在跑吗」。
 *
 * 只读：这里不提供 resume / 取消。工具调用必须经过模型，界面直接触发会让「谁在调度」
 * 变成两套真相。
 */
@Composable
internal fun AgentTaskLedgerCard(ledger: AgentTaskLedger, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = ledger.cardSummary()
    val title = stringResource(R.string.task_ledger_title)
    val runningLabel = stringResource(R.string.tool_status_running)
    val failedLabel = stringResource(R.string.tool_status_failed)
    val cancelledLabel = stringResource(R.string.status_cancelled)
    val primary = MiuixTheme.colorScheme.primary
    val error = MiuixTheme.colorScheme.error
    // 计数全拼进同一个 Text：Row 里并排几个 Text 时，后面的会被挤成零宽，直接看不见。
    // 失败与已取消也放在收起行里 —— 只显示「已完成/总数」时，一条失败要展开才知道。
    val header = buildAnnotatedString {
        append(title)
        append(" · ${summary.completed}/${summary.total}")
        if (summary.running > 0) {
            withStyle(SpanStyle(color = primary)) { append(" · $runningLabel ${summary.running}") }
        }
        if (summary.failed > 0) {
            withStyle(SpanStyle(color = error)) { append(" · $failedLabel ${summary.failed}") }
        }
        if (summary.cancelled > 0) append(" · $cancelledLabel ${summary.cancelled}")
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
                ledger.entries.take(MAX_CARD_ROWS).forEach { entry -> TaskLedgerRow(entry) }
                val rest = ledger.entries.size - MAX_CARD_ROWS
                if (rest > 0) {
                    Text(
                        text = "+$rest",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 14.dp, top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskLedgerRow(entry: AgentTaskRecord) {
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
                .background(entry.status.color()),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.status.label()} · ${entry.id}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 台账状态到语义色/文案的映射；文案复用工具状态的现有键，不新增一套同样的词。 */
@Composable
private fun AgentTaskStatus.color(): Color = when (this) {
    AgentTaskStatus.RUNNING -> StatusRunning
    AgentTaskStatus.COMPLETED -> StatusSuccess
    AgentTaskStatus.FAILED -> StatusError
    AgentTaskStatus.CANCELLED -> StatusIdle
}

@Composable
private fun AgentTaskStatus.label(): String = stringResource(
    when (this) {
        AgentTaskStatus.RUNNING -> R.string.tool_status_running
        AgentTaskStatus.COMPLETED -> R.string.tool_status_success
        AgentTaskStatus.FAILED -> R.string.tool_status_failed
        AgentTaskStatus.CANCELLED -> R.string.status_cancelled
    },
)
