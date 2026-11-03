package io.github.mangi.eta.agent.subagent

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 子代理运行期间盯着父 run 的插话队列。
 *
 * 为什么需要它：`subagent` 是普通工具调用，父 Agent 在工具返回前不会进入下一轮，
 * 而 steering 只在轮次边界被消费。一个跑十几分钟的子代理会把用户的「插话」压到结束，
 * 界面看起来就是“发什么都没反应”。
 *
 * 这里用独立守护线程轮询父控制器：一旦发现新插话，立刻取消子控制器，让子代理带着
 * 部分结果尽快返回；父循环下一轮再正常消费这条插话。**不在这里取走消息**——取走会让
 * 父循环再也看不到它。
 */
internal class SubagentSteeringWatch(
    private val hasPendingSteering: () -> Boolean,
    private val cancelChild: () -> Unit,
    private val pollMillis: Long = 200L,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val steered = AtomicBoolean(false)
    private val worker = thread(name = "eta-subagent-steering", isDaemon = true) {
        while (!stopped.get()) {
            if (hasPendingSteering()) {
                steered.set(true)
                runCatching { cancelChild() }
                return@thread
            }
            try {
                Thread.sleep(pollMillis)
            } catch (_: InterruptedException) {
                return@thread
            }
        }
    }

    /** 子代理是否因为用户插话而被主动中断。 */
    val wasSteered: Boolean
        get() = steered.get()

    override fun close() {
        stopped.set(true)
        worker.interrupt()
    }
}
