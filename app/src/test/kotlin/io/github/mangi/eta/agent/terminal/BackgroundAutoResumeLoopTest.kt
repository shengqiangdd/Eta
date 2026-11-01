package io.github.mangi.eta.agent.terminal

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动续跑**触发循环本身**的存活不变量（第十五批）。
 *
 * 为什么单独守这一层：`BackgroundAutoResumeWatcher.start()` 是**进程级、只启动一次**的
 * (`AtomicBoolean.compareAndSet(false, true)`)，而它起的两个协程是自动续跑**唯一**的触发点
 * （第十二批把卡片里那份删掉了）。协程一旦因异常终止：
 * - 不会有人重启它 —— 再调一次 `start()` 直接返回；
 * - `SupervisorJob` 不让失败扩散，另一个协程也活着，于是没有任何「整体坏了」的迹象；
 * - 用户看到的只是「后台任务结束之后再也没接着跑」，不报错、不弹窗。
 *
 * 这正是 §122 与 `EtaApp` 的探活循环已经明确要避免的模式（那里每轮都 `runCatching`）。
 * 这里要守两条：
 * 1. 单轮 `tick` 失败不能终止循环 —— 下一轮照常；
 * 2. `CancellationException` 必须传播 —— 用 `runCatching` 包会把它一起吞掉，
 *    循环永远退不出去（而且 `isActive` 语义被破坏）。
 */
class BackgroundAutoResumeLoopTest {

    /** 只用来结束一个「本该无限」的循环，与缺陷无关。 */
    private class LoopFinished : RuntimeException("loop finished")

    /**
     * 兜底循环：第 1 轮 `tick` 抛异常，后面几轮仍要被调用。
     *
     * 不修的话异常直接从 `runFallbackLoop` 冒出去，第一次 `tick` 就结束 —— `ticks` 只有 1。
     */
    @Test
    fun fallbackLoopKeepsRunningAfterTickFailure() = runBlocking {
        var rounds = 0
        var ticks = 0
        val intervals = CopyOnWriteArrayList<Long>()
        val failures = CopyOnWriteArrayList<String>()
        try {
            BackgroundAutoResumeWatcher.runFallbackLoop(
                intervalMs = 5L,
                delayBefore = { interval ->
                    rounds += 1
                    if (rounds > 3) throw LoopFinished()
                    intervals += interval
                },
                tick = {
                    ticks += 1
                    error("tick exploded")
                },
                onFailure = { failures += it.message.orEmpty() },
            )
        } catch (_: LoopFinished) {
            // 只用来跳出无限循环。
        }
        assertEquals("每一轮都要真的跑到，不能因为上一轮失败就停下", 3, ticks)
        assertEquals("每次失败都要报上去，否则就是静默失效", 3, failures.size)
        assertEquals(listOf(5L, 5L, 5L), intervals.toList())
    }

    /**
     * 事件循环：第一次 `tick` 抛异常后，仍然要继续接收后续的变化信号。
     *
     * 不修的话 `collect` 所在的协程会被异常终止：之后的记录写入再也触发不了扫描，
     * 只剩 30 秒兜底（而兜底那条也在同一个修复里，若它也死了就彻底不动了）。
     */
    @Test
    fun changeLoopKeepsCollectingAfterTickFailure() = runBlocking {
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        val ticks = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) {
            BackgroundAutoResumeWatcher.runChangeLoop(
                changes = changes,
                tick = {
                    val index = ticks.size + 1
                    ticks += index
                    if (index == 1) error("first tick exploded")
                },
                onFailure = { failures += it.message.orEmpty() },
            )
        }
        try {
            withTimeout(10_000L) {
                while (changes.subscriptionCount.value == 0) delay(5L)
            }
            repeat(3) { index -> changes.emit(index.toLong()) }
            withTimeout(10_000L) {
                while (ticks.size < 3) delay(5L)
            }
        } finally {
            job.cancel()
        }
        assertEquals(listOf(1, 2, 3), ticks.toList())
        assertEquals(1, failures.size)
    }

    /**
     * 取消不能被当成「一次失败」吞掉。
     *
     * 用 `runCatching { tick() }` 实现这条兜底会顺手吞掉 `CancellationException`：
     * 协程已经被取消，循环却继续转 —— 与「异常不能终止循环」正好互为反面。
     */
    @Test
    fun fallbackLoopPropagatesCancellation() = runBlocking {
        var ticks = 0
        var failures = 0
        var stoppedByCancellation = false
        try {
            BackgroundAutoResumeWatcher.runFallbackLoop(
                intervalMs = 5L,
                delayBefore = { },
                tick = {
                    ticks += 1
                    throw CancellationException("stop")
                },
                onFailure = { failures += 1 },
            )
        } catch (_: CancellationException) {
            stoppedByCancellation = true
        }
        assertEquals(1, ticks)
        assertEquals("取消不是失败，不能走 onFailure", 0, failures)
        assertTrue("取消必须让循环停下来", stoppedByCancellation)
    }
}
