package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskRecord
import io.github.mangi.eta.agent.model.AgentTaskStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTaskLedgerStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentTaskLedgerStore(temporaryFolder.root)

    private fun record(id: String, startedAt: Long = 1_000L) = AgentTaskRecord(
        id = id,
        kind = AgentTaskRecord.KIND_SUBAGENT,
        title = "任务 $id",
        status = AgentTaskStatus.RUNNING,
        ref = null,
        summary = null,
        startedAt = startedAt,
        endedAt = null,
    )

    @Test
    fun runningEntryLeftByAnotherProcessIsReportedAsStopped() {
        val before = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-a")
        assertTrue(before.recordStarted("conversation-1", record("sub-a")))

        // 模拟进程被系统回收后重启：新进程 token 不同，旧记录里的 RUNNING 不可能还在跑。
        val restarted = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-b")
        val entry = restarted.load("conversation-1").entries.single()
        assertEquals(AgentTaskStatus.CANCELLED, entry.status)
        assertEquals(AgentTaskLedger.ORPHAN_NOTE, entry.summary)
        assertNull("结束时间未知就不编一个", entry.endedAt)
    }

    @Test
    fun sameProcessKeepsRunningStatus() {
        val store = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-a")
        assertTrue(store.recordStarted("c", record("sub-a")))
        assertEquals(AgentTaskStatus.RUNNING, store.load("c").entries.single().status)
    }

    @Test
    fun legacyFileWithoutOwnerIsTreatedAsLeftover() {
        // 老版本写的文件没有 owner 字段；升级必然伴随进程重启，里面的 RUNNING 同样是残留。
        val legacy = AgentTaskLedger().upsert(record("sub-a"))
        val path = AgentTaskLedgerStore(temporaryFolder.root).fileFor("c")!!
        path.parentFile!!.mkdirs()
        path.writeText(legacy.toJson().toString(), Charsets.UTF_8)

        val entry = AgentTaskLedgerStore(temporaryFolder.root, owner = "any").load("c").entries.single()
        assertEquals(AgentTaskStatus.CANCELLED, entry.status)
    }

    @Test
    fun stoppedOrphanIsPersistedOnTheNextWrite() {
        val before = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-a")
        before.recordStarted("c", record("sub-a"))

        // 重启后派了新任务：写入时会带上新 owner，顺手把旧的 RUNNING 落盘成已停止。
        val after = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-b")
        assertTrue(after.recordStarted("c", record("sub-b", startedAt = 2_000L)))

        val entries = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-b").load("c").entries
        assertEquals(2, entries.size)
        assertEquals(AgentTaskStatus.RUNNING, entries.first { it.id == "sub-b" }.status)
        assertEquals(AgentTaskStatus.CANCELLED, entries.first { it.id == "sub-a" }.status)
    }

    @Test
    fun completedEntriesAreNotTouchedAcrossProcesses() {
        val before = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-a")
        before.recordStarted("c", record("sub-a"))
        before.recordFinished("c", "sub-a", AgentTaskStatus.COMPLETED, "完成", 2_000L)

        val entry = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-b").load("c").entries.single()
        assertEquals(AgentTaskStatus.COMPLETED, entry.status)
        assertEquals("完成", entry.summary)
    }

    @Test
    fun successfulWritesBumpTheChangeSignalSoTheCardRefreshes() {
        val before = AgentTaskLedgerStore.changes.value
        val store = AgentTaskLedgerStore(temporaryFolder.root, owner = "process-a")

        store.recordStarted("c", record("sub-a"))

        assertTrue(
            "界面靠这个信号刷新，不递增就永远停在派发前的状态",
            AgentTaskLedgerStore.changes.value > before,
        )
    }

    @Test
    fun failedWritesDoNotBumpTheChangeSignal() {
        val before = AgentTaskLedgerStore.changes.value
        // 空作用域写不进去，也就没有新状态可刷新。
        assertFalse(AgentTaskLedgerStore(temporaryFolder.root, owner = "p").recordStarted("  ", record("sub-a")))
        assertEquals(before, AgentTaskLedgerStore.changes.value)
    }

    @Test
    fun recordsStartThenFinish() {
        val store = store()
        assertTrue(store.recordStarted("conversation-1", record("sub-a")))
        assertEquals(AgentTaskStatus.RUNNING, store.load("conversation-1").entries.single().status)

        assertTrue(store.recordFinished("conversation-1", "sub-a", AgentTaskStatus.COMPLETED, "完成", 2_000L))
        val entry = store.load("conversation-1").entries.single()
        assertEquals(AgentTaskStatus.COMPLETED, entry.status)
        assertEquals("完成", entry.summary)
        assertEquals(2_000L, entry.endedAt)
    }

    @Test
    fun scopesAreIsolated() {
        val store = store()
        store.recordStarted("conversation-1", record("sub-a"))
        assertTrue(store.load("conversation-2").isEmpty)
    }

    @Test
    fun finishingUnknownIdDoesNotCreateAnEntry() {
        val store = store()
        assertFalse(store.recordFinished("c", "missing", AgentTaskStatus.FAILED, "x", 1L))
        assertTrue(store.load("c").isEmpty)
    }

    @Test
    fun blankScopeIsNotPersisted() {
        val store = store()
        assertFalse(store.recordStarted("  ", record("sub-a")))
        assertNull(store.fileFor(""))
        assertTrue(store.load("  ").isEmpty)
    }

    @Test
    fun scopeIsHashedIntoASafeFileName() {
        val file = store().fileFor("../../etc/passwd")!!
        assertEquals(temporaryFolder.root, file.parentFile)
        assertTrue(file.name.endsWith(".json"))
        assertFalse(file.name.contains("/"))
    }

    @Test
    fun oversizedFileIsIgnored() {
        val store = store()
        val file = store.fileFor("c")!!
        file.parentFile!!.mkdirs()
        file.writeText("x".repeat(AgentTaskLedgerStore.MAX_BYTES + 1), Charsets.UTF_8)
        assertTrue(store.load("c").isEmpty)
    }

    @Test
    fun corruptedFileReadsAsEmpty() {
        val store = store()
        val file = store.fileFor("c")!!
        file.parentFile!!.mkdirs()
        file.writeText("not json", Charsets.UTF_8)
        assertTrue(store.load("c").isEmpty)
    }

    /**
     * 同一轮里模型可以并发派多个子代理，执行器各自持有 store 实例 —— 如果锁按实例分配，
     * 这里就会稳定丢记录。8 条并发写入必须全部留下。
     */
    @Test
    fun concurrentWritesFromSeparateStoresDoNotLoseEntries() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { index ->
            thread {
                val writer = store()
                start.await(5, TimeUnit.SECONDS)
                writer.recordStarted("c", record("sub-$index", startedAt = index.toLong() + 1))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发写入超时", done.await(10, TimeUnit.SECONDS))

        val entries = store().load("c").entries
        assertEquals(8, entries.size)
        assertEquals((0 until 8).map { "sub-$it" }.toSet(), entries.map { it.id }.toSet())
    }
}
