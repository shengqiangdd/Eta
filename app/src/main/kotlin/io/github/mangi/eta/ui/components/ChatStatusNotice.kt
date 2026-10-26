package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
internal fun ChatStatusNoticeBar(
    notices: List<ChatStatusNoticeKind>,
    missingPermissionCount: Int,
    onOpenPermissions: () -> Unit,
    hiddenMessageCount: Int = 0,
    viewMode: ChatViewMode = ChatViewMode.ALL,
    onShowAllMessages: () -> Unit = {},
    systemExit: AppExitCause = AppExitCause.NONE,
    onDismissSystemExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notices.forEach { notice ->
            when (notice) {
                ChatStatusNoticeKind.MessagesLoading -> NoticeRow(
                    text = stringResource(R.string.conversation_messages_loading_5c31d8),
                    onAction = null,
                )

                ChatStatusNoticeKind.PermissionIssues -> NoticeRow(
                    text = stringResource(R.string.ui_permission_health_3048bb) + " · " +
                        pluralStringResource(
                            R.plurals.permission_health_issues,
                            missingPermissionCount,
                            missingPermissionCount,
                        ),
                    onAction = onOpenPermissions,
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
                    onAction = onDismissSystemExit,
                )

                ChatStatusNoticeKind.HistoryDeferred -> NoticeRow(
                    text = stringResource(R.string.state_ui_this_conversation_s_history_is_too_large_a71f3d),
                    onAction = null,
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
                    onAction = onShowAllMessages,
                    icon = Icons.Rounded.FilterAlt,
                    emphasized = false,
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(
    text: String,
    onAction: (() -> Unit)?,
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
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        onClick = onAction,
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
                    .padding(start = 8.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onAction != null) {
                // 整行可点，只用箭头提示；不再塞一个和文案重复的按钮。
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}
