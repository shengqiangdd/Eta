package io.github.mangi.eta.agent.terminal

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
 * 这里守的是「没人看着的时候也不会乱发」：归属不对不发、没结束不发、历史任务不发、
 * 续过的与超预算的不发，以及**发不出去就不占预算**（否则一次失败会把这一轮的额度耗光）。
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

    @Test
    fun neverDispatchesWithoutOwner() = runBlocking {
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L, sessionScope = null)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertEquals(0, scopeFor("conv-a").autoResumed)
    }

    @Test
    fun runningTaskIsNotDispatched() = runBlocking {
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", null)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    @Test
    fun taskFinishedBeforeWatcherStartedIsIgnored() = runBlocking {
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 500L)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    @Test
    fun finishedTaskOfThisConversationIsDispatchedAndRecorded() = runBlocking {
        var dispatched: Pair<String, String>? = null
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            finishedAfterMs = 500L,
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
    }

    /** 分发失败（会话正在跑 / 会话不存在）不能记账，否则这一轮额度被白白吃掉。 */
    @Test
    fun failedDispatchDoesNotConsumeBudget() = runBlocking {
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> false },
        )
        assertEquals(0, sent)
        val scope = scopeFor("conv-a")
        assertFalse("job_a" in scope.resumed)
        assertEquals(0, scope.autoResumed)
    }

    @Test
    fun budgetExhaustedStopsForThatConversationOnly() = runBlocking {
        scopeFor("conv-a").markAutoResumed("job_old")
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(
                task("job_a1", 600L, sessionScope = "conv-a"),
                task("job_a2", 700L, sessionScope = "conv-a"),
                task("job_b1", 650L, sessionScope = "conv-b"),
            ),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        // conv-a 已经用了 1 次、上限 2：还能发一条；conv-b 未被 A 的用量影响，也发一条。
        assertEquals(2, sent)
        assertEquals(AutoResumeMaxPerSession, scopeFor("conv-a").autoResumed)
        assertEquals(1, scopeFor("conv-b").autoResumed)
    }

    @Test
    fun handledTaskIsNotDispatchedAgain() = runBlocking {
        scopeFor("conv-a").markResumed("job_a")
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
    }

    /** 一个 tick 里一个会话最多发一条：第二条要等这一轮真正跑完。 */
    @Test
    fun atMostOneTaskPerConversationPerScan() = runBlocking {
        var count = 0
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a1", 600L), task("job_a2", 700L)),
            finishedAfterMs = 500L,
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
        BackgroundResumeStore(storeRoot).save("conv-a", listOf("job_a"))
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = listOf(task("job_a", 600L)),
            finishedAfterMs = 500L,
            scopeFor = ::scopeFor,
            dispatch = { _, _ -> true },
        )
        assertEquals(0, sent)
        assertTrue(scopeFor("conv-a").isLoaded)
        assertEquals(0, scopeFor("conv-a").autoResumed)
    }

    @Test
    fun emptyRecordsDoNothing() = runBlocking {
        var called = false
        val sent = BackgroundAutoResumeWatcher.runOnce(
            records = emptyList(),
            finishedAfterMs = 0L,
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
