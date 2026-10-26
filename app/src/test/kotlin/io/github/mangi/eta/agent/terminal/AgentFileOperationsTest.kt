package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFileOperationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test fun failuresCarryEnvironmentAndExplainMismatchedPaths() {
        val operations = operations()
        // 注意：JVM 单测跑在 Linux 主机上，/workspace 下的真实文件能被直接读到；
        // 这里必须用一个确定不存在的子路径，否者拿到的是宿主上的真文件。
        val linuxPath = JSONObject(operations.readFile("/workspace/__eta_missing__/README.md"))
        assertEquals("FILE_NOT_FOUND", linuxPath.getString("code"))
        assertEquals("android", linuxPath.getString("environment"))
        assertEquals("user", linuxPath.getString("identity"))
        assertTrue(linuxPath.getString("environment_note").contains("设为 linux"))

        val local = JSONObject(operations.readFile("missing.txt"))
        assertEquals("FILE_NOT_FOUND", local.getString("code"))
        assertEquals("android", local.getString("environment"))
        assertFalse(local.toString(), local.has("environment_note"))

        val stat = JSONObject(operations.statFile("/workspace/__eta_missing__"))
        assertEquals("FILE_NOT_FOUND", stat.getString("code"))
        assertTrue(stat.getString("environment_note").contains("设为 linux"))
    }

    @Test fun bytePaginationNeverSkipsContentHiddenByOutputLimit() {
        val file = temporary.newFile("large.txt").apply { writeText("a".repeat(20_000)) }
        val operations = operations()
        val first = JSONObject(operations.readFile(file.name, maxBytes = 65_536))
        assertTrue(first.toString(), first.getBoolean("truncated"))
        assertEquals(16_000, first.getInt("bytes_read"))
        val second = JSONObject(operations.readFile(file.name, offsetBytes = first.getLong("next_offset_bytes")))
        assertFalse(second.getBoolean("truncated"))
        assertEquals(file.readText(), first.getString("content") + second.getString("content"))
    }

    @Test fun chineseAndSupplementaryCharactersSurviveByteBoundaries() {
        val original = "a".repeat(15_999) + "汉😀字"
        val file = temporary.newFile("utf8.txt").apply { writeText(original) }
        val first = JSONObject(operations().readFile(file.name))
        assertEquals(15_999, first.getInt("bytes_read"))
        val second = JSONObject(operations().readFile(file.name, offsetBytes = first.getLong("next_offset_bytes")))
        assertEquals(original, first.getString("content") + second.getString("content"))
        assertEquals(file.length(), second.getLong("next_offset_bytes"))
    }

    @Test fun invalidUtf8AndBinaryFilesHaveExplicitFailures() {
        // 真正无效的 UTF-8：0xE4 后面本该跟两个续字节，实际是 'A''B'。
        // 「游标落在字符中间」是另一类问题（OFFSET_INSIDE_CHARACTER），见 FileToolLargeChineseReadTest。
        val text = temporary.newFile("utf8.txt").apply { writeBytes(byteArrayOf(0xE4.toByte(), 0x41, 0x42)) }
        assertEquals("NOT_UTF8_TEXT", JSONObject(operations().readFile(text.name)).getString("code"))
        val binary = temporary.newFile("binary.dat").apply { writeBytes(byteArrayOf(65, 0, 66)) }
        assertEquals("BINARY_FILE", JSONObject(operations().readFile(binary.name)).getString("code"))
    }

    @Test fun customWorkingDirectoryDoesNotRedefineHome() {
        val cwd = temporary.newFolder("nested")
        File(temporary.root, "config.txt").writeText("home")
        File(cwd, "config.txt").writeText("cwd")
        val operations = AgentFileOperations(LocalFileToolBackend(
            workspace = cwd, home = temporary.root,
        ))
        assertEquals("home", JSONObject(operations.readFile("~/config.txt")).getString("content"))
        assertEquals("cwd", JSONObject(operations.readFile("config.txt")).getString("content"))
    }

    @Test fun lineRangeAndLargeSingleLineReturnRecoverableOffsets() {
        val file = temporary.newFile("lines.txt").apply { writeText("一\n二\n" + "长".repeat(8_000) + "\n四") }
        val second = JSONObject(operations().readFile(file.name, startLine = 2, maxLines = 1))
        assertEquals("二\n", second.getString("content"))
        assertEquals(3, second.getInt("next_line"))
        val longLine = JSONObject(operations().readFile(file.name, startLine = 3))
        assertTrue(longLine.getBoolean("line_truncated"))
        assertEquals(3, longLine.getInt("next_line"))
        val remainder = JSONObject(operations().readFile(file.name, offsetBytes = longLine.getLong("next_offset_bytes")))
        assertEquals("长".repeat(8_000) + "\n四", longLine.getString("content") + remainder.getString("content"))
    }

    @Test fun directoryPagesEnumerateEveryEntryAndRejectChangedDirectory() {
        repeat(241) { File(temporary.root, "entry-$it").writeText("") }
        val operations = operations()
        val names = mutableSetOf<String>()
        var offset = 0
        var revision: String? = null
        do {
            val page = JSONObject(operations.listDirectory("", limit = 80, offset = offset, expectedRevision = revision))
            assertTrue(page.toString(), page.getBoolean("ok"))
            revision = page.getString("revision")
            val entries = page.getJSONArray("entries")
            for (index in 0 until entries.length()) assertTrue(names.add(entries.getJSONObject(index).getString("name")))
            offset = page.getInt("next_offset")
        } while (page.getBoolean("has_more"))
        assertEquals(241, names.size)
        val oldTime = temporary.root.lastModified()
        Files.setLastModifiedTime(temporary.root.toPath(), FileTime.fromMillis(oldTime + 10_000))
        assertEquals("STALE_CURSOR", JSONObject(operations.listDirectory("", offset = offset, expectedRevision = revision)).getString("code"))
    }

    @Test fun deepDirectoryResumesAfterOutputBudgetWithoutLosingOrRepeatingNames() {
        var directory = temporary.root
        repeat(5) { level -> directory = File(directory, "directory-$level-" + "x".repeat(70)).apply { mkdir() } }
        val expected = (0 until 90).map { "文件-" + "x".repeat(80) + "-$it.txt" }.toSet()
        expected.forEach { File(directory, it).writeText("") }
        val operations = operations()
        var offset = 0
        var revision: String? = null
        val actual = mutableListOf<String>()
        var outputLimited = false
        var rounds = 0
        do {
            val raw = operations.listDirectory(directory.path, limit = 200, offset = offset, expectedRevision = revision)
            assertTrue("JSON exceeded output budget: ${raw.length}", raw.length <= 16_000)
            val result = JSONObject(raw)
            assertTrue(result.toString(), result.getBoolean("ok"))
            val entries = result.getJSONArray("entries")
            for (index in 0 until entries.length()) actual += entries.getJSONObject(index).getString("name")
            if (result.getBoolean("has_more")) assertTrue(result.getInt("next_offset") > offset)
            offset = result.getInt("next_offset")
            revision = result.getString("revision")
            outputLimited = outputLimited || result.getString("stop_reason") == "output_limit"
            assertTrue(++rounds < 20)
        } while (result.getBoolean("has_more"))
        assertTrue(outputLimited)
        assertEquals(expected, actual.toSet())
        assertEquals(expected.size, actual.size)
    }

    @Test fun editRequiresUniqueOldTextAndKeepsFileOnFailure() {
        val file = temporary.newFile("edit.txt").apply { writeText("one one") }
        val operations = operations()
        assertEquals("MATCH_NOT_FOUND", JSONObject(operations.editFile(file.name, "missing", "x")).getString("code"))
        assertEquals("AMBIGUOUS_MATCH", JSONObject(operations.editFile(file.name, "one", "x")).getString("code"))
        assertEquals("one one", file.readText())
        val result = JSONObject(operations.editFile(file.name, "one", "二", replaceAll = true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("atomic"))
        assertEquals(2, result.getInt("replacements"))
        assertEquals("二 二", file.readText())
    }

    @Test fun staleRevisionAndOldContentDigestPreventOverwrite() {
        val file = temporary.newFile("edit.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val initial = backend.stat(file.path)
        file.writeText("different")
        assertEquals("FILE_CHANGED", JSONObject(AgentFileOperations(backend).editFile(file.name, "different", "new", expectedRevision = initial.revision)).getString("code"))
        try {
            backend.write(file.path, "new".toByteArray(), false, expectedSha256 = "0".repeat(64))
            throw AssertionError("digest mismatch accepted")
        } catch (error: FileToolException) { assertEquals("FILE_CHANGED", error.code) }
        assertEquals("different", file.readText())
    }

    @Test fun atomicOverwriteRetainsExistingPosixPermissions() {
        val file = temporary.newFile("permissions.txt").apply { writeText("original") }
        val permissions = PosixFilePermissions.fromString("rwxr-----")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        val result = JSONObject(operations().writeFile(file.name, "updated"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("atomic"))
        assertEquals("updated", file.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun symlinksResolveToAccessibleFilesOutsideWorkingDirectory() {
        val workspace = temporary.newFolder("workspace")
        val outside = temporary.newFile("outside.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(workspace, "escape").toPath(), outside.toPath())
        val result = JSONObject(AgentFileOperations(LocalFileToolBackend(workspace)).readFile("escape"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("outside", result.getString("content"))
        assertEquals(outside.canonicalPath, result.getString("path"))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingFileError() {
        val file = temporary.newFile("cancel.txt").apply { writeText("data") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root, isCancelled = { true }))
        try {
            operations.readFile(file.name)
            throw AssertionError("cancelled read accepted")
        } catch (_: InterruptedException) { }
    }

    @Test fun editReportsLineRangeAndLineDelta() {
        val file = temporary.newFile("edit.txt").apply { writeText("a\nb\nc\nd\n") }
        val result = JSONObject(operations().editFile(file.name, "b\nc", "B\nC\nX"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("line"))
        assertEquals(3, result.getInt("line_end"))
        assertEquals(3, result.getInt("lines_added"))
        assertEquals(2, result.getInt("lines_removed"))
        assertTrue(result.getString("diff").contains("+X"))
        assertTrue(result.getString("diff").contains("-b"))
        assertEquals("a\nB\nC\nX\nd\n", file.readText())
    }

    @Test fun writeReportsCreatedAppendAndLineCount() {
        val operations = operations()
        val created = JSONObject(operations.writeFile("new.txt", "x\ny\n"))
        assertTrue(created.toString(), created.getBoolean("ok"))
        assertTrue(created.getBoolean("created"))
        assertEquals(2, created.getInt("line_count"))
        assertEquals(2, created.getInt("lines_added"))
        assertEquals(0, created.getInt("lines_removed"))

        val appended = JSONObject(operations.writeFile("new.txt", "z\n", append = true))
        assertEquals("append", appended.getString("mode"))
        assertFalse(appended.getBoolean("created"))
        assertEquals(3, appended.getInt("line_count"))
        assertEquals(1, appended.getInt("lines_added"))
        assertEquals(0, appended.getInt("lines_removed"))
        assertTrue(appended.getString("diff").contains("+z"))
    }

    @Test fun batchEditsApplyInOrderAndWriteOnce() {
        val file = temporary.newFile("batch.txt").apply { writeText("a\nb\nc\n") }
        val result = JSONObject(
            operations().editFile(
                path = file.name,
                oldText = null,
                newText = null,
                edits = listOf(FileEdit("a", "A"), FileEdit("c", "C")),
            ),
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("edits_applied"))
        assertEquals(2, result.getInt("replacements"))
        assertEquals("A\nb\nC\n", file.readText())
        assertEquals(2, result.getJSONArray("edits").length())
        assertEquals(1, result.getInt("line"))
        assertEquals(3, result.getInt("line_end"))
    }

    @Test fun batchEditFailureLeavesFileUntouchedAndReportsIndex() {
        val file = temporary.newFile("batch.txt").apply { writeText("a\nb\n") }
        val result = JSONObject(
            operations().editFile(
                path = file.name,
                oldText = null,
                newText = null,
                edits = listOf(FileEdit("a", "A"), FileEdit("missing", "X")),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("MATCH_NOT_FOUND", result.getString("code"))
        assertEquals(1, result.getJSONObject("hint").getInt("edit_index"))
        assertEquals(2, result.getJSONObject("hint").getInt("edits_total"))
        assertEquals("a\nb\n", file.readText())
    }

    @Test fun batchAmbiguousEditDoesNotWriteEarlierEdits() {
        val file = temporary.newFile("batch.txt").apply { writeText("a x x\n") }
        val result = JSONObject(
            operations().editFile(
                path = file.name,
                oldText = null,
                newText = null,
                edits = listOf(FileEdit("a", "A"), FileEdit("x", "y")),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("AMBIGUOUS_MATCH", result.getString("code"))
        assertEquals(1, result.getJSONObject("hint").getInt("edit_index"))
        assertEquals("a x x\n", file.readText())
    }

    @Test fun batchRejectsMixingSingleAndArrayForms() {
        val file = temporary.newFile("batch.txt").apply { writeText("a") }
        val result = JSONObject(operations().editFile(file.name, "a", "b", edits = listOf(FileEdit("a", "c"))))
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertEquals("a", file.readText())
    }

    @Test fun editThatChangesNothingIsRejectedInsteadOfRewriting() {
        val file = temporary.newFile("same.txt").apply { writeText("same") }
        val result = JSONObject(operations().editFile(file.name, "same", "same"))
        assertFalse(result.getBoolean("ok"))
        assertEquals("NO_CHANGES", result.getString("code"))
        assertEquals("same", file.readText())
    }

    @Test fun editWorksAcrossMultiByteChunkBoundary() {
        val prefix = "汉".repeat(6_000)
        val file = temporary.newFile("big.txt").apply { writeText(prefix + "\nEND\n") }
        val result = JSONObject(operations().editFile(file.name, "END", "DONE"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(prefix + "\nDONE\n", file.readText())
    }

    @Test fun multiFileEditsApplyOnlyAfterEveryFileMatches() {
        val first = temporary.newFile("first.txt").apply { writeText("one\ntwo\n") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha\n") }
        val result = JSONObject(
            operations().editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"), FileEdit("two", "TWO"))),
                    FilePatch(second.name, listOf(FileEdit("alpha", "ALPHA"))),
                ),
            ),
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("file_count"))
        assertEquals(3, result.getInt("total_replacements"))
        assertEquals("ONE\nTWO\n", first.readText())
        assertEquals("ALPHA\n", second.readText())
        assertEquals(2, result.getJSONArray("files").getJSONObject(0).getJSONArray("edits").length())
    }

    @Test fun multiFileFailureWritesNothingAndPointsAtTheFile() {
        val first = temporary.newFile("first.txt").apply { writeText("one") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha") }
        val result = JSONObject(
            operations().editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"))),
                    FilePatch(second.name, listOf(FileEdit("missing", "X"))),
                ),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("MATCH_NOT_FOUND", result.getString("code"))
        val hint = result.getJSONObject("hint")
        assertEquals(1, hint.getInt("file_index"))
        assertEquals(2, hint.getInt("files_total"))
        assertEquals(0, hint.getInt("edit_index"))
        assertTrue(hint.getString("file_path").endsWith("second.txt"))
        assertEquals("one", first.readText())
        assertEquals("alpha", second.readText())
    }

    @Test fun multiFileEditRejectsDuplicatePaths() {
        val file = temporary.newFile("same.txt").apply { writeText("a") }
        val result = JSONObject(
            operations().editFiles(
                listOf(
                    FilePatch(file.name, listOf(FileEdit("a", "A"))),
                    FilePatch(file.name, listOf(FileEdit("a", "B"))),
                ),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("DUPLICATE_PATH", result.getString("code"))
        assertEquals("a", file.readText())
    }

    @Test fun multiFileWriteFailureRollsBackFilesAlreadyWritten() {
        val first = temporary.newFile("first.txt").apply { writeText("one") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha") }
        val backend = FailOnWriteBackend(LocalFileToolBackend(temporary.root), failOn = setOf(2))
        val result = JSONObject(
            AgentFileOperations(backend).editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"))),
                    FilePatch(second.name, listOf(FileEdit("alpha", "ALPHA"))),
                ),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("WRITE_FAILED_ROLLED_BACK", result.getString("code"))
        assertEquals("one", first.readText())
        assertEquals("alpha", second.readText())
    }

    @Test fun multiFileRollbackFailureIsReportedAsPartial() {
        val first = temporary.newFile("first.txt").apply { writeText("one") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha") }
        val backend = FailOnWriteBackend(LocalFileToolBackend(temporary.root), failOn = setOf(2, 3))
        val result = JSONObject(
            AgentFileOperations(backend).editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"))),
                    FilePatch(second.name, listOf(FileEdit("alpha", "ALPHA"))),
                ),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("WRITE_FAILED_PARTIAL", result.getString("code"))
        assertEquals(0, result.getJSONObject("hint").getInt("rolled_back"))
        assertEquals(1, result.getJSONObject("hint").getJSONArray("rollback_failures").length())
        assertEquals("ONE", first.readText())
        assertEquals("alpha", second.readText())
    }

    @Test fun multiFileRejectsDocumentUrisBeforeTouchingTheBackend() {
        val result = JSONObject(
            operations().editFiles(
                listOf(FilePatch("content://media/1", listOf(FileEdit("a", "A")))),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("UNSUPPORTED_FILE_REFERENCE", result.getString("code"))
    }

    @Test fun editingExistingFileRequiresPriorRead() {
        val file = temporary.newFile("guard.txt").apply { writeText("a") }
        val ledger = FileAccessLedger()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), ledger)
        val denied = JSONObject(operations.editFile(file.name, "a", "A"))
        assertFalse(denied.getBoolean("ok"))
        assertEquals("READ_REQUIRED", denied.getString("code"))
        assertEquals("a", file.readText())
        assertTrue(JSONObject(operations.readFile(file.name)).getBoolean("ok"))
        val allowed = JSONObject(operations.editFile(file.name, "a", "A"))
        assertTrue(allowed.toString(), allowed.getBoolean("ok"))
        assertEquals("A", file.readText())
    }

    @Test fun editingMarksFileAsKnownSoASecondEditNeedsNoExtraRead() {
        val file = temporary.newFile("guard.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        operations.readFile(file.name)
        assertTrue(JSONObject(operations.editFile(file.name, "a", "A")).getBoolean("ok"))
        // 工具自己刚写过的文件，再改不需要重新读一次。
        val second = JSONObject(operations.editFile(file.name, "A", "B"))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals("B", file.readText())
    }

    @Test fun overwritingExistingFileRequiresReadButCreatingDoesNot() {
        val existing = temporary.newFile("existing.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        val denied = JSONObject(operations.writeFile(existing.name, "b"))
        assertFalse(denied.getBoolean("ok"))
        assertEquals("READ_REQUIRED", denied.getString("code"))
        assertEquals("a", existing.readText())
        val created = JSONObject(operations.writeFile("new.txt", "x"))
        assertTrue(created.toString(), created.getBoolean("ok"))
        assertTrue(JSONObject(operations.readFile(existing.name)).getBoolean("ok"))
        assertTrue(JSONObject(operations.writeFile(existing.name, "b")).getBoolean("ok"))
        assertEquals("b", existing.readText())
    }

    @Test fun multiFileEditRequiresEveryTargetToBeRead() {
        val first = temporary.newFile("first.txt").apply { writeText("one") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        operations.readFile(first.name)
        val result = JSONObject(
            operations.editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"))),
                    FilePatch(second.name, listOf(FileEdit("alpha", "ALPHA"))),
                ),
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("READ_REQUIRED", result.getString("code"))
        assertEquals(1, result.getJSONObject("hint").getInt("file_index"))
        assertEquals("one", first.readText())
        assertEquals("alpha", second.readText())
    }

    @Test fun editSavesBackupAndRestoresLatestVersion() {
        val file = temporary.newFile("backup.txt").apply { writeText("one") }
        val store = MemoryBackupStore()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), store)
        operations.readFile(file.name)
        val edited = JSONObject(operations.editFile(file.name, "one", "two"))
        val backupId = edited.getString("backup_id")
        assertTrue(backupId.isNotEmpty())
        val restored = JSONObject(operations.restoreFile(file.name))
        assertTrue(restored.toString(), restored.getBoolean("ok"))
        assertEquals("one", file.readText())
        assertEquals(backupId, restored.getString("restored_from"))
    }

    @Test fun listBackupsReturnsNewestFirstAndSupportsSpecificRestore() {
        val file = temporary.newFile("history.txt").apply { writeText("one") }
        val store = MemoryBackupStore()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), store)
        operations.readFile(file.name)
        val first = JSONObject(operations.editFile(file.name, "one", "two"))
        val second = JSONObject(operations.editFile(file.name, "two", "three"))
        val list = JSONObject(operations.listFileBackups(file.name))
        assertEquals(2, list.getInt("count"))
        assertEquals(second.getString("backup_id"), list.getJSONArray("backups").getJSONObject(0).getString("id"))
        val restored = JSONObject(operations.restoreFile(file.name, first.getString("backup_id")))
        assertTrue(restored.toString(), restored.getBoolean("ok"))
        assertEquals("one", file.readText())
    }

    @Test fun restoreWithoutBackupFailsExplicitly() {
        val file = temporary.newFile("no-backup.txt").apply { writeText("one") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), MemoryBackupStore())
        operations.readFile(file.name)
        val result = JSONObject(operations.restoreFile(file.name))
        assertFalse(result.getBoolean("ok"))
        assertEquals("BACKUP_NOT_FOUND", result.getString("code"))
        assertEquals("one", file.readText())
    }

    @Test fun writeOverwriteAlsoSavesRestorableBackup() {
        val file = temporary.newFile("write-backup.txt").apply { writeText("one") }
        val store = MemoryBackupStore()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), store)
        operations.readFile(file.name)
        val written = JSONObject(operations.writeFile(file.name, "two"))
        assertFalse(written.isNull("backup_id"))
        assertEquals("two", file.readText())
        assertTrue(JSONObject(operations.restoreFile(file.name)).getBoolean("ok"))
        assertEquals("one", file.readText())
    }

    @Test fun emptyFileBackupRestoresEmptyContent() {
        val file = temporary.newFile("empty.txt").apply { writeText("") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), MemoryBackupStore())
        operations.readFile(file.name)
        val written = JSONObject(operations.writeFile(file.name, "content"))
        assertFalse(written.isNull("backup_id"))
        assertEquals("content", file.readText())
        assertTrue(JSONObject(operations.restoreFile(file.name)).getBoolean("ok"))
        assertEquals("", file.readText())
    }

    @Test fun dryRunComputesDiffWithoutWritingOrBackup() {
        val file = temporary.newFile("preview.txt").apply { writeText("one") }
        val store = MemoryBackupStore()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), store)
        operations.readFile(file.name)
        val preview = JSONObject(operations.editFile(file.name, "one", "two", dryRun = true))
        assertTrue(preview.toString(), preview.getBoolean("ok"))
        assertTrue(preview.getBoolean("dry_run"))
        assertTrue(preview.getBoolean("would_write"))
        assertEquals("one", file.readText())
        assertEquals(0, store.list("android", "user", file.canonicalPath).size)
        assertTrue(preview.getString("diff").contains("+two"))
    }

    @Test fun dryRunMultiFileEditDoesNotTouchAnyFile() {
        val first = temporary.newFile("dry-a.txt").apply { writeText("one\n") }
        val second = temporary.newFile("dry-b.txt").apply { writeText("alpha\n") }
        val store = MemoryBackupStore()
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger(), store)
        operations.readFile(first.name)
        operations.readFile(second.name)
        val preview = JSONObject(
            operations.editFiles(
                listOf(
                    FilePatch(first.name, listOf(FileEdit("one", "ONE"))),
                    FilePatch(second.name, listOf(FileEdit("alpha", "ALPHA"))),
                ),
                dryRun = true,
            ),
        )
        assertTrue(preview.toString(), preview.getBoolean("ok"))
        assertTrue(preview.getBoolean("dry_run"))
        assertEquals(2, preview.getInt("file_count"))
        assertEquals("one\n", first.readText())
        assertEquals("alpha\n", second.readText())
        assertEquals(0, store.list("android", "user", first.canonicalPath).size)
    }

    @Test fun expectedRevisionLetsAnUnreadFileThrough() {
        val file = temporary.newFile("rev.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        // 没读过，但报出的 revision 与当前一致：内容就是这一版，允许直接改。
        val revision = JSONObject(operations.statFile(file.name))
            .getJSONObject("file").getString("revision")
        val edited = JSONObject(operations.editFile(file.name, "a", "A", expectedRevision = revision))
        assertTrue(edited.toString(), edited.getBoolean("ok"))
        assertEquals("A", file.readText())
    }

    @Test fun writeFileAlsoAcceptsAMatchingRevision() {
        val file = temporary.newFile("over.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        val revision = JSONObject(operations.statFile(file.name))
            .getJSONObject("file").getString("revision")
        val written = JSONObject(operations.writeFile(file.name, "b", expectedRevision = revision))
        assertTrue(written.toString(), written.getBoolean("ok"))
        assertEquals("b", file.readText())
    }

    @Test fun wrongExpectedRevisionReportsFileChangedNotEmptyRead() {
        val file = temporary.newFile("rev.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        val result = JSONObject(
            operations.editFile(file.name, "a", "A", expectedRevision = "stale-revision-value"),
        )
        assertFalse(result.getBoolean("ok"))
        // 版本不对时先报 FILE_CHANGED：让模型重新读，而不是误以为「没读过」。
        assertEquals("FILE_CHANGED", result.getString("code"))
        assertEquals("a", file.readText())
    }

    @Test fun readRequiredHintHandsOutTheRevisionThatUnlocksTheEdit() {
        val file = temporary.newFile("rev.txt").apply { writeText("a") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root), FileAccessLedger())
        val denied = JSONObject(operations.editFile(file.name, "a", "A"))
        assertEquals("READ_REQUIRED", denied.getString("code"))
        val revision = denied.getJSONObject("hint").getString("revision")
        assertTrue("hint 应带上当前 revision", revision.isNotEmpty())
        assertTrue(denied.getString("message").contains("expected_revision"))
        // 提示里给出的 revision 就是免重读的钥匙；用它重试应当成功。
        val retried = JSONObject(operations.editFile(file.name, "a", "A", expectedRevision = revision))
        assertTrue(retried.toString(), retried.getBoolean("ok"))
        assertEquals("A", file.readText())
    }
}

/** 内存备份存储：验证 AgentFileOperations 的保存/恢复调用，不触碰真实磁盘。 */
private class MemoryBackupStore : FileBackupStore {
    private val entries = mutableListOf<FileBackupEntry>()
    private val data = mutableMapOf<String, ByteArray>()

    override fun save(environment: String, identity: String, path: String, bytes: ByteArray, tool: String): String {
        val id = java.util.UUID.randomUUID().toString()
        entries += FileBackupEntry(id, environment, identity, path, bytes.size.toLong(), entries.size.toLong() + 1, tool)
        data[id] = bytes.copyOf()
        return id
    }

    override fun list(environment: String, identity: String, path: String, limit: Int): List<FileBackupEntry> =
        entries.filter { it.environment == environment && it.identity == identity && it.path == path }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun load(id: String): ByteArray? = data[id]?.copyOf()
}

/** 第 N 次 write 抛 IOException，用来验证跨文件写入中途失败的回滚路径。 */
private class FailOnWriteBackend(
    private val delegate: FileToolBackend,
    private val failOn: Set<Int>,
) : FileToolBackend by delegate {
    private var writes = 0

    override fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String?,
        expectedSha256: String?,
    ): FileToolWriteResult {
        writes += 1
        if (writes in failOn) throw IOException("simulated write failure")
        return delegate.write(path, bytes, append, expectedRevision, expectedSha256)
    }

}
