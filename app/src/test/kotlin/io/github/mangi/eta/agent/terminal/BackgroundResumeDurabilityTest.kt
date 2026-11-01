package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 续跑状态的**写入可靠性**与**作用域互斥**（第十七批）。
 *
 * 两件事都只在「用户看不见的地方」出错：
 * - `persist()` 原来把 `saveState` 的 Boolean 丢掉。写盘失败（磁盘满、目录不可写、
 *   tmp 换名失败）时内存里一切正常，重启后却退回旧值 —— 水位线回退会重放任务，
 *   链长回退会多续几次。**花的是用户的额度，且没有任何日志。**
 * - 同一作用域有两个写者：watcher 的 `runOnce`（在它自己的 `running` 锁里）和用户发消息时的
 *   `resetChain()`（另起协程）。两者都可能读到旧的 `seenUntil` 再写盘，后写者把新值覆盖回去。
 */
class BackgroundResumeDurabilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        BackgroundResumeMemory.reset()
        BackgroundResumeMemory.onPersistFailed = null
    }

    @After
    fun tearDown() {
        BackgroundResumeMemory.reset()
        BackgroundResumeMemory.onPersistFailed = null
    }

    private fun store(): BackgroundResumeStore = BackgroundResumeStore(temporaryFolder.newFolder("resume"))

    private fun task(id: String, completedAt: Long?) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = "sleep 1",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = 100L,
        completedAt = completedAt,
        sessionScope = "conv-a",
    )

    /**
     * 写盘失败必须上报 —— 否则「续过什么 / 链走到第几次」在重启后静默丢失。
     *
     * `/proc` 下建不了目录：`saveState` 会走「目录建不出来」分支返回 false。
     */
    @Test
    fun failedPersistIsReported() = runBlocking {
        val reported = mutableListOf<String>()
        BackgroundResumeMemory.onPersistFailed = { scopeId -> reported += scopeId }
        val scope = BackgroundResumeMemory.scopeFor(
            BackgroundResumeStore(File("/proc/eta-not-writable")),
            "conv-a",
        )
        scope.markSeenUntil(100L)
        assertFalse("不可写目录下必须返回失败", scope.persist())
        assertEquals(listOf("conv-a"), reported)
    }

    /** 能写成功时不上报（别把正常路径变成噪音）。 */
    @Test
    fun successfulPersistIsSilent() = runBlocking {
        val reported = mutableListOf<String>()
        BackgroundResumeMemory.onPersistFailed = { scopeId -> reported += scopeId }
        val scope = BackgroundResumeMemory.scopeFor(store(), "conv-a")
        scope.markSeenUntil(100L)
        assertTrue(scope.persist())
        assertTrue(reported.isEmpty())
    }

    /**
     * 判定到分发之间，同一作用域必须被锁住：`resetChain()` 只有拿到同一把锁才不会插进
     * 「读了内存 → 写盘」这中间，把旧的水位线写回去。
     */
    @Test
    fun scopeIsLockedWhileDispatching() = runBlocking {
        val resumeStore = store()
        val scope = BackgroundResumeMemory.scopeFor(resumeStore, "conv-a")
        scope.markSeenUntil(500L)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            BackgroundAutoResumeWatcher.runOnce(
                records = listOf(task("job_a", 600L)),
                scopeFor = { scope },
                dispatch = { _, _ ->
                    entered.complete(Unit)
                    gate.await()
                    true
                },
            )
        }
        entered.await()
        assertTrue("分发期间必须持有该作用域的互斥锁", BackgroundResumeMemory.mutexFor("conv-a").isLocked)
        gate.complete(Unit)
        job.join()
        assertFalse("分发结束后必须释放", BackgroundResumeMemory.mutexFor("conv-a").isLocked)
    }

    /**
     * 判定之后、真正发出之前被记成「续过」（用户手动点了「继续」），就不能再发第二条。
     *
     * 与第十三批的 `isCurrent` 是同一类问题：判定与执行之间状态会变。
     */
    @Test
    fun skipsDispatchWhenTaskWasResumedAfterPlanning() = runBlocking {
        val resumeStore = store()
        val scope = BackgroundResumeMemory.scopeFor(resumeStore, "conv-a")
        scope.markSeenUntil(500L)
        var dispatched = 0
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = { scope },
            dispatch = { _, _ ->
                dispatched += 1
                true
            },
            onPlan = { _, plan ->
                if (plan is AutomationTick.Dispatch) scope.markResumed(plan.task.id)
            },
        )
        assertEquals(0, sent)
        assertEquals(0, dispatched)
    }
}
