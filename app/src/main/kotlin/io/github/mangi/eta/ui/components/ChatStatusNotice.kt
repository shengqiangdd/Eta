package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.agent.runtime.AppExitCause
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天页「现在有什么不对劲」的结论条。
 *
 * 为什么需要它：这些问题原本只在真正失败时才暴露 —— 缺权限要等到工具报错、历史过大要等到点
 * 「重新生成」被 Toast 拒绝。用户看见的是「它怎么不动了」，却拿不到原因（参考 Opifexa 工作台
 * 的「结论先行」做法）。这里把已知的阻塞原因提前摆在输入框上方；一切正常时整条不占位。
 *
 * 三条交互约束（避免它自己变成新的干扰）：
 * 1. 每条都能关：整行点一下或右侧 ×，立刻消失；
 * 2. 关了要记住：权限按问题 id 记忆，只有新问题才再提醒（见 [ChatStatusNoticeDismissal]）；
 * 3. 占地有上限：默认最多两条，其余折成一行「还有 N 条」（见 [chatNoticeLayout]）。
 */
@Immutable
internal enum class ChatStatusNoticeKind {
    /** 消息还在从库里读：此刻列表是空的，但会话并没有丢。 */
    MessagesLoading,

    /** 有权限项未授予：相关工具会在调用时才失败。 */
    PermissionIssues,

    /** 本次会话历史过大、journal 未载入内存：早期消息不能编辑或重新生成。 */
    HistoryDeferred,

    /**
     * 上一次进程被系统终止（内存不足 / 后台清理 / 崩溃）。
     *
     * 进程被 SIGKILL 不会留下任何界面痕迹，用户只会看到「会话突然断了」；
     * 把系统记录的原因摆出来，他才知道该去加白名单，而不是以为会话数据丢了。
     */
    SystemExit,

    /**
     * 当前是「只看对话」或「只看轨迹」，而且这一屏确实有消息被收起。
     *
     * 它和上面三条性质不同：**它不是故障**，是用户主动选的视图，所以排在最后、
     * 用中性色而不是告警色，只负责把「被藏了几条」讲清楚，并给一个一键看全部的出口。
     */
    ViewModeFiltered,
}

/** 提示条的稳定 key：只在「条件消失」时移除，避免用户关掉后同一条又立刻弹回来。 */
internal object ChatStatusNoticeKey {
    const val MessagesLoading = "messages_loading"
    const val HistoryDeferred = "history_deferred"
    private const val ViewFilteredPrefix = "view_filtered:"

    fun viewFiltered(viewMode: ChatViewMode): String = "$ViewFilteredPrefix${viewMode.name}"

    fun isViewFiltered(key: String): Boolean = key.startsWith(ViewFilteredPrefix)
}

/**
 * 用户已经关掉的提示。
 *
 * 权限项按「问题 id 集合」记，而不是按数量或整条文案：关掉一次后，只有**新的**权限问题
 * 出现时才会重新出现；用户在权限页修好其中一项，不会把剩下几项又弹回来。
 *
 * 其余提示是当前这一屏的状态（载入中 / 历史过大 / 视图过滤），跟着会话
 * composition 走；换会话重新判断是正确的，不该把上个会话的「已读」带过去。
 */
@Immutable
internal data class ChatStatusNoticeDismissal(
    val dismissedPermissionIssueIds: Set<String> = emptySet(),
    val dismissedKeys: Set<String> = emptySet(),
) {
    fun hides(
        kind: ChatStatusNoticeKind,
        currentPermissionIssueIds: Set<String>,
        viewMode: ChatViewMode,
    ): Boolean = when (kind) {
        ChatStatusNoticeKind.PermissionIssues ->
            currentPermissionIssueIds.isNotEmpty() &&
                dismissedPermissionIssueIds.containsAll(currentPermissionIssueIds)
        ChatStatusNoticeKind.MessagesLoading ->
            ChatStatusNoticeKey.MessagesLoading in dismissedKeys
        ChatStatusNoticeKind.HistoryDeferred ->
            ChatStatusNoticeKey.HistoryDeferred in dismissedKeys
        ChatStatusNoticeKind.ViewModeFiltered ->
            ChatStatusNoticeKey.viewFiltered(viewMode) in dismissedKeys
        // 进程退出提示的消失由 AgentAppState 负责，避免两套状态各记一份。
        ChatStatusNoticeKind.SystemExit -> false
    }

    fun dismiss(
        kind: ChatStatusNoticeKind,
        currentPermissionIssueIds: Set<String>,
        viewMode: ChatViewMode,
    ): ChatStatusNoticeDismissal = when (kind) {
        ChatStatusNoticeKind.PermissionIssues -> copy(
            dismissedPermissionIssueIds = dismissedPermissionIssueIds + currentPermissionIssueIds,
        )
        ChatStatusNoticeKind.MessagesLoading -> copy(
            dismissedKeys = dismissedKeys + ChatStatusNoticeKey.MessagesLoading,
        )
        ChatStatusNoticeKind.HistoryDeferred -> copy(
            dismissedKeys = dismissedKeys + ChatStatusNoticeKey.HistoryDeferred,
        )
        ChatStatusNoticeKind.ViewModeFiltered -> copy(
            dismissedKeys = dismissedKeys + ChatStatusNoticeKey.viewFiltered(viewMode),
        )
        ChatStatusNoticeKind.SystemExit -> this
    }

    /**
     * 条件已经消失的提示要从记忆里移除，否则同一问题再次发生会被旧记录吞掉。
     *
     * 权限项按 id 逐个对账（只保留「现在仍然缺的」），而不是「全部正常才清空」：
     * 用户修好其中一项后，那一项就从记忆里消失；之后它再被关掉时能重新提醒，
     * 而不是被上一次的「已读」永久压住。
     *
     * [permissionIssuesKnown] = false 表示首次权限检查还没跑完（此刻 id 集合必然是空的）——
     * 这时**不能**对账：否则每次冷启动的那一瞬间都会把持久化的忽略记录抹掉，
     * 用户看到的就是「关掉的提示每次开机又回来了」。
     */
    fun reconcile(
        currentPermissionIssueIds: Set<String>,
        permissionIssuesKnown: Boolean,
        messagesDeferred: Boolean,
        journalDeferred: Boolean,
        hiddenMessageCount: Int,
    ): ChatStatusNoticeDismissal {
        var next = this
        if (permissionIssuesKnown) {
            val resolved = dismissedPermissionIssueIds - currentPermissionIssueIds
            if (resolved.isNotEmpty()) {
                next = next.copy(dismissedPermissionIssueIds = dismissedPermissionIssueIds - resolved)
            }
        }
        if (!messagesDeferred && ChatStatusNoticeKey.MessagesLoading in next.dismissedKeys) {
            next = next.copy(dismissedKeys = next.dismissedKeys - ChatStatusNoticeKey.MessagesLoading)
        }
        if (!journalDeferred && ChatStatusNoticeKey.HistoryDeferred in next.dismissedKeys) {
            next = next.copy(dismissedKeys = next.dismissedKeys - ChatStatusNoticeKey.HistoryDeferred)
        }
        if (hiddenMessageCount == 0 && next.dismissedKeys.any { ChatStatusNoticeKey.isViewFiltered(it) }) {
            next = next.copy(
                dismissedKeys = next.dismissedKeys
                    .filterNot { ChatStatusNoticeKey.isViewFiltered(it) }
                    .toSet(),
            )
        }
        return next
    }
}

/**
 * 撤销一次关闭：只把「那一次新加进去的」id / key 从当前状态里去掉。
 *
 * 为什么不能直接 `applyNoticeDismissal(before)`：Snackbar 只给几秒，用户很可能在这几秒里
 * 又关掉另一条提示。整份回滚会把后来的操作一起抹掉，表现为「点了撤销，另一条又弹回来」；
 * 而只减掉本次新增的部分，既能恢复这一条，也不动别的。
 *
 * [before] / [after] 是那次关闭前后的状态（[dismiss] 只会做加法，所以 `after - before`
 * 就精确等于这次新加的内容）。撤销时的当前状态可能是后来演变过的，据此做差集即可。
 */
internal fun ChatStatusNoticeDismissal.undoDismissal(
    before: ChatStatusNoticeDismissal,
    after: ChatStatusNoticeDismissal,
): ChatStatusNoticeDismissal = copy(
    dismissedPermissionIssueIds =
        dismissedPermissionIssueIds - (after.dismissedPermissionIssueIds - before.dismissedPermissionIssueIds),
    dismissedKeys = dismissedKeys - (after.dismissedKeys - before.dismissedKeys),
)

/** 纯函数便于单测：只返回当前确实需要让用户知道的条目，顺序即展示顺序（阻塞越强越靠前）。 */
internal fun chatStatusNotices(
    missingPermissionCount: Int,
    journalDeferred: Boolean,
    messagesDeferred: Boolean = false,
    hiddenMessageCount: Int = 0,
    systemExit: AppExitCause = AppExitCause.NONE,
): List<ChatStatusNoticeKind> = buildList {
    if (messagesDeferred) add(ChatStatusNoticeKind.MessagesLoading)
    if (missingPermissionCount > 0) add(ChatStatusNoticeKind.PermissionIssues)
    if (systemExit != AppExitCause.NONE) add(ChatStatusNoticeKind.SystemExit)
    if (journalDeferred) add(ChatStatusNoticeKind.HistoryDeferred)
    // 只有真的藏了东西才占位：选了收窄视图但这一屏没有可收的消息时，不该多出一条常驻提示。
    if (hiddenMessageCount > 0) add(ChatStatusNoticeKind.ViewModeFiltered)
}

/** 收起状态下最多铺开几条：再多的部分折成一行「还有 N 条」。 */
internal const val CHAT_NOTICE_COLLAPSED_ROWS = 2

@Immutable
internal data class ChatNoticeLayout(
    val visible: List<ChatStatusNoticeKind>,
    val hiddenCount: Int,
)

/**
 * 纯函数：决定这一屏铺开哪几条。
 *
 * 提示条是「旁注」不是主内容 —— 不限制条数时，五条同时成立会把输入框顶掉小半屏，
 * 长会话里正好挡住最想看的消息。默认只铺 [collapsedRows] 条，其余折起来；
 * 展开是用户主动动作，条件变少后自动收回。
 */
internal fun chatNoticeLayout(
    notices: List<ChatStatusNoticeKind>,
    expanded: Boolean,
    collapsedRows: Int = CHAT_NOTICE_COLLAPSED_ROWS,
): ChatNoticeLayout {
    if (expanded || notices.size <= collapsedRows) {
        return ChatNoticeLayout(visible = notices, hiddenCount = 0)
    }
    return ChatNoticeLayout(
        visible = notices.take(collapsedRows),
        hiddenCount = notices.size - collapsedRows,
    )
}

@Composable
internal fun ChatStatusNoticeBar(
    notices: List<ChatStatusNoticeKind>,
    missingPermissionCount: Int,
    onOpenPermissions: () -> Unit,
    onDismiss: (ChatStatusNoticeKind) -> Unit = {},
    hiddenMessageCount: Int = 0,
    viewMode: ChatViewMode = ChatViewMode.ALL,
    onShowAllMessages: () -> Unit = {},
    systemExit: AppExitCause = AppExitCause.NONE,
    onDismissSystemExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val foldable = notices.size > CHAT_NOTICE_COLLAPSED_ROWS
    // 条数掉回上限以内就收回：展开只是临时看清全部，不该把「展开」留到只剩一两条的场合。
    LaunchedEffect(foldable) {
        if (!foldable) expanded = false
    }
    val layout = chatNoticeLayout(notices, expanded = expanded && foldable)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        layout.visible.forEach { notice ->
            when (notice) {
                ChatStatusNoticeKind.MessagesLoading -> NoticeRow(
                    text = stringResource(R.string.conversation_messages_loading_5c31d8),
                    onDismiss = { onDismiss(ChatStatusNoticeKind.MessagesLoading) },
                )

                ChatStatusNoticeKind.PermissionIssues -> NoticeRow(
                    text = stringResource(R.string.ui_permission_health_3048bb) + " · " +
                        pluralStringResource(
                            R.plurals.permission_health_issues,
                            missingPermissionCount,
                            missingPermissionCount,
                        ),
                    actionLabel = stringResource(R.string.chat_notice_review_permissions),
                    // 「去处理」不是「知道了」：不动忽略记录。用户什么都没修就回来时，
                    // 提示应该还在（否则模块只能在真正调用时才默默失败）。
                    onAction = onOpenPermissions,
                    onDismiss = { onDismiss(ChatStatusNoticeKind.PermissionIssues) },
                )

                ChatStatusNoticeKind.SystemExit -> NoticeRow(
                    text = stringResource(
                        when (systemExit) {
                            AppExitCause.LOW_MEMORY -> R.string.conversation_system_exit_low_memory_7d3a1c
                            AppExitCause.CRASH -> R.string.conversation_system_exit_crash_4f90be
                            AppExitCause.START_TIMEOUT -> R.string.conversation_system_exit_start_timeout_9b2e47
                            else -> R.string.conversation_system_exit_system_kill_2c6d85
                        },
                    ),
                    onDismiss = onDismissSystemExit,
                )

                ChatStatusNoticeKind.HistoryDeferred -> NoticeRow(
                    text = stringResource(R.string.state_ui_this_conversation_s_history_is_too_large_a71f3d),
                    onDismiss = { onDismiss(ChatStatusNoticeKind.HistoryDeferred) },
                )

                ChatStatusNoticeKind.ViewModeFiltered -> NoticeRow(
                    // 同一个提示位要讲清「被收起的是什么」：对话视图收的是过程，轨迹视图收的
                    // 是正文。文案说反了，用户会以为消息丢了。
                    text = pluralStringResource(
                        if (viewMode == ChatViewMode.TRACE) {
                            R.plurals.chat_view_mode_hidden_replies
                        } else {
                            R.plurals.chat_view_mode_hidden_process
                        },
                        hiddenMessageCount,
                        hiddenMessageCount,
                    ),
                    actionLabel = stringResource(R.string.chat_dialog_only_show_all),
                    onAction = onShowAllMessages,
                    onDismiss = { onDismiss(ChatStatusNoticeKind.ViewModeFiltered) },
                    icon = Icons.Rounded.FilterAlt,
                    emphasized = false,
                )
            }
        }
        if (foldable) {
            NoticeOverflowRow(
                hiddenCount = layout.hiddenCount,
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }
    }
}

/**
 * 折叠行：把「还有几条」说清楚，点一下就地展开 / 收起。
 *
 * 不再套一层卡片 —— 它本身就是一行提示，多一层容器只会更挤。
 */
@Composable
private fun NoticeOverflowRow(
    hiddenCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.chat_notice_collapse)
            } else {
                stringResource(R.string.chat_notice_more, hiddenCount)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            maxLines = 1,
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun NoticeRow(
    text: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.Rounded.Warning,
    emphasized: Boolean = true,
) {
    val accent = if (emphasized) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    EtaCard(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        onClick = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = accent,
                    maxLines = 1,
                )
            }
            // 整行点击即忽略；× 只是把这个动作明说出来，避免用户以为只能点「去处理」。
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_notice_dismiss),
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(7.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
