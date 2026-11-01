package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.BackgroundResumeState
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import java.io.File
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
 * 进程级自动续跑 watcher 的编排逻辑。
 *
 * 这里守的是「没人看着的时候也不会乱发」：归属不对不发、没结束不发、水位线之前的不发、
 * 续过的与链满的不发，**发不出去就不占预算也不推进水位线**，以及重启后
 * 「刚结束还没处理」的任务仍然会被续（旧实现用进程启动时刻当水位线，这类任务会永久丢失）。
 *
 * 真机路径（真的起一轮 run、真的写会话）本机测不了，只能在设备上看。
 */
class BackgroundAutoResumeWatcherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storeRoot: File

    @Before
    fun setUp() {
        BackgroundResumeMemory.reset()
        storeRoot = temporaryFolder.newFolder("resume")
    }

    @After
    fun tearDown() {
        BackgroundResumeMemory.reset()
    }

    private fun task(
        id: String,
        completedAt: Long?,
        sessionScope: String? = "conv-a",
        startedAt: Long = 100L,
    ) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = "sleep 1",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = startedAt,
        completedAt = completedAt,
        sessionScope = sessionScope,
    )

    private fun scopeFor(scopeId: String): BackgroundResumeScope =
        BackgroundResumeMemory.scopeFor(BackgroundResumeStore(storeRoot), scopeId)

    private fun store() = BackgroundResumeStore(storeRoot)

    @Test
    fun neverDispatchesWithoutOwner() = runBlocking {
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L, sessionScope = null)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertEquals(0, scopeFor("conv-a").autoResumed)
    }

    /**
     * 第一次见到这个作用域：只立水位线，不补发历史任务；水位线要落盘。
     *
     * 升级上来的用户，记录里可能已经躺着十几条几个月前结束的任务 —— 一条都不能当新事件。
     */
    @Test
    fun firstScanInitializesWatermarkAndPersistsIt() = runBlocking {
        var dispatched = false
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_old", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                dispatched = true
                true
            },
            nowMs = 5_000L,
        )
        assertEquals(0, sent)
        assertFalse(dispatched)
        val scope = scopeFor("conv-a")
        assertEquals(5_000L, scope.seenUntil)
        assertEquals(5_000L, store().loadState("conv-a").seenUntil)
    }

    @Test
    fun runningTaskIsNotDispatched() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", null)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    @Test
    fun taskFinishedBeforeWatermarkIsIgnored() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 500L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    @Test
    fun finishedTaskOfThisConversationIsDispatchedAndRecorded() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        var dispatched: Pair<String, String>? = null
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { scopeId, target ->
                dispatched = scopeId to target.id
                true
            },
        )
        assertEquals(1, sent)
        assertEquals("conv-a" to "job_a", dispatched)
        val scope = scopeFor("conv-a")
        assertTrue("job_a" in scope.resumed)
        assertEquals(1, scope.autoResumed)
        // 发完就推进水位线，同一条不会被下一次兜底扫描再选中。
        assertEquals(600L, scope.seenUntil)
    }

    /** 分发失败（会话正在跑 / 会话不存在）不能记账，否则这一轮额度被白白吃掉，事件也丢了。 */
    @Test
    fun failedDispatchDoesNotConsumeBudgetOrAdvanceWatermark() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> false },
        )
        assertEquals(0, sent)
        val scope = scopeFor("conv-a")
        assertFalse("job_a" in scope.resumed)
        assertEquals(0, scope.autoResumed)
        assertEquals(500L, scope.seenUntil)
    }

    @Test
    fun budgetExhaustedStopsForThatConversationOnly() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        scopeFor("conv-a").markAutoResumed("job_old")
        scopeFor("conv-b").markSeenUntil(500L)
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(
                task("job_a1", 600L, sessionScope = "conv-a"),
                task("job_a2", 700L, sessionScope = "conv-a"),
                task("job_b1", 650L, sessionScope = "conv-b"),
            ),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        // conv-a 已经用了 1 次、上限 2：还能发一条；conv-b 未被 A 的用量影响，也发一条。
        assertEquals(2, sent)
        assertEquals(AutoResumeMaxPerChain, scopeFor("conv-a").autoResumed)
        assertEquals(1, scopeFor("conv-b").autoResumed)
    }

    /** 链满时把这一批明确放弃：水位线推到最晚一条，避免每 30 秒重算同一批。 */
    @Test
    fun chainExhaustedDiscardsPendingEvents() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        scopeFor("conv-a").markAutoResumed("job_old")
        scopeFor("conv-a").markAutoResumed("job_older")
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L), task("job_b", 700L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertEquals(700L, scopeFor("conv-a").seenUntil)
    }

    @Test
    fun handledTaskIsNotDispatchedAgain() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        scopeFor("conv-a").markResumed("job_a")
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    /** 一个 tick 里一个会话最多发一条：第二条要等这一轮真正跑完。 */
    @Test
    fun atMostOneTaskPerConversationPerScan() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        var count = 0
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a1", 600L), task("job_a2", 700L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                count += 1
                true
            },
        )
        assertEquals(1, sent)
        assertEquals(1, count)
    }

    /**
     * 重启后：磁盘上记着「这条续过」，进程内表是空的。
     * 扫描时必须先把磁盘记忆读进来再判定，否则会把同一批任务再续一遍。
     */
    @Test
    fun diskMemoryPreventsResumeAfterRestart() = runBlocking {
        store().saveState(
            "conv-a",
            BackgroundResumeState(resumed = listOf("job_a"), seenUntil = 500L),
        )
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertTrue(scopeFor("conv-a").isLoaded)
        assertEquals(0, scopeFor("conv-a").autoResumed)
    }

    /**
     * 重启后链长不归零：否则「杀掉 App 再打开」就是绕过上限的最短路径。
     */
    @Test
    fun restartKeepsChainBudget() = runBlocking {
        store().saveState(
            "conv-a",
            BackgroundResumeState(chain = AutoResumeMaxPerChain, seenUntil = 500L),
        )
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertEquals(AutoResumeMaxPerChain, scopeFor("conv-a").autoResumed)
        assertEquals(600L, scopeFor("conv-a").seenUntil)
    }

    /**
     * 重启后，水位线之后结束、还没处理的任务仍然会被续。
     *
     * 旧实现拿「进程启动时刻」当水位线：这条 600 结束、重启发生在 10_000 的任务永远不会被续。
     */
    @Test
    fun restartResumesTaskFinishedAfterPersistedWatermark() = runBlocking {
        store().saveState("conv-a", BackgroundResumeState(seenUntil = 500L))
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
            nowMs = 10_000L,
        )
        assertEquals(1, sent)
        assertEquals(1, scopeFor("conv-a").autoResumed)
    }

    @Test
    fun emptyRecordsDoNothing() = runBlocking {
        var called = false
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = emptyList(),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                called = true
                true
            },
        )
        assertEquals(0, sent)
        assertFalse(called)
    }
}
