package io.github.mangi.eta.agent.runtime

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GuiControlStatus(val wireValue: String) {
    Waiting("waiting"),
    Acquired("acquired"),
    Released("released"),
    UserPaused("user_paused");

    companion object {
        fun fromWireValue(value: String): GuiControlStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 手机界面的操作权，**按屏**分发。
 *
 * 早先是全局单锁：一个 run 拿到锁后一直持有，直到 `release_gui` 或整个 run 结束；
 * 别的 run 只能在 `await()` 上无限期等待。多会话并行时，两块**不同的**虚拟屏也会被串成一条队 ——
 * 表现就是点一下卡几百秒，而两边其实动的是不同的屏。
 *
 * 现在用 scope 把屏分开：主屏是 [MainScope]，虚拟屏是 [displayScope]。
 * 同一块屏仍然互斥（两个 run 同时点同一块屏本来就会互相打架），不同屏互不阻塞。
 *
 * 等待也有上限：[waitTimeoutMs] 到点抛 [GuiWaitTimeoutException]，而不是无限期挂着。
 * 这同时给「A 持主屏等虚拟屏、B 持虚拟屏等主屏」这种跨屏互等留了一个终点。
 */
internal class GuiControlCoordinator(
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    data class State(
        /** 主屏的持有者；保留这个字段是因为界面用它显示「谁在操作手机」。 */
        val ownerRunId: String? = null,
        val userPaused: Boolean = false,
        /** 每个 scope 的持有者：`main` / `display:<id>`。 */
        val owners: Map<String, String> = emptyMap(),
    )

    /** 等另一块屏等过头了。工具层把它翻译成可读错误，而不是让整轮对话挂在那里。 */
    internal class GuiWaitTimeoutException(val scope: String, val timeoutMs: Long) :
        RuntimeException("等待手机操作权超时：$scope 在 ${timeoutMs}ms 内没有释放")

    companion object {
        /** 主屏的作用域。 */
        const val MainScope: String = "main"

        /** 虚拟屏的作用域；同一块屏的并发动作仍然互斥。 */
        fun displayScope(displayId: Int): String = "display:$displayId"

        /** 单个动作等待另一块屏的最长时间：动作本身通常 1~2 秒，25 秒足够分辨「慢」和「卡死」。 */
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 25_000L
    }

    /** 一次成功的取得：`generation` 给观察快照用，`fresh` 表示这回真的从队列里拿到了锁（重入时为 false）。 */
    private class Grant(val generation: Long, val fresh: Boolean)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val waiting = mutableMapOf<String, ArrayDeque<RunControl>>()
    private val owners = mutableMapOf<String, RunControl>()
    /**
     * 被「用户接管」的 run。按 run 记，不再用全局布尔 ——
     * 否则用户暂停会话 A，另一块虚拟屏上的会话 B 也会被一起冻住，
     * 表现同样是「点一下卡很久」，但原因跟排队完全不同。
     */
    private val pausedRuns = mutableMapOf<String, RunControl>()
    private var generation = 0L
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun openRun(
        runId: String,
        controller: AgentRunController,
        onStatusChanged: (GuiControlStatus) -> Unit = {},
    ): RunControl = RunControl(runId, controller, onStatusChanged).also { control ->
        control.bindCancellation(controller.register(control::close))
    }

    fun pauseForUser(runId: String): Boolean {
        val paused = lock.withLock {
            val current = owners.values.firstOrNull { it.runId == runId && !it.closed } ?: return false
            if (pausedRuns[runId] === current) return true
            pausedRuns[runId] = current
            generation++
            current.controller.pause()
            // 只看自己有没有在跑的动作：别的 run 正在忙，不该拖住「暂停已生效」的发布。
            if (current.activeActions == 0) {
                publishState()
                current
            } else {
                null
            }
        }
        paused?.notifyStatus(GuiControlStatus.UserPaused)
        return true
    }

    /**
     * 只放开 [runId] 自己：另一个 run 被用户接管时，不该被这里顺手解冻。
     */
    fun resumeForUser(runId: String): Boolean {
        val resumed = lock.withLock {
            val current = pausedRuns.remove(runId) ?: return false
            generation++
            current.controller.resume()
            publishState()
            changed.signalAll()
            current
        }
        resumed.notifyStatus(GuiControlStatus.Acquired)
        return true
    }

    private fun publishState() {
        mutableState.value = State(
            ownerRunId = owners[MainScope]?.runId,
            userPaused = pausedRuns.isNotEmpty(),
            owners = owners.mapValues { (_, control) -> control.runId },
        )
    }

    private fun removeFromQueueLocked(scope: String, control: RunControl) {
        val queue = waiting[scope] ?: return
        queue.remove(control)
        if (queue.isEmpty()) waiting.remove(scope)
    }

    private fun releaseScopeLocked(scope: String): GuiControlStatus? {
        val current = owners.remove(scope) ?: return null
        // 这一个 run 不再持有任何 scope 时，「我已经交还操作权」的标记才算走完。
        if (owners.values.none { it === current }) current.releaseRequested = false
        publishState()
        changed.signalAll()
        return GuiControlStatus.Released
    }

    internal inner class RunControl internal constructor(
        val runId: String,
        internal val controller: AgentRunController,
        private val onStatusChanged: (GuiControlStatus) -> Unit,
    ) : AutoCloseable {
        internal var closed = false
        internal var activeActions = 0
        internal var releaseRequested = false
        private val activeByScope = mutableMapOf<String, Int>()
        private var cancellationBinding: AgentRunController.ResourceBinding? = null

        internal fun bindCancellation(binding: AgentRunController.ResourceBinding) = lock.withLock {
            if (closed) binding.close() else cancellationBinding = binding
        }

        /**
         * 锁内试一次：拿到就返回，拿不到就把自己排进队列并返回 null。
         *
         * 队列为空（第一次进来）或队首就是自己，才算轮到自己 ——
         * 早先直接拿 `peekFirst()` 和 this 比，空队列时它是 null，永远比不中，
         * 表现就是每个动作都先「排队」25 秒。
         */
        private fun tryAcquireLocked(scope: String): Grant? {
            checkOpen()
            val currentOwner = owners[scope]
            val takenByUser = pausedRuns.containsKey(runId)
            if (currentOwner === this && !takenByUser) {
                activeActions++
                activeByScope[scope] = (activeByScope[scope] ?: 0) + 1
                return Grant(generation, fresh = false)
            }
            val head = waiting[scope]
            val atHead = head == null || head.isEmpty() || head.peekFirst() === this
            if (currentOwner == null && !takenByUser && atHead) {
                if (head != null) {
                    head.remove(this)
                    if (head.isEmpty()) waiting.remove(scope)
                }
                owners[scope] = this
                generation++
                publishState()
                activeActions++
                activeByScope[scope] = (activeByScope[scope] ?: 0) + 1
                return Grant(generation, fresh = true)
            }
            val queue = waiting.getOrPut(scope) { ArrayDeque() }
            if (!queue.contains(this)) queue.addLast(this)
            return null
        }

        /** 锁内等到拿到 [scope] 为止；超时或取消时把自己从队列里撤掉再抛。 */
        private fun awaitGrantLocked(scope: String, deadlineNanos: Long): Grant {
            var got = tryAcquireLocked(scope)
            while (got == null) {
                checkOpen()
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) {
                    removeFromQueueLocked(scope, this)
                    changed.signalAll()
                    throw GuiWaitTimeoutException(scope, waitTimeoutMs)
                }
                try {
                    changed.await(remainingMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    removeFromQueueLocked(scope, this)
                    changed.signalAll()
                    throw AgentRunCancelledException()
                }
                got = tryAcquireLocked(scope)
            }
            return got
        }

        /**
         * 在 [scope] 这块屏上执行一次动作。
         *
         * 已经在同一块屏上持有时直接进入（重入不排队）；否则排队，最多等 [waitTimeoutMs]。
         * 不同 scope 之间没有先后关系 —— 这正是「主屏被占着时虚拟屏还能动」的原因。
         *
         * 分成两段：先试着拿，拿不到**出锁**通知「排队中」，再进锁等。
         * 早先把登记和 await 塞在同一个 lock 块里，`await` 一直占着锁，
         * Waiting 状态根本发不出去 —— 单测等回调全超时，界面也看不到「等待中」。
         */
        fun <T> withAction(scope: String = MainScope, block: (generation: Long) -> T): T {
            controller.throwIfCancelled()
            val deadlineNanos = System.nanoTime() + waitTimeoutMs * 1_000_000L
            val immediate = lock.withLock { tryAcquireLocked(scope) }
            val grant = if (immediate != null) {
                immediate
            } else {
                notifyStatus(GuiControlStatus.Waiting)
                lock.withLock { awaitGrantLocked(scope, deadlineNanos) }
            }
            val actionGeneration = grant.generation
            val acquired = grant.fresh
            try {
                if (acquired) notifyStatus(GuiControlStatus.Acquired)
                if (controller.isCancelled) throw AgentRunCancelledException()
                return block(actionGeneration)
            } finally {
                val status = lock.withLock {
                    activeActions--
                    val left = (activeByScope[scope] ?: 1) - 1
                    if (left <= 0) activeByScope.remove(scope) else activeByScope[scope] = left
                    when {
                        left > 0 -> null
                        releaseRequested || closed -> releaseScopeLocked(scope)
                        pausedRuns[runId] === this -> {
                            publishState()
                            GuiControlStatus.UserPaused
                        }
                        else -> null
                    }
                }
                status?.let(::notifyStatus)
            }
        }

        fun release() {
            val statuses = lock.withLock {
                if (owners.values.any { it === this }) {
                    releaseRequested = true
                    generation++
                    val released = owners.filterValues { it === this }.keys.toList().mapNotNull { scope ->
                        if ((activeByScope[scope] ?: 0) == 0) releaseScopeLocked(scope) else null
                    }
                    if (owners.values.none { it === this }) releaseRequested = false
                    released
                } else {
                    // 还在排队：撤掉自己，别占着队首让后面的人等一个已经不想动的 run。
                    waiting.values.forEach { it.remove(this) }
                    changed.signalAll()
                    emptyList()
                }
            }
            statuses.forEach(::notifyStatus)
        }

        override fun close() {
            val statuses = lock.withLock {
                if (closed) return
                closed = true
                cancellationBinding?.close()
                cancellationBinding = null
                waiting.values.forEach { it.remove(this) }
                // 不清 pausedRuns：「用户接管」是用户对这个 run 的意志，
                // 它被取消/关闭后仍保持到显式 resume（既有契约，有单测守着）。
                changed.signalAll()
                generation++
                owners.filterValues { it === this }.keys.toList().mapNotNull { scope ->
                    if ((activeByScope[scope] ?: 0) == 0) releaseScopeLocked(scope) else null
                }
            }
            statuses.forEach(::notifyStatus)
        }

        private fun checkOpen() {
            if (closed || controller.isCancelled) throw AgentRunCancelledException()
        }

        internal fun notifyStatus(status: GuiControlStatus) = onStatusChanged(status)
    }
}
