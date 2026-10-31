package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.ReasoningEffort

/**
 * 一条「已经落成用户消息、还没真正发出去」的 `ask_user` 回答。
 *
 * 为什么需要它：回答是模型明确停下来等的那个答案，语义上就是「下一轮用户消息」。
 * 走排队通道时用户看到的是「排队中 / 插话中」的队列条，切走会话还可能一直发不出去 ——
 * 2026-10-30 真机反馈「回答放到了排队消息上，切回来又要重答」。
 *
 * 现在的顺序是：先把回答写成正常的用户消息（立刻可见、立刻落盘），旧 run 停下来之后再
 * 用 [messageId] 对应的那条消息起新 run —— 不经过队列，也就没有「排队」这个中间态。
 */
internal data class PendingAskAnswer(
    val runId: String,
    val messageId: String,
    val prompt: String,
    val reasoningEffort: ReasoningEffort,
)

/**
 * 按会话记录待发的回答。
 *
 * 拆成独立小对象是为了能用单测钉住两条规则：
 * 1. 同一个会话只允许有一条待发回答（连点两个选项不会发两遍）；
 * 2. 旧 run 还没收尾时**不许**起新 run（取消与新运行之间会抢历史写入）。
 */
internal class PendingAskAnswers {
    private val byConversation = mutableMapOf<String, PendingAskAnswer>()

    /**
     * 放入一条回答；返回被它顶掉的旧回答（没有则 null）。
     *
     * 以前这里是「已存在就拒绝」：用户第一次点没发出去（旧 run 迟迟不收尾）之后再点一次，
     * 界面毫无变化 —— 看上去就是「点了没反应，切回来还让我重答」。现在改成「后一次选择生效」，
     * 由调用方把旧的那条消息换掉，语义更接近用户的重选动作。
     */
    fun put(conversationId: String, answer: PendingAskAnswer): PendingAskAnswer? =
        byConversation.put(conversationId, answer)

    fun get(conversationId: String): PendingAskAnswer? = byConversation[conversationId]

    fun remove(conversationId: String) {
        byConversation.remove(conversationId)
    }

    /**
     * 只能在会话真的空闲时把回答交出去；否则原样留着，等 run 收尾再来一次。
     *
     * 空闲判定不区分「真运行」和「假运行」：假运行态下起新 run 一样会和服务端那条还活着的
     * run 打架，先让停止\/对账流程把它清掉。
     */
    fun takeIfIdle(
        conversationId: String,
        isStreaming: Boolean,
        isCompacting: Boolean,
    ): PendingAskAnswer? {
        if (isStreaming || isCompacting) return null
        return byConversation.remove(conversationId)
    }
}
