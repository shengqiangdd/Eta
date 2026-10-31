package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi

/**
 * 聊天页两种收窄视图的过滤规则。
 *
 * 为什么要有：真机上一个长会话是上千条消息，绝大多数是思考片段、工具调用和运行轨迹。
 * 两类相反的需求都会撞上它 ——
 * - 回头翻「我问了什么、它答了什么」时要滚过几十屏过程细节，问答本身被淹掉；
 * - 查「这一步到底做了什么、为什么慢或失败」时又要滚过成段的正文。
 * 前者是 [ChatViewMode.DIALOG]，后者是 [ChatViewMode.TRACE]，两者互斥。
 *
 * 保留的几类，各自都有理由：
 * - UserMessageUi：两种视图都留作回合锚点，否则一屏消息分不清属于哪次提问。
 * - SystemNoticeMessageUi：停止、失败、重试、上下文压缩这些是对结果的解释，藏掉会让用户
 *   看到一段莫名中断的对话。
 * - SuggestionChipsMessageUi：只在空对话时出现，属于「对话」而不是「轨迹」，轨迹视图收起。
 *
 * 纯函数，便于单测；不做缓存，重算时机交给调用方的 remember。
 */
internal fun isProcessMessage(message: AgentChatMessageUi): Boolean = when (message) {
    is ThinkingMessageUi,
    is ToolSummaryMessageUi,
    is ToolActivityMessageUi,
    -> true

    else -> false
}

/** 「对话」侧消息：助手正文与空对话时的推荐提问。轨迹视图把它们收起。 */
internal fun isReplyMessage(message: AgentChatMessageUi): Boolean = when (message) {
    is AgentMessageUi,
    is SuggestionChipsMessageUi,
    -> true

    else -> false
}

/** 该视图会收起哪些消息；[ChatViewMode.ALL] 返回 null 表示一条都不收。 */
private fun hiddenPredicate(mode: ChatViewMode): ((AgentChatMessageUi) -> Boolean)? = when (mode) {
    ChatViewMode.ALL -> null
    ChatViewMode.DIALOG -> ::isProcessMessage
    ChatViewMode.TRACE -> ::isReplyMessage
}

/**
 * 按视图模式过滤出这一屏要画的消息。
 *
 * 没有需要收起的消息时原样返回入参：调用方多半把结果放进 remember / derivedStateOf，
 * 返回同一实例能让引用相等判断短路，避免整条消息流因为一次无关重组而重建时间线。
 */
internal fun filterForViewMode(
    messages: List<AgentChatMessageUi>,
    mode: ChatViewMode,
): List<AgentChatMessageUi> {
    val hidden = hiddenPredicate(mode) ?: return messages
    return if (messages.none(hidden)) messages else messages.filterNot(hidden)
}

/** 当前视图收起了多少条：状态条据此告诉用户「这里不是空的，只是收起来了」。 */
internal fun countHiddenByViewMode(messages: List<AgentChatMessageUi>, mode: ChatViewMode): Int =
    hiddenPredicate(mode)?.let { hidden -> messages.count(hidden) } ?: 0
