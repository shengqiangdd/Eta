package io.github.mangi.eta.agent.model

/**
 * 回合结束后「工作过程」什么时候收回折叠态，对齐 dsh 的 `collapseTiming`。
 *
 * dsh 只提供两个取值，含义是「已完成的回合从实时形态退回历史形态」的时机：
 * - `completion`：回合结束就收；摘要行仍然说明这一段做了什么。
 * - `next-input`：结束后先保持展开，等用户真的发出下一条消息再收。刚看完答案还想回头
 *   核对工具调用时，不必先手动点一次展开。
 *
 * 与 dsh 同名同值（`next-input` 带连字符），方便对照；未知值一律回退默认。
 */
internal enum class AgentWorkCollapseTiming(val id: String) {
    COMPLETION("completion"),
    NEXT_INPUT("next-input"),
    ;

    companion object {
        /** 默认结束即收：保持既有行为，切换开关不动现有观感。 */
        val DEFAULT: AgentWorkCollapseTiming = COMPLETION

        /** 未知或空值一律回退默认：手改配置或旧版本写入不能让折叠行为失配。 */
        fun fromId(raw: String?): AgentWorkCollapseTiming {
            val id = raw?.trim().orEmpty()
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * 自动模式下，一块工作过程要不要展开。
 *
 * 抽成纯函数是为了能单测：界面里的判断掺了「用户手点过没有」「搜索命中」两件事，
 * 而「结束之后还继续展开到下一句话」只由这两个输入决定，真机很难逐帧验证。
 */
internal object AgentWorkCollapsePolicy {
    /**
     * @param active 这块工作过程是不是本轮仍在进行的那块（流式收尾前的实时形态）。
     * @param hasUserMessageAfter 这块之后是否已经出现用户消息（即用户已经开始下一轮）。
     */
    fun autoExpand(
        timing: AgentWorkCollapseTiming,
        active: Boolean,
        hasUserMessageAfter: Boolean,
    ): Boolean = when {
        // 正在跑的这一块永远展开：工作步骤是「现在在做什么」的唯一出口。
        active -> true
        timing == AgentWorkCollapseTiming.NEXT_INPUT -> !hasUserMessageAfter
        else -> false
    }
}
