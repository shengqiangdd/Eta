package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellFileToolBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvePreservesQuotedPathsAndUsesEnvironmentHome() {
        val backend = newBackend { command, _, _ ->
            runShell("readlink() { printf '%s\\n' \"\$3\"; }; $command")
        }
        listOf(
            "/workspace/plain", "/workspace/a b", "/workspace/a'b", "/workspace/line\nend\n",
            "/workspace/\$(printf injected)", "/workspace/`printf injected`", "/workspace/*?[x]",
        ).forEach { path -> assertEquals(path, backend.resolve(path)) }
        assertEquals("/workspace/-leading-option", backend.resolve("-leading-option"))
        assertEquals("/guest-home", backend.resolve("~"))
        assertEquals("/guest-home/a'b", backend.resolve("~/a'b"))
    }

    @Test
    fun resolveDoesNotCollapseDotDotBeforeFollowingRemoteSymlink() {
        var requested = ""
        val backend = newBackend { command, _, _ ->
            requested = command
            result("/elsewhere/file\n")
        }
        assertEquals("/elsewhere/file", backend.resolve("link/../file"))
        assertTrue(requested, requested.contains("'/workspace/link/../file'"))
        val missing = newBackend { _, _, _ -> result(exit = 1) }
        assertEquals("/workspace/link/../new/file", missing.resolve("link/../new/file"))
        assertFailure("FILE_COMMAND_FAILED") { newBackend { _, _, _ -> result(exit = 127) }.resolve("file") }
        assertFailure("INVALID_PATH") { backend.resolve("bad\u0000path") }
    }

    @Test
    fun statPassesRealNewlineFormatAndPathAsSeparateLiteralArguments() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)`printf injected`")
        val arguments = File(temporaryFolder.root, "stat-arguments")
        val backend = newBackend { command, _, _ ->
            val stub = "stat() { printf '%s\\000' \"\$@\" > ${shellQuote(arguments.absolutePath)}; " +
                "printf '7\\n1:2:7:10:11:modified:changed\\n'; }; "
            runShell(stub + command)
        }
        val stat = backend.stat(file.absolutePath)
        assertEquals(FileToolKind.FILE, stat.kind)
        assertEquals(7L, stat.sizeBytes)
        val args = arguments.readText().split('\u0000').dropLast(1)
        assertEquals(listOf("-c", "%s\n%d:%i:%s:%Y:%Z:%y:%z", "--", file.absolutePath), args)
    }

    @Test
    fun statRejectsInvalidOrTruncatedMetadata() {
        listOf(
            "unknown\n7\n1:2:7:10:11:modified:changed\n",
            "file\n-1\n1:2:-1:10:11:modified:changed\n",
            "file\n7\\n1:2:7:10:11:modified:changed\n",
            "file\n7\n",
            "file\n7\n1:2:8:10:11:modified:changed\n",
            "file\n7\n?:2:7:10:11:modified:changed\n",
        ).forEach { output ->
            assertFailure("FILE_METADATA_INVALID") { newBackend { _, _, _ -> result(output) }.stat("/workspace/a") }
        }
        assertFailure("UNSUPPORTED_ENCODING") {
            newBackend { _, _, _ -> OneShotShellResult(0, byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf()) }.stat("/workspace/a")
        }
        assertFailure("FILE_OUTPUT_LIMIT") {
            newBackend { _, _, _ -> result(metadata(), truncated = true) }.stat("/workspace/a")
        }
    }

    @Test
    fun blockReadsReturnExactUnalignedByteRangeAndHandleEof() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val backend = newBackend { command, input, maxBytes ->
            assertEquals(null, input)
            assertTrue(command, command.contains("dd if='/workspace/a' bs=4096"))
            val blockOffset = Regex("skip=([0-9]+)").find(command)!!.groupValues[1].toLong()
            val count = Regex("count=([0-9]+)").find(command)!!.groupValues[1].toInt()
            assertEquals(count * 4096, maxBytes)
            val start = (blockOffset * 4096).coerceAtMost(bytes.size.toLong()).toInt()
            val end = (start + maxBytes).coerceAtMost(bytes.size)
            OneShotShellResult(0, bytes.copyOfRange(start, end), byteArrayOf())
        }
        listOf(0L to 4, 4095L to 6, 4096L to 4096, 8193L to 7000, 19_998L to 8, 30_000L to 8).forEach { (offset, limit) ->
            val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
            assertArrayEquals(bytes.copyOfRange(start, (start + limit).coerceAtMost(bytes.size)), backend.readBytes("/workspace/a", offset, limit))
        }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", -1, 2) }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", 0, 0) }
    }

    @Test
    fun checkedReadFramesMetadataAndContentInOneExecution() {
        val file = temporaryFolder.newFile("checked.txt")
        val text = "第一行\nsecond line\n" + "x".repeat(5000) + "\n"
        file.writeText(text)
        var calls = 0
        val backend = newBackend { command, input, _ ->
            calls++
            assertEquals(null, input)
            runShell(command)
        }
        val full = backend.readChecked(file.absolutePath, 0, 16_000)
        assertEquals(1, calls)
        assertArrayEquals(text.toByteArray(), full.bytes)
        assertEquals(text.toByteArray().size.toLong(), full.statBefore.sizeBytes)
        assertEquals(full.statBefore.revision, full.statAfter.revision)
        // 非对齐起点与 readBytes 同语义：先按 4096 取块，再切块内偏移。
        assertArrayEquals(text.toByteArray().copyOfRange(4095, 4101), backend.readChecked(file.absolutePath, 4095, 6).bytes)
        assertFailure("INVALID_ARGUMENT") { backend.readChecked(file.absolutePath, -1, 2) }
        assertFailure("INVALID_ARGUMENT") { backend.readChecked(file.absolutePath, 0, 0) }
        assertFailure("FILE_NOT_FOUND") { backend.readChecked("${temporaryFolder.root.absolutePath}/missing", 0, 4) }
        assertFailure("NOT_REGULAR_FILE") { backend.readChecked(temporaryFolder.root.absolutePath, 0, 4) }
    }

    @Test
    fun checkedReadSurfacesMetadataChangeAndRejectsBrokenFraming() {
        val output = metadata(version = "1") + "aGk=\n" + metadata(version = "2")
        val checked = newBackend { _, _, _ -> result(output) }.readChecked("/workspace/a", 0, 4)
        assertArrayEquals("hi".toByteArray(), checked.bytes)
        assertTrue(checked.statBefore.revision != checked.statAfter.revision)
        listOf(metadata() + "aGk=\n", metadata() + "aGk=\n" + metadata() + "\n").forEach { broken ->
            assertFailure("FILE_METADATA_INVALID") {
                newBackend { _, _, _ -> result(broken) }.readChecked("/workspace/a", 0, 4)
            }
        }
    }

    @Test
    fun listKeepsNulDelimitedNamesAndUsesStablePagination() {
        val paths = listOf("/workspace/z", "/workspace/a\n'b", "/workspace/-first")
        var calls = 0
        val backend = newBackend { command, input, _ ->
            calls++
            if (command == "sh -s") {
                val script = input!!.decodeToString()
                val selected = paths.sorted().filter { script.contains(shellQuote(it)) }
                result(statPageFrame(selected.map { "file" } + "directory"))
            } else {
                result(directoryFrame(paths, metadata("directory")))
            }
        }
        val first = backend.list("/workspace", 0, 2)
        assertEquals(listOf("/workspace/-first", "/workspace/a\n'b"), first.entries.map { it.path })
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        val last = backend.list("/workspace", first.nextOffset, 2, first.revision)
        assertEquals(listOf("/workspace/z"), last.entries.map { it.path })
        assertFalse(last.hasMore)
        assertEquals(3, last.nextOffset)
        assertEquals(first.revision, last.revision)
        // 每页固定两条命令：合成帧（枚举） + statPage（条目元数据与目录复核）。
        assertEquals(4, calls)
    }

    @Test
    fun fullDirectoryPageUsesTwoExecutionsAndStdinForLongPaths() {
        val root = "/" + List(6) { "directory".repeat(20) }.joinToString("/")
        val paths = List(200) { "$root/entry-${it.toString().padStart(3, '0')}\n'\$(printf literal)" }
        var calls = 0
        var batches = 0
        val backend = newBackend { command, input, _ ->
            calls++
            if (command == "sh -s") {
                batches++
                assertTrue(input!!.size > 131_072)
                val script = input.decodeToString()
                paths.forEach { assertTrue(script.contains(shellQuote(it))) }
                // 旧实现是 200 次 stat；现在按 32 KiB 切块，最多几块。
                val chunks = Regex("stat -c ").findAll(script).count()
                assertTrue("chunks=$chunks", chunks in 1..12)
                result(statPageFrame(paths.map { "file" } + "directory"))
            } else {
                assertTrue(command, command.contains("find '"))
                result(directoryFrame(paths, metadata("directory")))
            }
        }
        val page = backend.list(root, 0, 200)
        assertEquals(200, page.entries.size)
        assertEquals(paths, page.entries.map { it.path })
        assertEquals(2, calls)
        assertEquals(1, batches)
        assertFalse(page.hasMore)
    }

    @Test
    fun directoryListingKeepsNewlinesAndQuotesOutOfRecordFraming() {
        val directory = temporaryFolder.newFolder("listing")
        val paths = listOf("line\n'a", "\$(printf injected)", "-option")
            .map { File(directory, it).apply { writeText("x") }.absolutePath }
            .sorted()
        // 桩按格式参数分流：带 %F 的是批量 stat 页（三行），否则是单个 stat（两行，类型由内建判断先打印）。
        val stub = """
            stat() {
              fmt=''; skip=0; seen=0
              for a in "${'$'}@"; do
                if [ "${'$'}skip" = 1 ]; then fmt=${'$'}a; skip=0
                elif [ "${'$'}a" = -c ]; then skip=1
                elif [ "${'$'}a" = -- ]; then seen=1
                elif [ "${'$'}seen" = 1 ]; then
                  case ${'$'}fmt in
                    *%F*) printf 'regular file\n7\n1:2:7:10:11:modified:changed\n' ;;
                    *) printf '7\n1:2:7:10:11:modified:changed\n' ;;
                  esac
                fi
              done
              return 0
            }
        """.trimIndent() + "\n"
        var calls = 0
        val backend = newBackend { command, input, _ ->
            calls++
            // 目录帧那一趟必须走真实 stat：桩是给批量 stat 页用的，拦到目录元数据会把帧弄坏。
            if (command == "sh -s") runShell("sh -s", stub.toByteArray() + input!!)
            else runShell(stub + command)
        }
        val page = backend.list(directory.absolutePath, 0, 200)
        assertEquals(paths, page.entries.map { it.path })
        assertEquals(2, calls)
    }

    @Test
    fun batchRejectsMissingExtraAndUnterminatedMetadataRecords() {
        val paths = listOf("/workspace/a", "/workspace/b")
        val record = statRecord()
        // 每条目三行：类型行 + 大小行 + 元数据行
        val file = statType("file") + "\n" + record
        val good = file + file + statType("directory") + "\n" + record
        listOf(
            // 少一条元数据：类型行齐了，最后一条只有类型
            file + file + statType("directory") + "\n",
            // 结尾缺换行（未正常结束）
            good.dropLast(1),
            // 多一条元数据
            good + record,
        ).forEach { batchOutput ->
            val backend = newBackend { command, _, _ ->
                if (command == "sh -s") result(batchOutput)
                else result(directoryFrame(paths, metadata("directory")))
            }
            assertFailure("FILE_METADATA_INVALID") { backend.list("/workspace", 0, 2) }
        }
    }

    @Test
    fun listDoesNotTreatTruncatedOrChangedEnumerationAsComplete() {
        assertFailure("FILE_OUTPUT_LIMIT") {
            newBackend { _, _, _ ->
                result(directoryFrame(listOf("/workspace/a"), metadata("directory")), truncated = true)
            }.list("/workspace", 0, 2)
        }
        assertFailure("FILE_METADATA_INVALID") {
            newBackend { _, _, _ -> result(metadata("directory") + '\u0000' + "/workspace/a") }
                .list("/workspace", 0, 2)
        }
        // 名字列表与结尾元数据之间少了分隔 NUL：不能被当成完整的一页。
        assertFailure("DIRECTORY_TOO_LARGE") {
            newBackend { _, _, _ ->
                result(metadata("directory") + '\u0000' + "/workspace/a" + '\u0000' + metadata("directory"))
            }.list("/workspace", 0, 2)
        }
        // find 失败不能伪装成空目录：这里跑真实脚本，只把 find 换成必定失败的桩，
        // 少了 `|| exit $?` 时脚本会正常退出并返回空页，这条用例就会红。
        val stub = "stat() { printf '7\\n1:2:7:10:11:modified:changed\\n'; };\n"
        val failingFind = newBackend { command, input, _ ->
            if (command == "sh -s") runShell("sh -s", stub.toByteArray() + input!!)
            else runShell(stub + "find() { return 1; };\n" + command)
        }
        assertFailure("FILE_COMMAND_FAILED") { failingFind.list(temporaryFolder.root.absolutePath, 0, 2) }
        assertFailure("STALE_CURSOR") {
            newBackend { _, _, _ ->
                result(
                    directoryFrame(
                        listOf("/workspace/a"),
                        metadata("directory", version = "1"),
                        metadata("directory", version = "2"),
                    )
                )
            }.list("/workspace", 0, 2)
        }
        assertFailure("STALE_CURSOR") {
            newBackend { command, _, _ ->
                if (command == "sh -s") {
                    result(statPageFrame(listOf("file", "directory"), listOf(statRecord(), statRecord(version = "2"))))
                } else {
                    result(directoryFrame(listOf("/workspace/a"), metadata("directory", version = "1")))
                }
            }.list("/workspace", 0, 2)
        }
        var calls = 0
        val stale = newBackend { _, _, _ ->
            calls++
            result(directoryFrame(listOf("/workspace/a"), metadata("directory")))
        }
        assertFailure("STALE_CURSOR") { stale.list("/workspace", 0, 2, "old-revision") }
        assertEquals(1, calls)
    }

    @Test
    fun statPageUsesOneBatchedStatInvocationForAPage() {
        val paths = List(120) { "/workspace/entry-${it.toString().padStart(3, '0')}" }
        var script = ""
        val backend = newBackend { command, input, _ ->
            if (command == "sh -s") {
                script = input!!.decodeToString()
                result(statPageFrame(paths.map { "file" } + "directory"))
            } else {
                result(directoryFrame(paths, metadata("directory")))
            }
        }
        assertEquals(120, backend.list("/workspace", 0, 120).entries.size)
        // 关键回归：一页 120 个条目只允许一次 stat 调用（旧实现是 120 次 fork）。
        assertEquals(1, Regex("stat -c ").findAll(script).count())
        paths.forEach { assertTrue(script.contains(shellQuote(it))) }
    }

    @Test
    fun statPageSplitsIntoChunksInsteadOfOneCallPerEntry() {
        val paths = List(8) { "/workspace/" + "x".repeat(9_000) + "-$it" }
        var script = ""
        val backend = newBackend { command, input, _ ->
            if (command == "sh -s") {
                script = input!!.decodeToString()
                result(statPageFrame(paths.map { "file" } + "directory"))
            } else {
                result(directoryFrame(paths, metadata("directory")))
            }
        }
        assertEquals(8, backend.list("/workspace", 0, 8).entries.size)
        // 32 KiB 一块：约 72 KiB 的路径至少要分 3 块，但绝不能退回“每条目一个 stat”。
        val chunks = Regex("stat -c ").findAll(script).count()
        assertTrue("chunks=$chunks", chunks in 3..4)
        paths.forEach { assertTrue(script.contains(shellQuote(it))) }
    }

    @Test
    fun statPageRejectsEnvironmentsWithoutPercentF() {
        val backend = newBackend { command, _, _ ->
            if (command == "sh -s") result("%F\n7\n1:2:7:10:11:modified:changed\n")
            else result(directoryFrame(listOf("/workspace/a"), metadata("directory")))
        }
        assertFailure("FILE_METADATA_INVALID") { backend.list("/workspace", 0, 2) }
    }

    @Test
    fun emptyDirectoryStillVerifiesWithoutBatchRecords() {
        var calls = 0
        val backend = newBackend { command, _, _ ->
            calls++
            if (command == "sh -s") result(statPageFrame(listOf("directory")))
            else result(directoryFrame(emptyList(), metadata("directory")))
        }
        val page = backend.list("/workspace", 0, 10)
        assertTrue(page.entries.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(0, page.nextOffset)
        assertEquals(2, calls)
    }

    @Test
    fun writesCheckHashBeforeRedirectionAndKeepReplacementOutOfScript() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)").apply { writeText("original") }
        val expectedHash = "a".repeat(64)
        val replacement = "replacement\n\$(printf should_stay_literal)".toByteArray()
        var match = false
        var writeCommand = ""
        val backend = newBackend { command, input, _ ->
            if (input == null) result(metadata(size = file.length()))
            else {
                writeCommand = command
                assertArrayEquals(replacement, input)
                assertFalse(command.contains("should_stay_literal"))
                val actualHash = if (match) expectedHash else "b".repeat(64)
                val stub = "sha256sum() { cat >/dev/null; printf '%s  -\\n' '$actualHash'; }; "
                runShell(stub + command, input)
            }
        }
        assertFailure("FILE_CHANGED") { backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash) }
        assertEquals("original", file.readText())
        assertTrue(writeCommand.indexOf("sha256sum") < writeCommand.indexOf("cat >"))
        match = true
        val written = backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash)
        assertFalse(written.atomic)
        assertArrayEquals(replacement, file.readBytes())
        backend.write(file.absolutePath, replacement, true)
        assertArrayEquals(replacement + replacement, file.readBytes())
    }

    @Test
    fun staleRevisionAndInvalidHashNeverSubmitWrite() {
        var calls = 0
        val backend = newBackend { _, input, _ ->
            calls++
            assertEquals(null, input)
            result(metadata())
        }
        assertFailure("FILE_CHANGED") { backend.write("/workspace/a", byteArrayOf(1), false, expectedRevision = "stale") }
        assertEquals(1, calls)
        assertFailure("INVALID_ARGUMENT") { backend.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "invalid") }
        assertEquals(1, calls)
    }

    @Test
    fun cancellationAndAuthorizationChangesPropagateBeforeAndAfterExecution() {
        var cancellation: String? = "CANCELLED"
        var calls = 0
        val backend = ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", ensureActive = {
            cancellation?.let { throw FileToolException(it, "操作不可继续") }
        }) { _, _, _ ->
            calls++
            cancellation = "ROOT_REQUIRED"
            result("secret")
        }
        assertFailure("CANCELLED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(0, calls)
        cancellation = null
        assertFailure("ROOT_REQUIRED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(1, calls)
        assertFailure("CANCELLED") {
            newBackend { _, _, _ -> result(exit = -3, truncated = true) }.readBytes("/workspace/a", 0, 3)
        }
        assertFailure("FILE_TIMEOUT") { newBackend { _, _, _ -> result(exit = -2) }.readBytes("/workspace/a", 0, 3) }
        assertFailure("FILE_INPUT_FAILED") {
            newBackend { _, _, _ -> result(exit = SHELL_STDIN_FAILED_EXIT_CODE) }
                .write("/workspace/a", byteArrayOf(1), false)
        }
        assertFailure("FILE_ACCESS_DENIED") { newBackend { _, _, _ -> result(exit = 43) }.stat("/workspace/a") }
        assertFailure("FILE_HASH_UNAVAILABLE") {
            newBackend { _, _, _ -> result(exit = 45) }.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "a".repeat(64))
        }
    }

    @Test
    fun readCheckedBatchReadsEveryFileInOneCommand() {
        val directory = temporaryFolder.newFolder("batch")
        val files = (1..3).map { index -> File(directory, "f$index.txt").apply { writeText("x".repeat(index)) } }
        var calls = 0
        val backend = newBackend { command, _, _ ->
            calls++
            runShell(command)
        }
        val reads = backend.readCheckedBatch(files.map { it.absolutePath }, 0, 16)
        assertEquals(1, calls)
        assertEquals(files.map { it.absolutePath }, reads.map { it.path })
        assertEquals(listOf(1, 2, 3), reads.map { it.checked!!.bytes.size })
        assertTrue(reads.all { it.errorCode == null && it.checked!!.statAfter.revision == it.checked.statBefore.revision })
    }

    @Test
    fun readCheckedBatchKeepsGoodFilesWhenOneIsMissing() {
        val directory = temporaryFolder.newFolder("batchfail")
        val first = File(directory, "a.txt").apply { writeText("aaa") }
        val second = File(directory, "b.txt").apply { writeText("bbb") }
        val missing = File(directory, "missing.txt").absolutePath
        val backend = newBackend { command, _, _ -> runShell(command) }
        val reads = backend.readCheckedBatch(listOf(first.absolutePath, missing, second.absolutePath), 0, 16)
        assertEquals(3, reads.size)
        assertEquals("aaa", reads[0].checked!!.bytes.decodeToString())
        assertEquals("FILE_NOT_FOUND", reads[1].errorCode)
        assertEquals("bbb", reads[2].checked!!.bytes.decodeToString())
    }

    @Test
    fun readCheckedBatchRejectsTruncatedFrames() {
        val backend = newBackend { _, _, _ -> result(":eta:ok\nfile\n7\n") }
        assertFailure("FILE_METADATA_INVALID") { backend.readCheckedBatch(listOf("/workspace/a"), 0, 8) }
    }

    /** 预扫描脚本要真的跑一遍：两条 find 的分段标记与 -print0 拼接不能靠读代码判断。 */
    @Test
    fun walkFilesListsFilesAndSymlinksInDictionaryOrder() {
        val root = temporaryFolder.newFolder("tree")
        File(root, "b.txt").writeText("")
        File(root, "a.txt").writeText("")
        File(root, ".hidden.txt").writeText("")
        File(root, "sub").mkdir()
        File(root, "sub/c.txt").writeText("")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), File(root, "a.txt").toPath())
        val backend = newBackend { command, _, _ -> runShell(command) }
        val walk = backend.walkFiles(root.absolutePath, includeHidden = false, maxEntries = 100)!!
        assertEquals(listOf("a.txt", "b.txt", "link.txt", "sub/c.txt"), walk.entries.map { it.relative })
        assertEquals(FileToolKind.SYMLINK, walk.entries.first { it.relative == "link.txt" }.kind)
        assertFalse(walk.truncated)
        val withHidden = backend.walkFiles(root.absolutePath, includeHidden = true, maxEntries = 100)!!
        assertTrue(withHidden.entries.any { it.relative == ".hidden.txt" })
    }

    /** find 把「目录进不去」写到 stderr、退出码仍是 0：这条线索必须变成 unreadable。 */
    @Test
    fun walkFilesTreatsStderrAsIncompleteCoverage() {
        val output = "\u0001F\u0001\u0000\u0001L\u0001\u0000"
        val backend = newBackend { _, _, _ ->
            OneShotShellResult(0, output.toByteArray(), "find: /secret: Permission denied\n".toByteArray())
        }
        val walk = backend.walkFiles("/workspace", includeHidden = false, maxEntries = 100)!!
        assertTrue(walk.unreadable)
        val problem = walk.problem
        assertTrue(problem.toString(), problem != null && problem.contains("Permission denied"))
        assertTrue(walk.entries.isEmpty())
        assertFalse(walk.truncated)
    }

    /**
     * 真机上 find 报的是「路径长度超过 PATH_MAX」：错误行里的路径能有几千字符。
     * 从头截断会把唯一有用的原因切掉，提示里只剩一长串目录名 —— 这条用例守住「原因必须在」。
     */
    @Test
    fun walkFilesKeepsReasonWhenFindPathIsVeryLong() {
        val output = "\u0001F\u0001\u0000\u0001L\u0001\u0000"
        val longPath = "./" + "x".repeat(600)
        val backend = newBackend { _, _, _ ->
            OneShotShellResult(0, output.toByteArray(), "find: $longPath: File name too long\n".toByteArray())
        }
        val walk = backend.walkFiles("/workspace", includeHidden = false, maxEntries = 100)!!
        assertTrue(walk.unreadable)
        assertEquals("…: File name too long", walk.problem)
    }

    @Test
    fun walkFilesCapsEntriesAndReportsTruncation() {
        val root = temporaryFolder.newFolder("many")
        repeat(5) { File(root, "f$it.txt").writeText("") }
        val backend = newBackend { command, _, _ -> runShell(command) }
        val walk = backend.walkFiles(root.absolutePath, includeHidden = false, maxEntries = 3)!!
        assertEquals(3, walk.entries.size)
        assertTrue(walk.truncated)
    }

    private fun newBackend(execute: (String, ByteArray?, Int) -> OneShotShellResult) =
        ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", execute = execute)

    private fun metadata(kind: String = "file", size: Long = 7, version: String = "1") =
        "$kind\n$size\n1:2:$size:10:11:modified$version:changed$version\n"

    /** 目录页合成帧：<目录元数据> \0 <名字>... \0 <目录元数据>，中间空段是列表结束标记。 */
    private fun directoryFrame(entries: List<String>, before: String, after: String = before) =
        before + '\u0000' + entries.joinToString("") { it + '\u0000' } + '\u0000' + after

    private fun statRecord(size: Long = 7, version: String = "1") =
        "$size\n1:2:$size:10:11:modified$version:changed$version\n"

    /** statPage 的行帧：每条目 `%F 类型 / 大小 / 元数据` 三行，顺序与路径一致。 */
    private fun statPageFrame(kinds: List<String>, records: List<String> = List(kinds.size) { statRecord() }) =
        kinds.mapIndexed { index, kind -> statType(kind) + "\n" + records[index] }.joinToString("")

    private fun statType(kind: String) = when (kind) {
        "file" -> "regular file"
        "directory" -> "directory"
        "symlink" -> "symbolic link"
        else -> kind
    }

    private fun result(output: String = "", exit: Int = 0, truncated: Boolean = false) =
        OneShotShellResult(exit, output.toByteArray(), byteArrayOf(), outputTruncated = truncated)

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (failure: FileToolException) {
            assertEquals(code, failure.code)
        }
    }

    private fun runShell(command: String, input: ByteArray? = null): OneShotShellResult {
        // 这里验证 Shell 脚本及文件保护合同；用文件供给输入，避免提前退出与父进程写管道竞争。
        val inputFile = temporaryFolder.newFile().apply { writeBytes(input ?: byteArrayOf()) }
        val process = ProcessBuilder("sh", "-c", command)
            .directory(temporaryFolder.root)
            .redirectInput(inputFile)
            .start()
        val output = process.inputStream.readBytes()
        val error = process.errorStream.readBytes()
        assertTrue("测试 Shell 没有正常结束", process.waitFor(5, TimeUnit.SECONDS))
        return OneShotShellResult(process.exitValue(), output, error)
    }
}
