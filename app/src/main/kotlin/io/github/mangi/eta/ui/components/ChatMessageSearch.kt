package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.matches

/**
 * 系统通知标题资源。
 *
 * 消息渲染、会话筛选、会话内搜索都需要把 `SystemNoticeCode` 翻成可读文字；三处各写一份
 * when 迟早会漂移（新增一种 code 只改一处）。这里收成唯一入口。
 */
internal fun systemNoticeTitleRes(code: SystemNoticeCode): Int = when (code) {
    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
}

/**
 * 会话内搜索：按出现顺序返回命中消息的 id。
 *
 * 空查询返回空列表而不是全部消息 —— 调用方据此不显示「N / M」，避免一打开搜索框就显示
 * 「1 / 8435」这种没有信息量的数字。
 */
internal fun matchMessageIds(
    messages: List<AgentChatMessageUi>,
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): List<String> {
    if (query.isBlank()) return emptyList()
    return messages.filter { it.matches(query, noticeText) }.map { it.id }
}

/**
 * 折叠工作过程块里有多少条命中。
 *
 * 过程消息被折叠进同一个块，块在时间线上只有一个 index —— 命中落在块内时「下一个」
 * 只能滚到块首行，视口几乎不动。界面据此在块摘要上写「本块内 N 条命中」，让用户
 * 知道按钮真的在前进，而不是坏了。
 */
internal fun countProcessBlockMatches(
    messages: List<AgentChatMessageUi>,
    matchIds: Set<String>,
): Int = if (matchIds.isEmpty()) 0 else messages.count { it.id in matchIds }

/**
 * 当前命中在这一块里排第几条（1 基）；当前命中不在本块时返回 0。
 *
 * 只给「本块内 N 条命中」还不够：命中全落在同一个块里时数字不动，看着仍像没前进。
 * 带上序号，连续点「下一个」时数字在走，用户才能确认按钮有效。
 */
internal fun processBlockHitPosition(
    messages: List<AgentChatMessageUi>,
    matchIds: Set<String>,
    currentMessageId: String?,
): Int {
    if (matchIds.isEmpty() || currentMessageId == null) return 0
    var position = 0
    messages.forEach { message ->
        if (message.id in matchIds) {
            position += 1
            if (message.id == currentMessageId) return position
        }
    }
    return 0
}

/**
 * 会话内搜索条的状态与回调。
 *
 * 打包成一个对象而不是七八个参数：`AgentChatBody → AgentChatScaffold → 搜索栏` 这条链
 * 已经很长，再加散参数会没法读。
 */
@Immutable
internal data class ChatSearchUi(
    val query: String,
    val matchCount: Int,
    val cursor: Int,
    /** 还有多少条匹配落在被「只看对话」收起的过程消息里。 */
    val hiddenMatchCount: Int,
    val onQueryChange: (String) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onClose: () -> Unit,
    /**
     * 被收起的那批匹配属于助手正文（轨迹视图）还是过程消息（对话视图）。
     *
     * 提示语必须跟着换：轨迹视图收起的是正文，却写「在已收起的过程消息里」就是在骗人。
     */
    val hiddenMatchesAreReplies: Boolean = false,
) {
    val hasMatches: Boolean get() = matchCount > 0
    /** 展示用的「3 / 12」；没有匹配时为空串，由调用方决定显示什么。 */
    val positionLabel: String
        get() = if (matchCount == 0) "" else "${(cursor + 1).coerceIn(1, matchCount)} / $matchCount"
}

/** 请求把某条消息滚进视野。token 让「连续点同一个结果」也能再次触发。 */
@Immutable
internal data class ChatScrollRequest(
    val messageId: String,
    val token: Int,
)
