package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController(
    /**
     * 父 run 的控制权。子代理、嵌套编排这类「run 里的 run」必须挂到父级：
     * 用户暂停父任务时子任务也要停在同一检查点，取消时更不能让子任务继续跑。
     * 只向下传导暂停/取消，不反向传导——子任务结束不等于父任务结束。
     */
    private val parent: AgentRunController? = null,
) {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()

    /** 重试退避等待：模型配置一变就唤醒，好让「还没发出」的重试立刻改用新配置。 */
    private val retryCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<String>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    /**
     * 运行中热切换的模型配置（模型 / 思考等级 / provider）。
     *
     * 为什么放在 run 里，而不是重发一次请求：请求只在开跑时传一次，中途换模型如果靠重发，
     * 等价于「停止再重来」——已经完成的工具结果白跑，用户还得重新排队。这里改成
     * 「下一个模型回合读最新值」：正在飞行的那次请求不打断（取消网络会丢掉已生成的 token）。
     */
    @Volatile
    private var modelConfigOverride: AgentModelClient.ModelConfig? = null

    /**
     * 配置代数：每次热切换 +1。
     *
     * 只用来回答「等待期间换过模型没有」——不能靠比较配置对象，因为用户可能切走又切回同一份
     * （那也该立刻重试，否则退避期间的新选择会被白等掉）。
     */
    @Volatile
    private var modelConfigGeneration = 0L

    val configGeneration: Long
        get() = modelConfigGeneration

    /** 当前生效的配置：有覆盖就用覆盖，否则用启动时那份。每轮模型请求前取一次。 */
    fun effectiveConfig(base: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig =
        modelConfigOverride ?: base

    /** 运行中热切换模型配置；run 已取消时返回 false（不能让它看起来切成功了）。 */
    fun applyModelConfig(config: AgentModelClient.ModelConfig): Boolean = lock.withLock {
        if (cancelled) {
            false
        } else {
            modelConfigOverride = config
            modelConfigGeneration += 1
            // 正在退避的重试此刻还没发出任何请求：唤醒它，用新配置立刻重试，
            // 而不是把剩下的退避等完再换模型（用户主动切换就是为了不等）。
            retryCondition.signalAll()
            true
        }
    }

    /** 当前是否处于暂停（客户端据此显示「继续」而不是「暂停」）。 */
    val isPaused: Boolean
        get() = paused || parent?.isPaused == true

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
            retryCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(prompt)
        }
        return true
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringMessage(): String? =
        lock.withLock { steeringMessages.pollFirst() }

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        // 自己的取消优先：被插话中断的子代理不能因为父级正在暂停而继续等下去。
        if (cancelled) throw AgentRunCancelledException()
        // 再看父级：父 run 已取消时连子级的锁都不用拿；暂停也要在父级检查点一起等待。
        parent?.throwIfCancelled()
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    /**
     * 等待重试退避。
     *
     * 可被三种情况提前结束：取消（抛 [AgentRunCancelledException]）、
     * 以及**模型配置热切换**（返回 true，调用方应立刻用新配置重试）。
     * 已经在飞行中的请求不会被这里打断 —— 取消网络会丢掉已生成的 token。
     */
    fun awaitRetryDelay(delayMs: Long): Boolean {
        throwIfCancelled()
        val generation = modelConfigGeneration
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs.coerceAtLeast(0L))
        lock.withLock {
            while (!cancelled && modelConfigGeneration == generation) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                try {
                    retryCondition.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AgentRunCancelledException()
                }
            }
        }
        throwIfCancelled()
        return modelConfigGeneration != generation
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
