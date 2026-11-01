package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.BackgroundResumeState
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // 停在「前一毫秒」，好让同一毫秒结束的其余任务仍能被看到（见同毫秒用例）。
        assertEquals(599L, scope.seenUntil)
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

    /**
     * 系统时间被回拨：水位线比现在晚一小时。
     *
     * 不重建的话，回拨之后结束的任务都落在水位线之前，自动续跑会静默失效。
     * 重建时把「比现在还晚」的旧记录吸收成已处理，否则它们会被当成未来事件反复重放
     * （每次 tick 都选它，发两次、链满，水位线再被推到更远的未来 —— 再次卡死）。
     */
    @Test
    fun clockRollbackRebuildsTimelineAndDoesNotReplayFutureRecords() = runBlocking {
        val now = 1_700_000_000_000L
        store().saveState("conv-a", BackgroundResumeState(seenUntil = now + 3_600_000L))
        var dispatched = 0
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(
                task("job_future", now + 3_000_000L),
                task("job_after_rollback", now - 1_000L),
            ),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                dispatched += 1
                true
            },
            nowMs = now,
        )
        assertEquals(0, sent)
        assertEquals(0, dispatched)
        val scope = scopeFor("conv-a")
        assertEquals(now, scope.seenUntil)
        assertTrue("job_future" in scope.resumed)
        assertFalse("job_after_rollback" in scope.resumed)
        // 重建也要落盘，否则重启后又是旧水位线，等于没修。
        assertEquals(now, store().loadState("conv-a").seenUntil)
    }

    /**
     * Activity 重建会先后 install / uninstall 分发器。tick 读到记录后、真正发之前
     * 手里那个实例可能已经销毁：这一轮不能再发，否则 run 发到一个没人看的实例，
     * 返回成功还会把事件记成「已续」（用户既看不到那一轮，这条任务也不会再被续）。
     */
    @Test
    fun dispatchIsSkippedWhenDispatcherWasReplaced() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        var dispatched = false
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                dispatched = true
                true
            },
            isCurrent = { false },
        )
        assertEquals(0, sent)
        assertFalse(dispatched)
        val scope = scopeFor("conv-a")
        assertFalse("job_a" in scope.resumed)
        assertEquals(0, scope.autoResumed)
        assertEquals(500L, scope.seenUntil)
    }

    /**
     * 两条任务在同一毫秒结束（模型一轮派几个短命令时很常见）。
     *
     * 旧实现只把水位线推进到 `completed_at`，等于把同一毫秒的其余任务一起压住：
     * 第一条发出去之后，剩下的永远不满足「严格晚于水位线」，被静默跳过。
     */
    @Test
    fun tasksFinishedInSameMillisecondAreDispatchedOneAfterAnother() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        val records = listOf(task("job_a", 600L), task("job_b", 600L))
        var sent = 0
        val first = BackgroundAutoResumeWatcher.runOnce(
            records = records,
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                sent += 1
                true
            },
        )
        val second = BackgroundAutoResumeWatcher.runOnce(
            records = records,
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                sent += 1
                true
            },
        )
        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(2, sent)
    }

    /** 反过来的保护：链满时明确放弃的同一毫秒任务，不能因为水位线微调而反复变成候选。 */
    @Test
    fun discardedSameMillisecondTasksDoNotComeBack() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        repeat(AutoResumeMaxPerChain) { index -> scopeFor("conv-a").markAutoResumed("job_old_$index") }
        val records = listOf(task("job_a", 600L), task("job_b", 600L))
        var sent = 0
        val first = BackgroundAutoResumeWatcher.runOnce(
            records = records,
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                sent += 1
                true
            },
        )
        val second = BackgroundAutoResumeWatcher.runOnce(
            records = records,
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                sent += 1
                true
            },
        )
        assertEquals(0, first)
        assertEquals(0, second)
        assertEquals(0, sent)
        assertEquals(600L, scopeFor("conv-a").seenUntil)
    }

    /** 判定回调：日志就挂在这上面，必须能看到决定本身（而不是只看「发了几个」）。 */
    @Test
    fun planObserverReportsEveryDecision() = runBlocking {
        scopeFor("conv-a").markSeenUntil(500L)
        val seen = mutableListOf<String>()
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
            onPlan = { scopeId, plan -> seen += plan.logLine(scopeId) },
        )
        assertEquals(1, sent)
        assertEquals(listOf("dispatch scope=conv-a task=job_a"), seen)
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

    /**
     * 手动「继续」只记 id、不推水位线（第二十二批留下的边界）。id 被 64 条上限挤出后，
     * 它会重新变成候选再发一遍 —— 这里要求水位线自己记住「这条处理过」。
     */
    @Test
    fun manualResumeIsCoveredByWatermarkBeforeTheIdLimitCanDropIt() = runBlocking {
        val scope = scopeFor("conv-a")
        scope.markSeenUntil(500L)
        // 用户手动点了「继续」：只记账，不动水位线（动了会跳过更早的未处理任务）。
        scope.markResumed("job_a")

        var dispatched = 0
        val first = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                dispatched += 1
                true
            },
        )
        assertEquals(0, first)
        assertEquals(0, dispatched)
        // 已经续过的任务必须被水位线覆盖，否则它只是「暂时被 id 集合挡住」。
        assertEquals(600L, scope.seenUntil)

        // 模拟 64 条上限把这条 id 挤掉：水位线是唯一还记得「它处理过」的东西。
        scope.resumed.remove("job_a")
        val second = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            scopeFor = ::scopeFor,
            dispatch = { _, _ ->
                dispatched += 1
                true
            },
        )
        assertEquals(0, second)
        assertEquals(0, dispatched)
    }

    @Test
    fun watermarkStopsAtTheFirstUnhandledTask() {
        val a = task("job_a", 600L)
        val b = task("job_b", 700L)
        val c = task("job_c", 800L)
        // 中间那条没处理过：水位线只能停到它前面，不能越过去（越过去就是静默丢任务）。
        assertEquals(
            600L,
            advanceSeenUntilOverHandled(listOf(a, b, c), "conv-a", setOf("job_a", "job_c"), 500L),
        )
        // 连续都处理过 → 推到最后一条自己的时刻（它已经处理完，不能留下当候选）。
        assertEquals(
            800L,
            advanceSeenUntilOverHandled(listOf(a, b, c), "conv-a", setOf("job_a", "job_b", "job_c"), 500L),
        )
        // 水位线已经更晚：只前进，不后退。
        assertNull(advanceSeenUntilOverHandled(listOf(a, b, c), "conv-a", setOf("job_a"), 5_000L))
        // 别的会话的任务不参与本会话的水位线。
        assertNull(
            advanceSeenUntilOverHandled(
                listOf(task("job_x", 600L, sessionScope = "conv-b")),
                "conv-a",
                setOf("job_x"),
                500L,
            ),
        )
        // 同一毫秒的两条只处理了一条：整组不能越过，否则另一条永远没机会。
        assertNull(
            advanceSeenUntilOverHandled(
                listOf(task("job_d", 900L), task("job_e", 900L)),
                "conv-a",
                setOf("job_d"),
                800L,
            ),
        )
    }
}
