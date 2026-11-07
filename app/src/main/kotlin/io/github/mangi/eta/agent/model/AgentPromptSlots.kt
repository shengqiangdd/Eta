package io.github.mangi.eta.agent.model

/**
 * 动态系统段落的「空占位」。
 *
 * 为什么要有空占位：OpenAI 兼容端点（DeepSeek、OpenAI、大多数第三方中转）只做
 * **自动前缀缓存** —— 命中条件是请求前缀逐字节相同。一旦某个系统段从「不存在」变成
 * 「存在」，它之后的全部内容都要重算，那一轮的输入 token 全部按未命中计费。
 *
 * 真实现场：一个不建清单的会话跑了二十轮，模型第一次调用 `todo_write` 后，
 * `todoPlan` 从空串变成一段清单，于是之后每一轮都在为**同一段系统提示**重复付费。
 * 让每个动态段落无条件占位，前缀就稳定了；代价是空会话多几十个字符。
 *
 * 文案都刻意写得极短（一句），并且**不含任何会随轮变化的措辞** ——
 * 「当前没有」和「暂无」这类微小变化同样会让前缀失效。
 */
internal object AgentPromptSlots {
    const val EMPTY_MODE = "（本轮没有额外模式说明）"
    const val EMPTY_GOAL = "（当前没有进行中的目标）"
    const val EMPTY_TODO = "（当前没有待办清单）"
    const val EMPTY_TASK_LEDGER = "（当前没有任务台账）"
    const val EMPTY_BACKGROUND_TASKS = "（当前没有后台任务）"
    const val EMPTY_BACKGROUND_EVENTS = "（当前没有待告知的后台结束事件）"
}
