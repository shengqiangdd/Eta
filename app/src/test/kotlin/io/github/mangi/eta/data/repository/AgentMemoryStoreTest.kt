package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    @Test
    fun archiveIsReadableButNeverTouchesMainRevision() {
        val root = temporaryFolder.newFolder()
        val store = AgentMemoryStore(root)
        val main = store.replaceAll("# 核心记忆\n当前内容")
        // 没有归档时返回 null：调用方据此说「没有归档」，而不是拿空内容冒充
        assertNull(store.readArchive())

        val archive = File(root, "memory/MEMORY-ARCHIVE.md")
        archive.parentFile?.mkdirs()
        archive.writeText("# 核心记忆\n旧偏好一\n旧偏好二\n## 旧项目\n已归档项目")

        val page = store.readArchive(startLine = 3, maxChars = 20)!!
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: 旧偏好二"))
        assertTrue(page.hasMore)

        val search = store.readArchive(query = "已归档")!!
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("## 旧项目"))

        val section = store.readArchive(section = "核心记忆")!!
        assertTrue(section.content.contains("旧偏好一"))
        assertFalse(section.content.contains("已归档项目"))

        // 归档只读：读多少次都不改变主文件的 revision / 内容
        assertEquals(main.revision, store.snapshot().revision)
        assertEquals("# 核心记忆\n当前内容", store.snapshot().content)
    }

    @Test
    fun archiveSectionAmbiguityIsReportedInsteadOfSilentlyPickingFirst() {
        val root = temporaryFolder.newFolder()
        AgentMemoryStore(root).replaceAll("# 核心记忆\n内容")
        val archive = File(root, "memory/MEMORY-ARCHIVE.md")
        archive.parentFile?.mkdirs()
        archive.writeText("## 重名\n一\n## 重名\n二")

        val failure = assertThrows(AgentMemoryException::class.java) {
            AgentMemoryStore(root).readArchive(section = "重名")
        }
        assertEquals("MEMORY_SECTION_AMBIGUOUS", failure.code)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
