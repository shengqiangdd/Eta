package io.github.mangi.eta.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * 执行中进度的轮次登记表。
 *
 * 存在的理由（一个真实缺陷的回归）：`acp_delegate` 这类长时工具把整个外部代理回合
 * 包在一次调用里，进度从 ACP 的 `eta-acp-*` HandlerThread 持续回流。
 * 而"第几轮"只存在于编排线程。原先用 `ThreadLocal<Int>` 传轮次，
 * 结果读线程的 ThreadLocal 恒为初始值 0，`round <= 0` 的丢弃分支
 * **命中全部进度事件** —— UI、浮层、checkpoint 全都接好了，却一条都收不到，
 * 界面上只剩一张静止的转圈卡片。
 *
 * 所以改成按 toolCallId 维度的映射：事件自带 toolCallId，与线程无关，
 * 无论进度从哪个线程来都能查到正确的轮次。
 *
 * 线程安全：登记/移除来自编排线程，查询来自 ACP 读线程，用 ConcurrentHashMap 保证可见性。
 */
internal class ToolProgressRoundRegistry {
    private val rounds = ConcurrentHashMap<String, Int>()

    /** 工具开始执行前登记。`round` 必须 >= 1（与 [AgentEvent.ToolProgress.round] 同口径）。 */
    fun enter(round: Int, toolCallId: String) {
        if (round <= 0 || toolCallId.isEmpty()) return
        rounds[toolCallId] = round
    }

    /**
     * 工具执行结束后注销。
     *
     * 必须在 `finally` 里调用：取消是异常路径，漏掉注销会让迟到增量挂到已结束的工具卡片上，
     * 制造"工具复活"的假象；泄漏的表项也会一直留在 map 里。
     */
    fun exit(toolCallId: String) {
        if (toolCallId.isEmpty()) return
        rounds.remove(toolCallId)
    }

    /**
     * 查询某次工具调用的轮次；查不到（已结束、或登记前就来报）返回 0。
     *
     * 返回 0 而不是猜一个轮次：进度事件靠 `round + toolCallId` 定位界面上的卡片，
     * 猜错会把这条进度写到别人的卡片上，比丢一条进度更糟。
     */
    fun roundOf(toolCallId: String): Int = rounds[toolCallId] ?: 0

    /** 当前登记中的调用数；只用于测试与诊断。 */
    fun size(): Int = rounds.size
}
