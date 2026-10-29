package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentGoal
import io.github.mangi.eta.agent.model.AgentGoalPhase
import io.github.mangi.eta.agent.runtime.AgentGoalStore
import io.github.mangi.eta.agent.runtime.GoalMutation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val GoalActionSize = 28.dp
private val GoalActionIconSize = 14.dp
private val GoalEditorMaxHeight = 120.dp

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
 * 动作照 dsh 的 GoalBar：暂停 / 继续 / 行内编辑 / 清除，动作期间单飞（同一个目标同时只允许一次写），
 * 失败就地显示在卡片里，不弹窗、不改数据。写回前先比对 [AgentGoal.revision]：模型可能刚用
 * `goal update` 改过，拿界面上的旧快照覆盖会吃掉那次修改，所以 revision 变了就报错让用户重试。
 *
 * 与 dsh 的差异只在摆放：桌面把四个图标放在同一行栏里，手机收起态放不下，
 * 因此动作放在展开区；动作集合与语义不变（没有确认弹窗，清除后卡片直接消失 —— 同 dsh）。
 */
@Composable
internal fun AgentGoalCard(
    goal: AgentGoal,
    scopeId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 资源取用走 LocalResources：LocalContext 在 Configuration 变化（字号/语言）后
    // 可能拿到旧值，lint 会直接报错。
    val resources = LocalResources.current
    val store = remember(context) { AgentGoalStore.forApp(context) }
    val scope = scopeId?.takeIf(String::isNotBlank)
    val coroutines = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // 状态要等下一帧才禁用按钮；ref 是同步的，用来关掉同一帧里连点两次的窗口（同 dsh 的 pendingRef）。
    val inFlight = remember { AtomicBoolean(false) }

    // 目标换了身份（被清、被替换、被模型重建）就作废本地编辑态：
    // 否则残留草稿的「保存」会写到新目标上（dsh 在 goalId 变化时同样清理）。
    LaunchedEffect(goal.id) {
        editing = false
        actionError = null
    }

    fun runAction(action: suspend (String) -> GoalMutation, onSaved: () -> Unit = {}) {
        val id = scope
        if (id == null) {
            actionError = resources.getString(R.string.goal_card_error_scope)
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        pending = true
        actionError = null
        coroutines.launch {
            val outcome = withContext(Dispatchers.IO) { action(id) }
            pending = false
            inFlight.set(false)
            if (outcome == GoalMutation.SAVED) {
                onSaved()
            } else {
                actionError = resources.getString(goalMutationMessage(outcome))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            // 编辑时点空白处不应该把卡片收起来，否则草稿还在、表单却没了。
            .clickable(enabled = !editing) { expanded = !expanded }
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
                if (editing) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { next ->
                            if (next.length <= AgentGoal.MAX_OBJECTIVE_CHARS) draft = next
                        },
                        enabled = !pending,
                        textStyle = MiuixTheme.textStyles.footnote2.copy(
                            color = MiuixTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp, max = GoalEditorMaxHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
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
                }
                actionError?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (editing) {
                        Spacer(modifier = Modifier.weight(1f))
                        GoalActionButton(
                            icon = Icons.Rounded.Check,
                            description = R.string.goal_card_action_save,
                            enabled = !pending && draft.trim().isNotEmpty(),
                        ) {
                            val text = draft.trim()
                            runAction({ id ->
                                store.mutate(id, goal.revision) { it.copy(objective = text) }
                            }) { editing = false }
                        }
                        GoalActionButton(
                            icon = Icons.Rounded.Close,
                            description = R.string.goal_card_action_cancel,
                            enabled = !pending,
                        ) { editing = false }
                    } else {
                        Text(
                            text = stringResource(R.string.goal_card_revision, goal.revision),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        if (goal.phase == AgentGoalPhase.ACTIVE) {
                            GoalActionButton(
                                icon = Icons.Rounded.Pause,
                                description = R.string.goal_card_action_pause,
                                enabled = !pending,
                            ) {
                                runAction({ id ->
                                    store.mutate(id, goal.revision) {
                                        it.copy(phase = AgentGoalPhase.PAUSED)
                                    }
                                })
                            }
                        }
                        if (goal.phase == AgentGoalPhase.PAUSED) {
                            GoalActionButton(
                                icon = Icons.Rounded.PlayArrow,
                                description = R.string.goal_card_action_resume,
                                enabled = !pending,
                            ) {
                                runAction({ id ->
                                    store.mutate(id, goal.revision) {
                                        it.copy(phase = AgentGoalPhase.ACTIVE, blockedReason = null)
                                    }
                                })
                            }
                        }
                        GoalActionButton(
                            icon = Icons.Rounded.Edit,
                            description = R.string.goal_card_action_edit,
                            enabled = !pending,
                        ) {
                            draft = goal.objective
                            actionError = null
                            editing = true
                        }
                        GoalActionButton(
                            icon = Icons.Rounded.Delete,
                            description = R.string.goal_card_action_clear,
                            enabled = !pending,
                        ) {
                            runAction({ id ->
                                if (store.clear(id)) GoalMutation.SAVED else GoalMutation.FAILED
                            })
                        }
                    }
                }
            }
        }
    }
}

/** 小号图标按钮：与队列条同一套尺寸（28dp 触控区 / 14dp 图标），行高不因动作行变高。 */
@Composable
private fun GoalActionButton(
    icon: ImageVector,
    description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minWidth = GoalActionSize,
        minHeight = GoalActionSize,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            modifier = Modifier.size(GoalActionIconSize),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.4f)
            },
        )
    }
}

/** 写回失败的提示文案；`SAVED` 不会走到这里。 */
private fun goalMutationMessage(outcome: GoalMutation): Int = when (outcome) {
    GoalMutation.MISSING -> R.string.goal_card_error_missing
    GoalMutation.STALE -> R.string.goal_card_error_stale
    GoalMutation.INVALID -> R.string.goal_card_error_invalid
    GoalMutation.FAILED -> R.string.goal_card_error_failed
    GoalMutation.SAVED -> R.string.goal_card_error_failed
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
