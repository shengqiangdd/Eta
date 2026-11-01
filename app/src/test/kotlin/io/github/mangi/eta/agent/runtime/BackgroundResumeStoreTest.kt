package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「这条后台任务已经续过」的落盘记忆。
 *
 * 这一层的价值全在**坏情况下也不崩**：文件被截断、被写成垃圾、涨到超限、
 * 作用域是空串 —— 都要安静地退回「没有记录」，因为它的唯一用途是少显示一个按钮。
 */
class BackgroundResumeStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = BackgroundResumeStore(temporaryFolder.root)

    @Test
    fun savesAndReloads() {
        val store = store()
        assertTrue(store.save("conversation-1", listOf("job_a", "job_b")))
        assertEquals(listOf("job_a", "job_b"), store.load("conversation-1"))
    }

    @Test
    fun unknownScopeLoadsEmpty() {
        assertEquals(emptyList<String>(), store().load("conversation-2"))
    }

    @Test
    fun lastWriteWins() {
        val store = store()
        store.save("c", listOf("job_a", "job_b"))
        assertTrue(store.save("c", listOf("job_c")))
        assertEquals(listOf("job_c"), store.load("c"))
    }

    @Test
    fun scopesDoNotShareFiles() {
        val store = store()
        store.save("a", listOf("job_a"))
        assertTrue(store.save("b", listOf("job_b")))
        assertEquals(listOf("job_a"), store.load("a"))
        assertEquals(listOf("job_b"), store.load("b"))
    }

    @Test
    fun keepsOnlyTheMostRecentIds() {
        val store = store()
        val ids = (0 until BackgroundResumeStore.MAX_IDS + 16).map { "job_$it" }
        assertTrue(store.save("c", ids))
        val loaded = store.load("c")
        assertEquals(BackgroundResumeStore.MAX_IDS, loaded.size)
        // 丢最旧的、留最新的：任务 id 不会复用，旧的记着也没用。
        assertFalse(loaded.contains("job_0"))
        assertEquals("job_${ids.lastIndex}", loaded.last())
    }

    @Test
    fun blankIdsAreDropped() {
        val store = store()
        assertTrue(store.save("c", listOf("job_a", "", "   ", "job_b")))
        assertEquals(listOf("job_a", "job_b"), store.load("c"))
    }

    @Test
    fun duplicateKeepsTheLatestPosition() {
        val store = store()
        store.save("c", listOf("job_a", "job_b", "job_a"))
        // 去重保留最近一次出现：否则「刚续过」的那条会因为位置靠前被上限挤掉。
        assertEquals(listOf("job_b", "job_a"), store.load("c"))
    }

    @Test
    fun blankScopeIsNotPersisted() {
        val store = store()
        assertNull(store.fileFor(""))
        assertNull(store.fileFor("   "))
        assertFalse(store.save("", listOf("job_a")))
        assertEquals(emptyList<String>(), store.load(""))
    }

    @Test
    fun brokenFileIsTreatedAsEmpty() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON", Charsets.UTF_8)
        assertEquals(emptyList<String>(), store.load("c"))
        // 坏文件之后仍然能写：不能因为一次坏数据就永久放弃这个作用域。
        assertTrue(store.save("c", listOf("job_a")))
        assertEquals(listOf("job_a"), store.load("c"))
    }

    @Test
    fun oversizeFileIsIgnored() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("x".repeat(BackgroundResumeStore.MAX_BYTES + 1), Charsets.UTF_8)
        assertEquals(emptyList<String>(), store.load("c"))
    }

    @Test
    fun missingFieldIsTreatedAsEmpty() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("""{"other":1}""", Charsets.UTF_8)
        assertEquals(emptyList<String>(), store.load("c"))
        assertEquals(0, store.loadState("c").chain)
        assertNull(store.loadState("c").seenUntil)
    }

    @Test
    fun savesAndReloadsChainAndWatermark() {
        val store = store()
        assertTrue(
            store.saveState(
                "c",
                BackgroundResumeState(resumed = listOf("job_a"), chain = 2, seenUntil = 1_700_000_000_000L),
            )
        )
        val loaded = store.loadState("c")
        assertEquals(listOf("job_a"), loaded.resumed)
        assertEquals(2, loaded.chain)
        assertEquals(1_700_000_000_000L, loaded.seenUntil)
    }

    /** 卡片手动点「继续」只写 id：不能把链长与水位线一起抹成默认值。 */
    @Test
    fun savingIdsKeepsChainAndWatermark() {
        val store = store()
        store.saveState("c", BackgroundResumeState(resumed = listOf("job_a"), chain = 1, seenUntil = 900L))
        assertTrue(store.save("c", listOf("job_a", "job_b")))
        val loaded = store.loadState("c")
        assertEquals(listOf("job_a", "job_b"), loaded.resumed)
        assertEquals(1, loaded.chain)
        assertEquals(900L, loaded.seenUntil)
    }

    /** 第九～十一批写下的老文件只有 `resumed`：缺字段按默认值读，不能连「续过哪些」一起丢。 */
    @Test
    fun legacyFileWithOnlyResumedLoadsOtherFieldsAsDefaults() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("""{"resumed":["job_a","job_b"]}""", Charsets.UTF_8)
        val loaded = store.loadState("c")
        assertEquals(listOf("job_a", "job_b"), loaded.resumed)
        assertEquals(0, loaded.chain)
        assertNull(loaded.seenUntil)
    }

    /** 坏值不能变成「永久停用」或「把每条都当成新事件」。 */
    @Test
    fun illegalChainAndWatermarkFallBackToDefaults() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("""{"resumed":["job_a"],"chain":-5,"seen_until":-1}""", Charsets.UTF_8)
        val loaded = store.loadState("c")
        assertEquals(0, loaded.chain)
        assertNull(loaded.seenUntil)
        assertEquals(listOf("job_a"), loaded.resumed)
    }

    @Test
    fun hugeChainIsClamped() {
        val store = store()
        val file = store.fileFor("c") ?: error("fileFor 不该返回 null")
        file.parentFile?.mkdirs()
        file.writeText("""{"chain":999999}""", Charsets.UTF_8)
        assertEquals(BackgroundResumeStore.MAX_CHAIN, store.loadState("c").chain)
    }
}
