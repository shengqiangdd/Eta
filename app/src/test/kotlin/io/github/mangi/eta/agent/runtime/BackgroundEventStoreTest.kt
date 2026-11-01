package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 结束事件的水位线存储。
 *
 * 它只做两件事：读「已告知到哪一刻」、把水位线往前推。守的点：
 * 1. **只前进不后退** —— 两个 run 并发时，后完成的那次可能带着更早的事件，直接覆盖会重报；
 * 2. 坏文件／非法值按「从没告知过」处理，不抛异常、不改写；
 * 3. 作用域不同 → 文件不同（会话之间互不吞事件）。
 */
class BackgroundEventStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(name: String = "events") = BackgroundEventStore(temporaryFolder.newFolder(name))

    @Test
    fun blankScopeHasNoFile() {
        // 同一个空白作用域问两遍，不需要两份目录：这里用一个 store 就够。
        val store = store()
        assertNull(store.fileFor(" "))
        assertNull(store.fileFor(""))
    }

    @Test
    fun differentScopesUseDifferentFiles() {
        val store = store()
        val a = store.fileFor("conversation-a")
        val b = store.fileFor("conversation-b")
        assertTrue(a != null && b != null)
        assertNotEquals(a, b)
    }

    @Test
    fun missingFileMeansNeverDelivered() {
        assertNull(store().loadDeliveredUntil("conversation-a"))
    }

    @Test
    fun markThenLoadReturnsWatermark() {
        val store = store()
        assertTrue(store.markDeliveredUntil("conversation-a", 9_000L))
        assertEquals(9_000L, store.loadDeliveredUntil("conversation-a"))
    }

    @Test
    fun watermarkNeverMovesBackwards() {
        val store = store()
        store.markDeliveredUntil("conversation-a", 9_000L)

        // 并发的另一个 run 读到的是更早的记录，它的事件不该把水位线拉回去。
        assertTrue(store.markDeliveredUntil("conversation-a", 5_000L))
        assertEquals(9_000L, store.loadDeliveredUntil("conversation-a"))
    }

    @Test
    fun nonPositiveMarkIsRejected() {
        val store = store()
        assertFalse(store.markDeliveredUntil("conversation-a", 0L))
        assertFalse(store.markDeliveredUntil("conversation-a", -1L))
        assertNull(store.loadDeliveredUntil("conversation-a"))
    }

    @Test
    fun corruptJsonMeansNeverDelivered() {
        val store = store()
        val file = store.fileFor("conversation-a")!!
        file.parentFile!!.mkdirs()
        file.writeText("{ not json")

        assertNull(store.loadDeliveredUntil("conversation-a"))
        // 读路径不改写坏文件：下一次 mark 才覆盖它。
        assertEquals("{ not json", file.readText())
    }

    @Test
    fun nonPositiveStoredValueMeansNeverDelivered() {
        val store = store()
        val file = store.fileFor("conversation-a")!!
        file.parentFile!!.mkdirs()

        file.writeText("""{"delivered_until":0}""")
        assertNull(store.loadDeliveredUntil("conversation-a"))

        file.writeText("""{"delivered_until":-5}""")
        assertNull(store.loadDeliveredUntil("conversation-a"))
    }

    @Test
    fun oversizeFileMeansNeverDelivered() {
        val store = store()
        val file = store.fileFor("conversation-a")!!
        file.parentFile!!.mkdirs()
        file.writeText("0".repeat(BackgroundEventStore.MAX_BYTES + 1))

        assertNull(store.loadDeliveredUntil("conversation-a"))
    }
}
