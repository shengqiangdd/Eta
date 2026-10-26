package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRunFileChangesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentRunFileChanges(temporaryFolder.root)

    @Test
    fun mergesRepeatedWritesToOneEntry() {
        val store = store()
        store.record("run-1", "/sdcard/a.txt", "write_file", added = 3, removed = 0, diff = "1|+a", at = 10L)
        store.record("run-1", "/sdcard/a.txt", "edit_file", added = 1, removed = 2, diff = "2|-b\n2|+c", at = 20L)
        val entries = store.load("run-1")
        assertEquals(1, entries.size)
        assertEquals(4, entries[0].added)
        assertEquals(2, entries[0].removed)
        assertEquals(2, entries[0].count)
        // 保留最后一次 diff：用户要看「现在是什么样」，不是中间态。
        assertEquals("2|-b\n2|+c", entries[0].diff)
        assertEquals(20L, entries[0].at)
    }

    @Test
    fun keepsFirstTouchOrderAcrossFiles() {
        val store = store()
        store.record("run-1", "/a", "write_file", 1, 0, "", at = 1L)
        store.record("run-1", "/b", "write_file", 2, 0, "", at = 2L)
        assertEquals(listOf("/a", "/b"), store.load("run-1").map { it.path })
    }

    @Test
    fun separatesRunsAndIgnoresBlankRunId() {
        val store = store()
        store.record("run-1", "/a", "write_file", 1, 0, "", at = 1L)
        store.record("run-2", "/b", "write_file", 2, 0, "", at = 2L)
        assertEquals(listOf("/a"), store.load("run-1").map { it.path })
        assertEquals(listOf("/b"), store.load("run-2").map { it.path })
        assertNull(store.record("  ", "/a", "write_file", 1, 0, ""))
        assertTrue(store.load("").isEmpty())
    }

    @Test
    fun unsafeRunIdStaysInsideRoot() {
        val store = store()
        val file = store.fileFor("../../etc/passwd")!!
        assertEquals(temporaryFolder.root.canonicalPath, file.parentFile!!.canonicalPath)
        store.record("../../etc/passwd", "/a", "write_file", 1, 0, "", at = 1L)
        assertEquals(1, store.load("../../etc/passwd").size)
    }

    @Test
    fun longDiffsAreTruncatedAndEntriesCapped() {
        val store = store()
        store.record("run-1", "/long", "write_file", 1, 0, "x".repeat(AgentRunFileChanges.MAX_DIFF_CHARS + 500))
        assertEquals(AgentRunFileChanges.MAX_DIFF_CHARS, store.load("run-1")[0].diff.length)
        for (index in 0 until AgentRunFileChanges.MAX_ENTRIES + 5) {
            store.record("run-1", "/file-$index", "write_file", 1, 0, "")
        }
        val entries = store.load("run-1")
        assertEquals(AgentRunFileChanges.MAX_ENTRIES, entries.size)
        // 只留最近的：最早的文件被挤出去，最后写的一定还在。
        assertFalse(entries.any { it.path == "/long" })
        assertEquals("/file-${AgentRunFileChanges.MAX_ENTRIES + 4}", entries.last().path)
    }

    @Test
    fun corruptAndOversizedFilesReadAsEmpty() {
        val store = store()
        store.record("run-1", "/a", "write_file", 1, 0, "")
        val file = store.fileFor("run-1")!!
        file.writeText("not json", Charsets.UTF_8)
        assertTrue(store.load("run-1").isEmpty())
        file.writeText("x".repeat(AgentRunFileChanges.MAX_BYTES + 1), Charsets.UTF_8)
        assertTrue(store.load("run-1").isEmpty())
        // 坏文件不应阻止后续写入：重新记录一次就恢复。
        store.record("run-1", "/b", "write_file", 1, 0, "")
        assertEquals(listOf("/b"), store.load("run-1").map { it.path })
        assertFalse(file.parentFile!!.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun oldRunFilesArePrunedByCount() {
        val store = store()
        repeat(AgentRunFileChanges.MAX_FILES + 3) { index ->
            store.record("run-$index", "/f", "write_file", 1, 0, "")
        }
        val files = temporaryFolder.root.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        assertTrue("账本文件数量应被限制，实际 ${files.size}", files.size <= AgentRunFileChanges.MAX_FILES)
    }

    @Test
    fun digestSummarizesCountsAndSkipsEmptyInput() {
        assertEquals("", AgentRunFileChanges.digest(emptyList()))
        val store = store()
        store.record("run-1", "/a/one.kt", "write_file", 3, 1, "1|+a")
        store.record("run-1", "/a/two.kt", "edit_file", 2, 2, "2|-b")
        val digest = AgentRunFileChanges.digest(store.load("run-1"), header = "会话 abc")
        assertTrue(digest.startsWith("会话 abc"))
        assertTrue(digest.contains("2 个文件"))
        assertTrue(digest.contains("+5/-3"))
        assertTrue(digest.contains("/a/one.kt"))
        assertTrue(digest.contains("  1|+a"))
    }
}
