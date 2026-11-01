package io.github.mangi.eta.agent.runtime

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
