package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentGoal
import io.github.mangi.eta.agent.model.AgentGoalPhase
import io.github.mangi.eta.agent.runtime.AgentGoalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 读取某个会话的长期目标。
 *
 * 与清单同源：[AgentGoalStore.changes] 自增就重读，因为 `goal` 工具是就地更新同一条工具卡片，
 * 消息条数不会变。已完成的目标不显示 —— 它留在文件里可查，但不再影响本轮。
 */
@Composable
internal fun rememberAgentGoal(scopeId: String?): AgentGoal? {
    val context = LocalContext.current
    val store = remember(context) { AgentGoalStore.forApp(context) }
    val changes by AgentGoalStore.changes.collectAsState()
    var goal by remember(scopeId) { mutableStateOf<AgentGoal?>(null) }
    LaunchedEffect(scopeId, changes) {
        val id = scopeId?.takeIf(String::isNotBlank)
        goal = if (id == null) null else withContext(Dispatchers.IO) { store.load(id) }
    }
    return goal?.takeIf { it.phase != AgentGoalPhase.COMPLETED }
}

/**
 * 底部常驻的长期目标卡：收起时一行「目标 + 状态」，点开看完整目标与受阻原因。
 *
 * 只做展示，不提供按钮：改目标由模型调 `goal` 工具完成，界面上少一个会发命令的入口，
 * 也就少一处「保存成功但没生效」的可能。
 */
@Composable
internal fun AgentGoalCard(goal: AgentGoal, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Flag,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(14.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.goal_card_title),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = " · " + stringResource(goalPhaseLabel(goal.phase)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (goal.phase == AgentGoalPhase.BLOCKED) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
                if (!expanded) {
                    Text(
                        text = goal.objective,
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
                    if (expanded) R.string.goal_card_collapse else R.string.goal_card_expand,
                ),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp, start = 22.dp)) {
                Text(
                    text = goal.objective,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (goal.phase == AgentGoalPhase.BLOCKED) {
                    goal.blockedReason?.let { reason ->
                        Text(
                            text = stringResource(R.string.goal_card_blocked, reason),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.goal_card_revision, goal.revision),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * 状态文字走资源而不是 [AgentGoalPhase.label]：后者是给模型看的提示文案，
 * 直接显示在英语界面上会出现中文状态。
 */
@Composable
private fun goalPhaseLabel(phase: AgentGoalPhase): Int = when (phase) {
    AgentGoalPhase.ACTIVE -> R.string.goal_phase_active
    AgentGoalPhase.PAUSED -> R.string.goal_phase_paused
    AgentGoalPhase.BLOCKED -> R.string.goal_phase_blocked
    AgentGoalPhase.COMPLETED -> R.string.goal_phase_completed
}
