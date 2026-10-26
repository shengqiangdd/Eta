package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentTodoItem
import io.github.mangi.eta.agent.model.AgentTodoPlan
import io.github.mangi.eta.agent.model.AgentTodoStatus
import io.github.mangi.eta.agent.runtime.AgentTodoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 读取某个会话当前的任务清单。
 *
 * 依赖 [AgentTodoStore.changes] 而不是消息条数：`todo_write` 的结果是就地更新同一条工具卡片，
 * 消息数量不会变化，按条数刷新会看不到清单更新。
 */
@Composable
internal fun rememberTodoPlan(scopeId: String?): AgentTodoPlan? {
    val context = LocalContext.current
    val store = remember(context) { AgentTodoStore.forApp(context) }
    val changes by AgentTodoStore.changes.collectAsState()
    var plan by remember(scopeId) { mutableStateOf<AgentTodoPlan?>(null) }
    LaunchedEffect(scopeId, changes) {
        val id = scopeId?.takeIf(String::isNotBlank)
        plan = if (id == null) null else withContext(Dispatchers.IO) { store.load(id) }
    }
    return plan?.takeIf { !it.isEmpty }
}

/**
 * 底部常驻的任务清单卡：收起时一行摘要 + 当前步骤，点开看完整清单。
 *
 * 展开状态不受清单更新影响 —— 用户点开就是为了盯着进度看。
 */
@Composable
internal fun AgentTodoPlanCard(plan: AgentTodoPlan, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = plan.items.firstOrNull { it.status == AgentTodoStatus.IN_PROGRESS }
        ?: plan.items.firstOrNull { it.status == AgentTodoStatus.PENDING }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.todo_plan_title),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = if (plan.allCompleted) {
                            " · " + stringResource(R.string.todo_plan_all_done)
                        } else {
                            " · ${plan.completedCount}/${plan.items.size}"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (plan.allCompleted) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                if (!expanded && current != null) {
                    Text(
                        text = stringResource(R.string.todo_plan_current, current.content),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.todo_plan_collapse else R.string.todo_plan_expand,
                ),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                plan.items.forEach { item -> TodoPlanRow(item) }
            }
        }
    }
}

@Composable
private fun TodoPlanRow(item: AgentTodoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodoStatusMark(item.status)
        Text(
            text = item.content,
            style = MiuixTheme.textStyles.footnote2,
            color = when (item.status) {
                AgentTodoStatus.COMPLETED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                AgentTodoStatus.IN_PROGRESS -> MiuixTheme.colorScheme.primary
                AgentTodoStatus.PENDING -> MiuixTheme.colorScheme.onSurface
            },
            textDecoration = if (item.status == AgentTodoStatus.COMPLETED) {
                TextDecoration.LineThrough
            } else {
                null
            },
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
    }
}

@Composable
private fun TodoStatusMark(status: AgentTodoStatus) {
    val size = 12.dp
    when (status) {
        AgentTodoStatus.COMPLETED -> Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .background(MiuixTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(9.dp),
            )
        }

        AgentTodoStatus.IN_PROGRESS -> Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary),
        )

        AgentTodoStatus.PENDING -> Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
        )
    }
}
