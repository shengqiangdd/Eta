package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 预扫描（walk）路径的语义测试。
 *
 * 真机后端用一条 find 拿整棵树，这里用 [WalkBackend] 在本地复刻同一份契约：
 * 只给「普通文件 + 符号链接」、按相对路径字典序、超限就置 truncated。
 * 重点不是性能，而是「换成预扫描后，续查、跳过、完整性标注不能变味」。
 */
class FileToolSearchWalkTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 用本地文件系统模拟 shell 后端：walk 一次给全树，list 仍可用（用来确认真的没走它）。 */
    private class WalkBackend(
        private val local: LocalFileToolBackend,
        private val limit: Int = 20_000,
        /** 模拟 find 报告「有目录进不去」：列表看不出缺了什么，但覆盖度已经不完整。 */
        private val unreadable: Boolean = false,
        /** 模拟后端给出非法相对路径（防线必须拦住，不能带到 join() 之后）。 */
        private val weirdEntry: String? = null,
    ) : FileToolBackend by local {
        var walkCalls = 0
        var listCalls = 0

        override fun walkFiles(root: String, includeHidden: Boolean, maxEntries: Int): FileToolWalk? {
            walkCalls++
            if (weirdEntry != null) return FileToolWalk(listOf(FileToolWalkEntry(weirdEntry, FileToolKind.FILE)), truncated = false)
            val base = File(root)
            if (!base.isDirectory) throw FileToolException("FILE_NOT_FOUND", "搜索范围不存在")
            val entries = mutableListOf<FileToolWalkEntry>()
            base.walkTopDown().forEach { file ->
                if (file == base) return@forEach
                val relative = file.relativeTo(base).invariantSeparatorsPath
                if (relative.isEmpty()) return@forEach
                if (!includeHidden && relative.split('/').any { it.startsWith('.') }) return@forEach
                val kind = when {
                    Files.isSymbolicLink(file.toPath()) -> FileToolKind.SYMLINK
                    file.isFile -> FileToolKind.FILE
                    else -> return@forEach
                }
                entries += FileToolWalkEntry(relative, kind)
            }
            entries.sortWith(compareBy { it.relative })
            val capped = minOf(limit, maxEntries)
            return FileToolWalk(
                entries = entries.take(capped),
                truncated = entries.size > capped,
                unreadable = unreadable,
                problem = if (unreadable) "find: /secret: Permission denied" else null,
            )
        }

        override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
            listCalls++
            return local.list(path, offset, limit, expectedRevision)
        }
    }

    private fun backend(limit: Int = 20_000, unreadable: Boolean = false, weirdEntry: String? = null): WalkBackend =
        WalkBackend(LocalFileToolBackend(temporary.root), limit, unreadable, weirdEntry)

    @Test fun walkGlobSeesEveryFileWithoutAnyDirectoryListing() {
        temporary.newFolder("nested").also { folder ->
            repeat(3) { File(folder, "n$it.kt").writeText("") }
        }
        repeat(4) { temporary.newFile("f$it.txt").writeText("") }
        temporary.newFile("a.kt")
        val backend = backend()
        val result = JSONObject(FileToolSearch(backend).glob("", "**/*.kt", 50, null, false))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(4, result.getInt("count"))
        assertEquals(1, backend.walkCalls)
        assertEquals(0, backend.listCalls)
        assertTrue(result.getBoolean("complete"))
    }

    @Test fun walkCursorResumesExactlyOncePerFile() {
        repeat(6) { temporary.newFile("f$it.txt").writeText("") }
        val backend = backend()
        val search = FileToolSearch(backend)
        val paths = mutableListOf<String>()
        var cursor: String? = null
        var rounds = 0
        do {
            val result = JSONObject(search.glob("", "**/*", 1, cursor, false))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) paths += matches.getJSONObject(index).getString("relative_path")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 20)
        } while (cursor != null)
        assertEquals((0 until 6).map { "f$it.txt" }.toSet(), paths.toSet())
        assertEquals(6, paths.size)
        assertEquals(0, backend.listCalls)
    }

    @Test fun walkGrepStillReportsSymlinkAsIncompleteCoverage() {
        temporary.newFile("text.txt").writeText("needle")
        Files.createSymbolicLink(File(temporary.root, "link.txt").toPath(), File(temporary.root, "text.txt").toPath())
        val result = JSONObject(FileToolSearch(backend()).grep("", "needle", "**/*", 50, null, false, true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertEquals(1, result.getInt("total_skipped"))
        assertFalse(result.getBoolean("complete"))
        assertTrue(result.isNull("next_cursor"))
    }

    @Test fun walkCursorGoesStaleWhenItsAnchorDisappears() {
        val first = temporary.newFile("a.txt").apply { writeText("hit") }
        temporary.newFile("b.txt").writeText("hit")
        val backend = backend()
        val search = FileToolSearch(backend)
        val page = JSONObject(search.grep("", "hit", "**/*", 1, null, false, true))
        val cursor = page.getString("next_cursor")
        assertTrue(first.delete())
        val stale = JSONObject(search.grep("", "hit", "**/*", 1, cursor, false, true))
        assertFalse(stale.getBoolean("ok"))
        assertEquals("STALE_CURSOR", stale.getString("code"))
    }

    @Test fun truncatedWalkListIsNeverReportedAsComplete() {
        repeat(5) { temporary.newFile("f$it.txt").writeText("needle") }
        val result = JSONObject(FileToolSearch(backend(limit = 2)).grep("", "needle", "**/*", 50, null, false, true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        // truncated 的意思是「还有更多可以续查」，而这里预扫描已经到头了：没有游标，但结果确实不全。
        assertFalse(result.getBoolean("truncated"))
        assertTrue(result.getBoolean("partial"))
        assertFalse(result.getBoolean("complete"))
        assertEquals("entry_limit", result.getString("stop_reason"))
        assertTrue(result.getString("hint"), result.getString("hint").contains("截断"))
    }

    /** find 报错（目录进不去）时，结果看起来是「正常扫完了」——必须标成不完整并说明原因。 */
    @Test fun unreadableWalkIsNeverReportedAsComplete() {
        temporary.newFile("a.txt").writeText("needle")
        val result = JSONObject(
            FileToolSearch(backend(unreadable = true)).grep("", "needle", "**/*", 50, null, false, true),
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertTrue(result.getBoolean("partial"))
        assertFalse(result.getBoolean("complete"))
        // stop_reason 不能说 complete：否则只读这一个字段的调用方会得到与 complete=false 相反的答案。
        assertEquals("unreadable", result.getString("stop_reason"))
        val hint = result.getString("hint")
        assertTrue(hint, hint.contains("无法读取"))
        assertTrue(hint, hint.contains("Permission denied"))
    }

    @Test fun walkGrepPrefetchesInBatchesWithoutRepeatingFiles() {
        repeat(12) { index -> temporary.newFile("f%02d.txt".format(index)).writeText("needle\n") }
        val base = backend()
        val batchSizes = mutableListOf<Int>()
        val counted = object : FileToolBackend by base {
            override fun readCheckedBatch(paths: List<String>, offset: Long, limit: Int): List<FileToolBatchRead> {
                batchSizes += paths.size
                return base.readCheckedBatch(paths, offset, limit)
            }
        }
        val result = JSONObject(FileToolSearch(counted).grep("", "needle", "**/*", 50, null, false, true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(12, result.getInt("count"))
        assertEquals(batchSizes.toString(), 12, batchSizes.sum())
        assertTrue(batchSizes.toString(), batchSizes.size < 12)
    }

    /**
     * 续查时 pending 非空，而且「读完 pending 那一刻不触发任何上限」：
     * 只有这种路径才会在同一轮里继续取候选 —— 如果 walkIndex 没越过 pending，
     * 就会把刚读完的文件从列表里再取一次、从头再读一遍。
     * 构造：300 KB 的大文件（首轮必在 byte_limit 停下），末尾才有命中。
     */
    @Test fun walkCursorWithPendingFileDoesNotRepeatIt() {
        temporary.newFile("a.txt").writeText("x".repeat(300_000) + "\nneedle\n")
        temporary.newFile("b.txt").writeText("needle\n")
        val search = FileToolSearch(backend())
        val hits = mutableListOf<String>()
        var cursor: String? = null
        var rounds = 0
        do {
            val result = JSONObject(search.grep("", "needle", "**/*", 5, cursor, false, true))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) hits += matches.getJSONObject(index).getString("relative_path")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 10)
        } while (cursor != null)
        assertEquals(listOf("a.txt", "b.txt"), hits)
    }

    @Test fun walkGrepFindsMatchesInMultipleFiles() {
        repeat(3) { temporary.newFile("f$it.txt").writeText("needle $it\n") }
        temporary.newFile("skip.txt").writeText("nothing here\n")
        val result = JSONObject(FileToolSearch(backend()).grep("", "needle", "**/*", 50, null, false, true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(3, result.getInt("count"))
        assertTrue(result.getBoolean("complete"))
    }

    /** 老遍历对每条相对路径都过 validateRelative；walk 也是入口，不能因为路径来自 find 就跳过。 */
    @Test fun walkEntriesGoThroughTheSameRelativePathValidation() {
        temporary.newFile("a.txt").writeText("needle")
        val result = JSONObject(
            FileToolSearch(backend(weirdEntry = "../escape.txt")).grep("", "needle", "**/*", 50, null, false, true),
        )
        // require() 抛的 IllegalArgumentException 由 fileToolOperation 统一转成 INVALID_ARGUMENT。
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
    }

    /**
     * 字节预算按行粒度消费：最后一个块可能在预算耗尽后才算完，允许跨线一个 16 KiB 块，
     * 但不能成倍超出（预取读回来的量也应只计实际扫过的部分）。
     */
    @Test fun walkGrepByteBudgetOvershootIsBoundedToOneChunk() {
        repeat(80) { index -> temporary.newFile("f%02d.txt".format(index)).writeText("x".repeat(8_000) + "\n") }
        val result = JSONObject(FileToolSearch(backend()).grep("", "needle", "**/*.txt", 50, null, false, true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("byte_limit", result.getString("stop_reason"))
        val scanned = result.getInt("bytes_scanned")
        // 按行消费：最后一个块可能在预算耗尽后才算完，允许一个 16 KiB 块，但不能成倍超出。
        assertTrue("bytes_scanned=$scanned", scanned <= FileToolSearch.MAX_BYTES + 16_000)
    }
}
