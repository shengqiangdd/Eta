package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentAskUserToolCatalog

/** 一次仍在等待用户回答的提问。 */
@Immutable
internal data class AgentAskUi(
    val messageId: String,
    val question: String,
    val options: List<String>,
)

/**
 * 从消息流里找出「还没被回答的提问」。
 *
 * 只认最后一条 `ask_user`：更早的提问即使没人回答，也已经被后续轮次覆盖，再显示会让用户点到过期的按钮。
 * 是否已回答看它之后有没有用户消息 —— 点选项和手打文字最终都会落成 [UserMessageUi]。
 * 选项现在由 `AgentAppState.answerPendingAsk` 直接落成用户消息（运行中也一样，不排队）。
 */
internal fun pendingAskOf(
    messages: List<AgentChatMessageUi>,
    /**
     * 队列里已经有用户输入。
     *
     * 选项回答已经不走队列了；守的是「用户直接把回答打进输入框、运行中点发送」这条路：
     * 它仍然会先入队，此时消息流里还没有用户消息。
     */
    hasQueuedInput: Boolean = false,
): AgentAskUi? {
    if (hasQueuedInput) return null
    val index = messages.indexOfLast { message ->
        message is ToolActivityMessageUi &&
            message.toolName == AgentAskUserToolCatalog.NAME &&
            !message.askQuestion.isNullOrBlank()
    }
    if (index < 0) return null
    val ask = messages[index] as ToolActivityMessageUi
    if (ask.status == ToolActivityStatusUi.Failed) return null
    if (messages.drop(index + 1).any { it is UserMessageUi }) return null
    return AgentAskUi(
        messageId = ask.id,
        question = ask.askQuestion.orEmpty(),
        options = ask.askOptions,
    )
}
