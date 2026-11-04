package io.github.mangi.eta.agent.subagent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 子代理「卡住」的自治收尾。
 *
 * 为什么需要它：`subagent` 是普通工具调用，父 Agent 在它返回前不会进入下一轮。界面早就会显示
 * 「已 N 秒没有新进展 · 可能卡住」，但那只是**提示** —— 真正卡死的子代理（上游不再吐字节、
 * 工具在等一个不会来的结果）会一直挂着，父 loop 跟着无限期阻塞，用户只能手动停止整个 run。
 *
 * 这里把「提示」升级成「收尾」：**按最后一次真实事件**判断，没有进展超过阈值就取消子控制器，
 * 让执行器带着部分结果返回。父循环照常继续，并能在结果里看到 `SUBAGENT_STALLED`。
 *
 * 两条独立的判据：
 * - **无进展**（[DEFAULT_IDLE_TIMEOUT_MS]）：默认 10 分钟。这一条必须比模型请求的读取超时
 *   （`AgentHttpClient.MODEL_READ_TIMEOUT_MS` = 5 分钟）宽 —— 在飞请求结束时一定会产生事件
 *   （回答、重试、失败），5 分钟内没有任何事件的执行路径不存在，所以 10 分钟不会误杀
 *   正常的长思考或长工具调用。
 * - **总时长**（[DEFAULT_MAX_RUNTIME_MS]）：默认 45 分钟。一个子代理跑过这个时长，即使一直在动，
 *   也不该继续占着父 run：用户要的是结论，不是无限深挖。
 *
 * 与 [SubagentSteeringWatch] 的关系：两个看门狗都会取消子控制器，但语义不同 ——
 * 插话是用户主动改变方向（最高优先级），停滞是执行侧的自我放弃。执行器优先按插话解释结果。
 */
internal class SubagentStallWatch(
    private val cancelChild: () -> Unit,
    /** 最后一次子代理事件的时间；由 `StepTracker` 维护，读的时候会加锁。 */
    private val lastActivityAtMs: () -> Long,
    private val startedAtMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val maxRuntimeMs: Long = DEFAULT_MAX_RUNTIME_MS,
    pollMillis: Long = DEFAULT_POLL_MS,
) : AutoCloseable {
    enum class Reason {
        /** 超过阈值没有任何新事件。 */
        NO_PROGRESS,

        /** 总运行时长超限，即使一直在动。 */
        MAX_RUNTIME,
        ;

        val wire: String
            get() = when (this) {
                NO_PROGRESS -> "no_progress"
                MAX_RUNTIME -> "max_runtime"
            }
    }

    private val stopped = AtomicBoolean(false)
    private val triggered = AtomicReference<Reason?>(null)

    private val worker = thread(name = "eta-subagent-stall", isDaemon = true) {
        while (!stopped.get()) {
            val reason = evaluate(
                nowMs = now(),
                lastActivityMs = lastActivityAtMs(),
                startedAtMs = startedAtMs,
                idleTimeoutMs = idleTimeoutMs,
                maxRuntimeMs = maxRuntimeMs,
            )
            if (reason != null) {
                triggered.compareAndSet(null, reason)
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

    /** 触发原因；null 表示子代理在阈值内正常结束。 */
    val reason: Reason?
        get() = triggered.get()

    override fun close() {
        stopped.set(true)
        worker.interrupt()
    }

    companion object {
        /**
         * 无进展上限。取 10 分钟而不是 1~2 分钟：模型请求的读取超时是 5 分钟，
         * 任何一条活着的执行路径都会在那之前产生事件（正文、重试、错误）。
         */
        const val DEFAULT_IDLE_TIMEOUT_MS = 600_000L

        /** 总时长上限；超过它即使还在动也收尾（结果照常保留）。 */
        const val DEFAULT_MAX_RUNTIME_MS = 45 * 60_000L

        private const val DEFAULT_POLL_MS = 2_000L

        /**
         * 纯判定，供单测与看门狗共用：两个阈值都 <= 0 表示对应判据关闭。
         *
         * 总时长先判：它更「硬」，不会因为过程中恰好有事件而被推迟。
         */
        fun evaluate(
            nowMs: Long,
            lastActivityMs: Long,
            startedAtMs: Long,
            idleTimeoutMs: Long,
            maxRuntimeMs: Long,
        ): Reason? {
            if (maxRuntimeMs > 0 && nowMs - startedAtMs >= maxRuntimeMs) return Reason.MAX_RUNTIME
            if (idleTimeoutMs > 0 && nowMs - lastActivityMs >= idleTimeoutMs) return Reason.NO_PROGRESS
            return null
        }
    }
}
