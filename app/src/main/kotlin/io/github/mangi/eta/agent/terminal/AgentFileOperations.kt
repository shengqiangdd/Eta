package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 批量编辑中的一条；oldText 必须与当前文本完全一致，replaceAll 仅影响本条。 */
internal data class FileEdit(
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean = false,
)

/** 一次跨文件批量编辑中的一项；path 在该批次内必须唯一。 */
internal data class FilePatch(
    val path: String,
    val edits: List<FileEdit>,
    val expectedRevision: String? = null,
)

internal class AgentFileOperations(
    private val backend: FileToolBackend,
    private val ledger: FileAccessLedger? = null,
    private val backups: FileBackupStore? = null,
) {
    private val textReader = FileToolTextReader(backend)

    /**
     * 失败结果也要带上 environment / identity，否则「文件不存在」会被读成路径写错了；
     * 路径明显属于 Linux 工作区时再补一句环境提示。
     */
    private fun fileOp(tool: String, path: String? = null, block: () -> JSONObject): String =
        fileToolOperation(tool, backend.environment, backend.identity, path, block)

    fun statFile(path: String): String = fileOp("stat_file", path) { base("stat_file").put("file", backend.stat(backend.resolve(path)).toJson()) }

    fun readFile(
        path: String,
        offsetBytes: Long = 0,
        maxBytes: Int = 16_000,
        startLine: Int? = null,
        maxLines: Int = 200,
        expectedRevision: String? = null,
    ): String = fileOp("read_file", path) {
        require(offsetBytes >= 0 && maxBytes > 0 && maxLines > 0)
        require(startLine == null || (startLine > 0 && offsetBytes == 0L))
        val stat = regularFile(path, expectedRevision)
        var offset = offsetBytes
        var line: Int? = if (offset == 0L) 1 else null
        if (startLine != null && startLine > 1) {
            var scanBytes = 0
            while (line!! < startLine && offset < stat.sizeBytes && MAX_LINE_SCAN_BYTES - scanBytes >= 4) {
                val chunk = textReader.read(stat, offset, minOf(16_000, MAX_LINE_SCAN_BYTES - scanBytes))
                var consumedChars = 0
                while (consumedChars < chunk.text.length && line < startLine) {
                    val newline = chunk.text.indexOf('\n', consumedChars)
                    if (newline < 0) { consumedChars = chunk.text.length; break }
                    consumedChars = newline + 1
                    line++
                }
                val consumed = chunk.text.substring(0, consumedChars).toByteArray(Charsets.UTF_8).size
                offset += consumed
                scanBytes += consumed
            }
            if (line < startLine && offset < stat.sizeBytes) {
                verifyUnchanged(stat)
                ledger?.markRead(backend.environment, backend.identity, stat.path)
                return@fileOp base("read_file").put("path", stat.path).put("revision", stat.revision)
                    .put("content", "").put("bytes_read", 0).put("offset_bytes", offsetBytes)
                    .put("next_offset_bytes", offset).put("next_line", line)
                    .put("start_line_reached", false).put("truncated", true).put("stop_reason", "scan_limit")
                    // 不写清下一步时，这个回执看起来就像「文件是空的」——真机上把 386 KiB 的文件当成空文件误判过。
                    .put(
                        "hint",
                        "start_line=$startLine 离文件开头太远：本次只扫到第 $line 行（约 $offset 字节）就用完了扫描预算，" +
                            "所以正文没返回（不代表文件为空）。要继续往后读就带 offset_bytes=$offset 续读（可多次）；" +
                            "只是想知道某段内容在哪一行，用 grep_files 更快。",
                    )
                    .put("resume", JSONObject().put("offset_bytes", offset))
            }
        }
        val chunk = textReader.read(stat, offset, maxBytes.coerceAtMost(16_000))
        var end = chunk.text.length
        var newlineCount = 0
        for (index in chunk.text.indices) {
            if (chunk.text[index] == '\n') {
                newlineCount++
                if (newlineCount == maxLines.coerceAtMost(2_000)) { end = index + 1; break }
            }
        }
        val content = chunk.text.substring(0, end)
        val consumed = content.toByteArray(Charsets.UTF_8).size
        val nextOffset = offset + consumed
        val truncated = nextOffset < stat.sizeBytes
        val newlines = content.count { it == '\n' }
        verifyUnchanged(stat)
        ledger?.markRead(backend.environment, backend.identity, stat.path)
        base("read_file").put("path", stat.path).put("revision", stat.revision)
            .put("size_bytes", stat.sizeBytes).put("offset_bytes", offset).put("bytes_read", consumed)
            .put("next_offset_bytes", nextOffset).put("content", content)
            .put("start_line", line ?: JSONObject.NULL).put("next_line", line?.plus(newlines) ?: JSONObject.NULL)
            .put("line_count", newlines + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0)
            .put("line_truncated", truncated && content.isNotEmpty() && !content.endsWith('\n'))
            .put("start_line_reached", startLine == null || line!! >= startLine)
            .put("truncated", truncated).put("stop_reason", if (!truncated) "eof" else if (end < chunk.text.length) "line_limit" else "byte_limit")
    }

    fun listDirectory(
        path: String,
        showHidden: Boolean = false,
        limit: Int = 80,
        offset: Int = 0,
        expectedRevision: String? = null,
    ): String = fileOp("list_directory", path) {
        require(limit > 0 && offset >= 0)
        val resolved = backend.resolve(path)
        val page = backend.list(resolved, offset, limit.coerceAtMost(200), expectedRevision)
        val entries = JSONArray()
        val result = base("list_directory").put("path", resolved).put("revision", page.revision)
            .put("entries", entries).put("offset", offset).put("next_offset", page.nextOffset)
            .put("truncated", false).put("has_more", false).put("stop_reason", "output_limit")
        // 先保留最长状态字段的空间，分页必须停在尚未发布的条目前，不能截断完整 JSON。
        var outputChars = result.toString().length
        if (outputChars > MAX_DIRECTORY_JSON_CHARS) throw FileToolException("FILE_OUTPUT_LIMIT", "目录路径和元数据超过输出预算")
        var consumed = 0
        var outputLimited = false
        for (entry in page.entries) {
            backend.checkActive()
            if (!showHidden && entry.name.startsWith('.')) { consumed++; continue }
            val item = entry.toJson()
            val cost = item.toString().length + if (entries.length() == 0) 0 else 1
            if (outputChars + cost > MAX_DIRECTORY_JSON_CHARS) {
                if (entries.length() == 0) throw FileToolException("FILE_OUTPUT_LIMIT", "单个目录条目超过输出预算")
                outputLimited = true
                break
            }
            entries.put(item)
            outputChars += cost
            consumed++
        }
        val hasMore = page.hasMore || consumed < page.entries.size
        val nextOffset = if (consumed == page.entries.size) page.nextOffset else offset + consumed
        result.put("next_offset", nextOffset).put("truncated", hasMore).put("has_more", hasMore)
            .put("stop_reason", when { !hasMore -> "eof"; outputLimited -> "output_limit"; else -> "entry_limit" })
    }

    fun writeFile(
        path: String,
        content: String,
        append: Boolean = false,
        expectedRevision: String? = null,
        dryRun: Boolean = false,
    ): String =
        fileOp("write_file", path) {
            val bytes = encodeBounded(content)
            val resolved = backend.resolve(path)
            val stat = runCatching { backend.stat(resolved) }.getOrNull()
            val existed = stat?.kind == FileToolKind.FILE
            // 版本不符要先报 FILE_CHANGED：这比「没读过」准确，
            // 否则模型会去重读一遍、再撞同一个版本冲突。
            if (stat != null && expectedRevision != null && stat.revision != expectedRevision) {
                throw FileToolException("FILE_CHANGED", "文件已变化，请重新读取")
            }
            if (existed && !append) requireReadable(stat!!, expectedRevision = expectedRevision)
            // 覆盖前读原文只为算「改了哪几行」与备份；读不到（新建 / 超限 / 二进制）就只报行数。
            val previousBytes = if (!append && existed) readWholeBytesOrNull(resolved) else null
            val previous = if (append) readWholeTextOrNull(resolved) else previousBytes?.toString(Charsets.UTF_8)
            val afterText = if (append) (previous ?: "") + content else content
            val delta = previous?.let { FileChangeSummary.between(it, afterText) }
            if (dryRun) {
                return@fileOp base("write_file").put("path", resolved)
                    .put("revision", stat?.revision ?: JSONObject.NULL)
                    .put("dry_run", true).put("would_write", true)
                    .put("mode", if (append) "append" else "overwrite")
                    .put("bytes_written", bytes.size).put("size_bytes", stat?.sizeBytes ?: bytes.size.toLong())
                    .put("created", !append && !existed)
                    .apply {
                        if (delta != null) {
                            put("lines_added", delta.added).put("lines_removed", delta.removed)
                            put("diff", delta.diff).put("diff_truncated", delta.truncated)
                        } else {
                            put("lines_added", FileChangeSummary.lineCount(content)).put("lines_removed", 0)
                        }
                    }
            }
            // 覆盖已有文件前保存原文；新建或追加没有可回滚的旧内容，不生成备份。
            val backupId = if (!append && existed && previousBytes != null) {
                backups?.save(backend.environment, backend.identity, resolved, previousBytes, "write_file")
            } else {
                null
            }
            val result = backend.write(resolved, bytes, append, expectedRevision)
            ledger?.markRead(backend.environment, backend.identity, result.stat.path)
            val lineCount = if (append && previous != null) {
                FileChangeSummary.lineCount(afterText)
            } else {
                FileChangeSummary.lineCount(content)
            }
            base("write_file").put("path", result.stat.path).put("revision", result.stat.revision)
                .put("backup_id", backupId ?: JSONObject.NULL)
                .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
                .put("size_bytes", result.stat.sizeBytes).put("atomic", result.atomic)
                .put("line_count", lineCount)
                .put("created", !append && !existed)
                .apply {
                    if (delta != null) {
                        put("lines_added", delta.added)
                        put("lines_removed", delta.removed)
                        put("diff", delta.diff)
                        put("diff_truncated", delta.truncated)
                    } else {
                        // 新建或原文过大读不到时，至少给出本次写入的行数。
                        put("lines_added", FileChangeSummary.lineCount(content))
                        put("lines_removed", 0)
                    }
                }
        }

    /**
     * 精确替换。两种写法二选一：
     * - oldText + newText：单处替换（保持旧行为）；
     * - edits：按顺序在同一份文本上应用，全部成功才写入一次（参考 Claude Code 的 MultiEdit）。
     *
     * 批量语义特意做成「全成功或全不改」：第 N 条匹配失败时文件保持原样，
     * 不会留下「前两条改了、第三条失败」这种需要模型再猜现场的状态。
     */
    fun editFile(
        path: String,
        oldText: String?,
        newText: String?,
        replaceAll: Boolean = false,
        expectedRevision: String? = null,
        edits: List<FileEdit> = emptyList(),
        dryRun: Boolean = false,
    ): String = fileOp("edit_file", path) {
        val batch = resolveEdits(oldText, newText, replaceAll, edits)
        val stat = regularFile(path, expectedRevision)
        requireReadable(stat, expectedRevision = expectedRevision)
        if (stat.sizeBytes > MAX_EDIT_BYTES) throw FileToolException("FILE_TOO_LARGE", "精确编辑仅支持不超过 512 KiB 的文本文件")
        val originalBytes = readWholeTextBytes(stat)
        val original = decodeWholeText(originalBytes)
        val outcome = applyEdits(original, batch)
        if (outcome.text == original) throw FileToolException("NO_CHANGES", "编辑前后内容相同，文件未修改")
        val replacement = encodeBounded(outcome.text)
        verifyUnchanged(stat)
        val delta = FileChangeSummary.between(original, outcome.text)
        if (dryRun) {
            return@fileOp base("edit_file").put("path", stat.path).put("revision", stat.revision)
                .put("dry_run", true).put("would_write", true).put("backup_id", JSONObject.NULL)
                .put("edits_applied", batch.size).put("replacements", outcome.replacements)
                .put("bytes_written", replacement.size)
                .put("line", outcome.firstLine).put("line_end", outcome.lastLineEnd)
                .put("lines_added", delta.added).put("lines_removed", delta.removed)
                .put("line_count_before", delta.lineCountBefore).put("line_count_after", delta.lineCountAfter)
                .put("diff", delta.diff).put("diff_truncated", delta.truncated)
                .put("edits", outcome.details)
        }
        val backupId = backups?.save(backend.environment, backend.identity, stat.path, originalBytes, "edit_file")
        val result = backend.write(stat.path, replacement, append = false, expectedRevision = stat.revision, expectedSha256 = sha256(originalBytes))
        ledger?.markRead(backend.environment, backend.identity, result.stat.path)
        base("edit_file").put("path", result.stat.path).put("revision", result.stat.revision)
            .put("backup_id", backupId ?: JSONObject.NULL)
            .put("edits_applied", batch.size).put("replacements", outcome.replacements)
            .put("bytes_written", replacement.size).put("atomic", result.atomic)
            .put("line", outcome.firstLine).put("line_end", outcome.lastLineEnd)
            .put("lines_added", delta.added).put("lines_removed", delta.removed)
            .put("line_count_before", delta.lineCountBefore).put("line_count_after", delta.lineCountAfter)
            .put("diff", delta.diff).put("diff_truncated", delta.truncated)
            .put("edits", outcome.details)
    }

    /**
     * 跨文件批量编辑（参考 Codex 的 apply_patch）：先把每个文件都读到内存里验证并应用，
     * 全部成功后才开始写入。任一条匹配失败 → 一个文件都不写；写入中途失败 → 回滚已写文件。
     *
     * 回滚是「尽力而为」：如果回滚时文件已被外部改过，会明确返回 WRITE_FAILED_PARTIAL，
     * 列出哪些文件可能仍是新内容，而不是把部分成功说成成功。
     */
    fun editFiles(patches: List<FilePatch>, dryRun: Boolean = false): String =
        fileOp("edit_files", patches.firstOrNull()?.path) {
        if (patches.isEmpty()) throw FileToolException("INVALID_ARGUMENT", "files 不能为空")
        if (patches.size > MAX_BATCH_FILES) throw FileToolException("TOO_MANY_FILES", "单次最多 $MAX_BATCH_FILES 个文件")
        if (patches.sumOf { it.edits.size } > MAX_BATCH_EDITS) {
            throw FileToolException("TOO_MANY_EDITS", "单次最多 $MAX_BATCH_EDITS 处编辑；请拆分批次")
        }
        val paths = patches.map { it.path }
        if (paths.any(String::isBlank)) throw FileToolException("INVALID_ARGUMENT", "files[].path 不能为空")
        if (paths.any { "://" in it }) {
            throw FileToolException("UNSUPPORTED_FILE_REFERENCE", "请使用已授权的文件系统路径；文档 URI 需先导入")
        }
        if (paths.toSet().size != paths.size) throw FileToolException("DUPLICATE_PATH", "同一文件不能在一次批量编辑里出现多次")

        val prepared = patches.mapIndexed { fileIndex, patch ->
            if (patch.edits.isEmpty()) {
                throw FileToolException("INVALID_ARGUMENT", "files[$fileIndex].edits 不能为空")
            }
            patch.edits.forEachIndexed { editIndex, edit ->
                if (edit.oldText.isEmpty()) {
                    throw FileToolException("EMPTY_MATCH", "files[$fileIndex].edits[$editIndex].old_text 不能为空")
                }
                encodeBounded(edit.oldText)
                encodeBounded(edit.newText)
            }
            val stat = regularFile(patch.path, patch.expectedRevision)
            requireReadable(stat, fileIndex, patches.size, expectedRevision = patch.expectedRevision)
            if (stat.sizeBytes > MAX_EDIT_BYTES) {
                throw FileToolException("FILE_TOO_LARGE", "精确编辑仅支持不超过 512 KiB 的文本文件：${stat.path}")
            }
            val originalBytes = readWholeTextBytes(stat)
            val original = decodeWholeText(originalBytes)
            val outcome = applyEdits(original, patch.edits, fileIndex, patches.size, patch.path)
            if (outcome.text == original) {
                throw FileToolException("NO_CHANGES", "files[$fileIndex] 编辑前后内容相同：${stat.path}")
            }
            PreparedFile(
                stat = stat,
                originalBytes = originalBytes,
                original = original,
                outcome = outcome,
                replacement = encodeBounded(outcome.text),
                originalDigest = sha256(originalBytes),
                replacementDigest = sha256(outcome.text.toByteArray(Charsets.UTF_8)),
            )
        }

        // 所有文件都验证通过后再复核一次，尽量缩小「验证后又被外部改动」的窗口。
        prepared.forEach { verifyUnchanged(it.stat) }
        if (dryRun) {
            return@fileOp base("edit_files")
                .put("dry_run", true).put("would_write", true)
                .put("files", editFilesSummary(prepared, dryRun = true).getJSONArray("files"))
                .put("file_count", prepared.size)
                .put("total_replacements", prepared.sumOf { it.outcome.replacements })
                .put("total_lines_added", prepared.sumOf { FileChangeSummary.between(it.original, it.outcome.text).added })
                .put("total_lines_removed", prepared.sumOf { FileChangeSummary.between(it.original, it.outcome.text).removed })
        }

        val written = ArrayList<PreparedFile>(prepared.size)
        try {
            prepared.forEach { file ->
                val backupId = backups?.save(
                    backend.environment,
                    backend.identity,
                    file.stat.path,
                    file.originalBytes,
                    "edit_files",
                )
                val result = backend.write(
                    file.stat.path,
                    file.replacement,
                    append = false,
                    expectedRevision = file.stat.revision,
                    expectedSha256 = file.originalDigest,
                )
                written += file.copy(writtenStat = result.stat, atomic = result.atomic, backupId = backupId)
            }
            written.forEach { ledger?.markRead(backend.environment, backend.identity, it.writtenStat.path) }
        } catch (error: InterruptedException) {
            // 取消也要尽量回滚，但保留取消语义，不能伪装成一条普通写入失败。
            rollback(written)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            val failures = rollback(written)
            val reason = (error as? FileToolException)?.message ?: error.message ?: error.javaClass.simpleName
            if (failures.isEmpty()) {
                throw FileToolException(
                    "WRITE_FAILED_ROLLED_BACK",
                    "写入失败（$reason）；已回滚本次已写的 ${written.size} 个文件",
                )
            }
            throw FileToolException(
                "WRITE_FAILED_PARTIAL",
                "写入失败（$reason），且 ${failures.size} 个文件无法回滚：" +
                    failures.joinToString("；") { "${it.path}：${it.reason}" },
                JSONObject().put("rolled_back", written.size - failures.size)
                    .put("rollback_failures", JSONArray(failures.map { it.toJson() })),
            )
        }

        val summary = editFilesSummary(written, dryRun = false)
        base("edit_files").put("files", summary.getJSONArray("files"))
            .put("file_count", summary.getInt("file_count"))
            .put("total_replacements", summary.getInt("total_replacements"))
            .put("total_lines_added", summary.getInt("total_lines_added"))
            .put("total_lines_removed", summary.getInt("total_lines_removed"))
    }

    private fun editFilesSummary(files: List<PreparedFile>, dryRun: Boolean): JSONObject {
        val array = JSONArray()
        var totalReplacements = 0
        var totalAdded = 0
        var totalRemoved = 0
        files.forEach { file ->
            val delta = FileChangeSummary.between(file.original, file.outcome.text)
            totalReplacements += file.outcome.replacements
            totalAdded += delta.added
            totalRemoved += delta.removed
            array.put(
                JSONObject().put("path", file.writtenStat.path).put("revision", file.writtenStat.revision)
                    .put("edits_applied", file.outcome.details.length())
                    .put("replacements", file.outcome.replacements)
                    .put("line", file.outcome.firstLine).put("line_end", file.outcome.lastLineEnd)
                    .put("lines_added", delta.added).put("lines_removed", delta.removed)
                    .put("line_count_before", delta.lineCountBefore).put("line_count_after", delta.lineCountAfter)
                    .put("diff", delta.diff).put("diff_truncated", delta.truncated)
                    .put("backup_id", file.backupId ?: JSONObject.NULL)
                    .put("atomic", if (dryRun) false else file.atomic)
                    .put("edits", file.outcome.details),
            )
        }
        return JSONObject().put("files", array).put("file_count", array.length())
            .put("total_replacements", totalReplacements)
            .put("total_lines_added", totalAdded).put("total_lines_removed", totalRemoved)
    }

    /** 尽力回滚：只恢复「本批次确实写过」的文件，并用内容摘要确认回滚前没有被外部改过。 */
    private fun rollback(written: List<PreparedFile>): List<RollbackFailure> {
        val failures = ArrayList<RollbackFailure>()
        written.asReversed().forEach { file ->
            runCatching {
                backend.write(
                    file.stat.path,
                    file.originalBytes,
                    append = false,
                    expectedRevision = null,
                    expectedSha256 = file.replacementDigest,
                )
            }.onFailure { error ->
                failures += RollbackFailure(file.stat.path, error.message ?: error.javaClass.simpleName)
            }
        }
        return failures
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 编辑已有文件前的读取门禁；账本为空（例如直接单测）时不强制。 */
    private fun requireReadable(
        stat: FileToolStat,
        fileIndex: Int? = null,
        fileCount: Int = 1,
        expectedRevision: String? = null,
    ) {
        val ledger = ledger ?: return
        if (ledger.wasRead(backend.environment, backend.identity, stat.path)) return
        // 模型报出的 revision 与当前一致，说明它面对的正是这一版：revision 由后端根据
        // 大小/时间戳/内容派生，猜不中，所以这条通道与真读一遍等价。
        // 存在的意义：跨轮/跨文件批量改时账本没有记录，但模型手里已经有版本信息，
        // 就不必为了满足门禁再花一次往返重读（trace 里 READ_REQUIRED 是 edit_file 第一大失败原因）。
        if (expectedRevision != null && expectedRevision == stat.revision) return
        val hint = JSONObject()
            .put("path", stat.path)
            .put("required_tool", "read_file")
            .put("revision", stat.revision)
        if (fileIndex != null) hint.put("file_index", fileIndex).put("files_total", fileCount)
        throw FileToolException(
            "READ_REQUIRED",
            "编辑前先 read_file 读取该文件；若已确认内容就是当前版本，" +
                "可直接传 expected_revision=${stat.revision} 免除重读：${stat.path}",
            hint,
        )
    }

    private fun readWholeBytesOrNull(resolved: String): ByteArray? = runCatching {
        val stat = backend.stat(resolved)
        if (stat.kind != FileToolKind.FILE || stat.sizeBytes > MAX_EDIT_BYTES) return null
        val buffer = ByteArrayOutputStream(stat.sizeBytes.toInt())
        var offset = 0L
        while (offset < stat.sizeBytes) {
            backend.checkActive()
            val bytes = backend.readBytes(resolved, offset, minOf(16_000L, stat.sizeBytes - offset).toInt())
            if (bytes.isEmpty()) break
            buffer.write(bytes)
            offset += bytes.size
        }
        buffer.toByteArray()
    }.getOrNull()

    fun listFileBackups(path: String, limit: Int = 20): String = fileOp("list_file_backups", path) {
        val resolved = backend.resolve(path)
        val store = backups
        if (store == null) {
            return@fileOp base("list_file_backups").put("path", resolved).put("available", false)
                .put("backups", JSONArray()).put("count", 0)
        }
        val entries = store.list(backend.environment, backend.identity, resolved, limit.coerceIn(1, 50))
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        base("list_file_backups").put("path", resolved).put("available", true)
            .put("backups", array).put("count", array.length())
    }

    /** 恢复最近一次（或指定）工具写入前的原文；恢复本身也会被记入文件变更账本。 */
    fun restoreFile(
        path: String,
        backupId: String? = null,
        expectedRevision: String? = null,
    ): String = fileOp("restore_file", path) {
        val store = backups ?: throw FileToolException(
            "BACKUP_UNAVAILABLE",
            "本次运行没有可用的文件备份存储；无法恢复",
        )
        val stat = runCatching { backend.stat(backend.resolve(path)) }.getOrNull()
        val resolved = stat?.path ?: backend.resolve(path)
        if (stat != null && stat.kind != FileToolKind.FILE) {
            throw FileToolException("NOT_REGULAR_FILE", "目标不是普通文件")
        }
        if (expectedRevision != null) {
            if (stat == null) throw FileToolException("FILE_NOT_FOUND", "目标文件不存在，不能按 revision 恢复")
            if (stat.revision != expectedRevision) throw FileToolException("FILE_CHANGED", "文件已变化，请重新读取")
        }
        val entries = store.list(backend.environment, backend.identity, resolved, 50)
        val entry = if (backupId.isNullOrBlank()) {
            entries.firstOrNull()
        } else {
            entries.firstOrNull { it.id == backupId }
        } ?: throw FileToolException(
            "BACKUP_NOT_FOUND",
            if (backupId.isNullOrBlank()) "没有找到该文件的工具备份" else "没有找到指定的备份：$backupId",
        )
        val bytes = store.load(entry.id) ?: throw FileToolException("BACKUP_NOT_FOUND", "备份内容不可读")
        val previousBytes = if (stat != null) readWholeBytesOrNull(resolved) else null
        val previousText = previousBytes?.toString(Charsets.UTF_8)
        val restoredText = bytes.toString(Charsets.UTF_8)
        val result = backend.write(resolved, bytes, append = false, expectedRevision = stat?.revision)
        ledger?.markRead(backend.environment, backend.identity, result.stat.path)
        val delta = previousText?.let { FileChangeSummary.between(it, restoredText) }
        base("restore_file").put("path", result.stat.path).put("revision", result.stat.revision)
            .put("restored_from", entry.id).put("backup_path", entry.path)
            .put("bytes_written", bytes.size).put("created", stat == null)
            .apply {
                if (delta != null) {
                    put("lines_added", delta.added).put("lines_removed", delta.removed)
                    put("diff", delta.diff).put("diff_truncated", delta.truncated)
                } else {
                    put("lines_added", 0).put("lines_removed", 0)
                }
            }
    }

    fun projectInfo(path: String): String = FileToolProjectInfo(backend).inspect(path)

    private fun resolveEdits(
        oldText: String?,
        newText: String?,
        replaceAll: Boolean,
        edits: List<FileEdit>,
    ): List<FileEdit> {
        if (edits.isNotEmpty()) {
            if (oldText != null || newText != null) {
                throw FileToolException("INVALID_ARGUMENT", "edits 数组与 old_text/new_text 只能二选一")
            }
            if (edits.size > MAX_BATCH_EDITS) {
                throw FileToolException("TOO_MANY_EDITS", "单次最多 $MAX_BATCH_EDITS 处编辑；请拆分批次")
            }
            edits.forEachIndexed { index, edit ->
                if (edit.oldText.isEmpty()) {
                    throw FileToolException("EMPTY_MATCH", "edits[$index].old_text 不能为空；创建文件请使用 write_file")
                }
                encodeBounded(edit.oldText)
                encodeBounded(edit.newText)
            }
            return edits
        }
        if (oldText == null || newText == null) {
            throw FileToolException("INVALID_ARGUMENT", "需要 old_text 与 new_text，或 edits 数组")
        }
        if (oldText.isEmpty()) throw FileToolException("EMPTY_MATCH", "old_text 不能为空；创建文件请使用 write_file")
        encodeBounded(oldText)
        encodeBounded(newText)
        return listOf(FileEdit(oldText, newText, replaceAll))
    }

    private fun applyEdits(
        original: String,
        edits: List<FileEdit>,
        fileIndex: Int? = null,
        fileCount: Int = 1,
        filePath: String? = null,
    ): EditOutcome {
        var text = original
        var replacements = 0
        var firstLine = 1
        var lastLineEnd = 1
        var recordedFirst = false
        val details = JSONArray()
        edits.forEachIndexed { index, edit ->
            backend.checkActive()
            var count = 0
            var position = 0
            var firstFound = -1
            while (true) {
                val found = text.indexOf(edit.oldText, position)
                if (found < 0) break
                if (firstFound < 0) firstFound = found
                count++
                if (!edit.replaceAll && count > 1) {
                    val locations = FileToolMatchHint.ambiguous(text, edit.oldText)
                    throw batchFailure(
                        FileToolException(
                            "AMBIGUOUS_MATCH",
                            FileToolMatchHint.summarize(FileToolMatchHint.AMBIGUOUS, locations)
                                .ifBlank { "old_text 命中多处，请提供更多上下文或明确 replace_all=true" },
                            FileToolMatchHint.toJson(FileToolMatchHint.AMBIGUOUS, locations),
                        ),
                        index,
                        edits.size,
                        fileIndex,
                        fileCount,
                        filePath,
                    )
                }
                position = found + edit.oldText.length
            }
            if (count == 0) {
                throw batchFailure(matchNotFound(text, edit.oldText), index, edits.size, fileIndex, fileCount, filePath)
            }
            val line = FileChangeSummary.lineOfOffset(text, firstFound)
            val matchedLines = FileChangeSummary.lineCount(edit.oldText).coerceAtLeast(1)
            val replaced = if (edit.replaceAll) count else 1
            text = if (edit.replaceAll) {
                text.replace(edit.oldText, edit.newText)
            } else {
                text.substring(0, firstFound) + edit.newText + text.substring(firstFound + edit.oldText.length)
            }
            if (text.length > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "替换后的文本超过编辑大小限制")
            replacements += replaced
            if (!recordedFirst) {
                firstLine = line
                recordedFirst = true
            }
            lastLineEnd = line + matchedLines - 1
            details.put(
                JSONObject().put("index", index).put("line", line)
                    .put("line_end", line + matchedLines - 1).put("replacements", replaced),
            )
        }
        return EditOutcome(text, replacements, firstLine, lastLineEnd, details)
    }

    /** 批量编辑失败时带上「哪个文件、第几条」，否则模型只知道整批失败，不知道该改哪里。 */
    private fun batchFailure(
        error: FileToolException,
        index: Int,
        total: Int,
        fileIndex: Int?,
        fileCount: Int,
        filePath: String?,
    ): FileToolException {
        if (total <= 1 && fileCount <= 1) return error
        val hint = JSONObject(error.hint?.toString() ?: "{}")
            .put("edit_index", index).put("edits_total", total)
        if (fileIndex != null) hint.put("file_index", fileIndex).put("files_total", fileCount)
        if (filePath != null) hint.put("file_path", filePath)
        return FileToolException(error.code, error.message ?: "编辑失败", hint)
    }

    fun globFiles(path: String, pattern: String, limit: Int = 80, cursor: String? = null, includeHidden: Boolean = false): String =
        FileToolSearch(backend).glob(path, pattern, limit, cursor, includeHidden)

    fun grepFiles(
        path: String,
        query: String,
        glob: String = "**/*",
        limit: Int = 50,
        cursor: String? = null,
        includeHidden: Boolean = false,
        caseSensitive: Boolean = true,
        regex: Boolean = false,
        filesOnly: Boolean = false,
    ): String = FileToolSearch(backend).grep(
        path = path,
        query = query,
        glob = glob,
        limit = limit,
        cursor = cursor,
        includeHidden = includeHidden,
        caseSensitive = caseSensitive,
        regex = regex,
        filesOnly = filesOnly,
    )

    /**
     * 未命中时区分两种最常见原因：只有缩进/空白不同，或只是内容相近。
     * 返回值直接进入工具结果，让模型下次调用就能改对。
     */
    private fun matchNotFound(original: String, oldText: String): FileToolException {
        val whitespace = FileToolMatchHint.whitespaceOnly(original, oldText)
        if (whitespace != null) {
            return FileToolException(
                "MATCH_NOT_FOUND",
                "未找到 old_text（原文存在仅空白或缩进不同的相同文本），文件未修改。",
                FileToolMatchHint.toJson(FileToolMatchHint.WHITESPACE_ONLY, whitespace),
            )
        }
        val similar = FileToolMatchHint.similar(original, oldText)
        if (similar.isNotEmpty()) {
            return FileToolException(
                "MATCH_NOT_FOUND",
                "未找到 old_text，文件未修改；最相近的实际文本在第 ${similar.first().line} 行，请照抄原文。",
                FileToolMatchHint.toJson(FileToolMatchHint.SIMILAR_FOUND, similar),
            )
        }
        return FileToolException("MATCH_NOT_FOUND", "未找到 old_text，文件未修改；请重新读取并使用原文")
    }

    private fun regularFile(path: String, expectedRevision: String?): FileToolStat {
        val stat = backend.stat(backend.resolve(path))
        if (stat.kind != FileToolKind.FILE) throw FileToolException("NOT_REGULAR_FILE", "目标不是普通文件")
        if (expectedRevision != null && stat.revision != expectedRevision) throw FileToolException("FILE_CHANGED", "文件已变化，请重新读取")
        return stat
    }

    private fun verifyUnchanged(stat: FileToolStat) {
        if (backend.stat(stat.path).revision != stat.revision) throw FileToolException("FILE_CHANGED", "文件在操作期间已变化，请重试")
    }

    /**
     * 读完整个文件；批量编辑必须先拿到完整原文，才能保证「要么全改、要么不改」。
     *
     * 必须按**原始字节**整块读取、最后只解码一次。逐块解码时 16 KiB 边界经常落在
     * 汉字（3 字节）或 emoji（4 字节）中间：只要哪一次把「半个字符」当成已消费，
     * 下一次读取就从字符中间开始，整文件读取会被误报成「文件不是有效 UTF-8」。
     * 整文件读根本没有边界问题，sha256 也直接基于原始字节，不必重编码。
     *
     * 块大小取 16 KiB 是因为本机文件后端的单次读取上限不同（LocalFileToolBackend
     * 只允许 16 KiB），保持两边一致才不会出现「换身份就换行为」。
     */
    private fun readWholeTextBytes(stat: FileToolStat): ByteArray {
        val buffer = ByteArrayOutputStream(stat.sizeBytes.toInt().coerceAtLeast(0))
        var offset = 0L
        while (offset < stat.sizeBytes) {
            backend.checkActive()
            val limit = minOf(16_000L, stat.sizeBytes - offset).toInt()
            val bytes = backend.readBytes(stat.path, offset, limit)
            if (bytes.isEmpty()) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化（偏移 $offset）")
            buffer.write(bytes)
            offset += bytes.size
        }
        val raw = buffer.toByteArray()
        if (raw.any { it == 0.toByte() }) {
            throw FileToolException("BINARY_FILE", "文件包含二进制内容，请使用文件导出或终端工具处理")
        }
        return raw
    }

    /** 整文件严格解码：失败就是编码问题，与游标无关，别让模型再去试 offset。 */
    private fun decodeWholeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw FileToolException(
            "NOT_UTF8_TEXT",
            "文件内容不是有效 UTF-8 文本（${bytes.size} 字节）；请先用终端工具确认编码，或在副本上转换后再编辑",
        )
    }

    /** 读完整文本用来说明变更；文件不存在、不是普通文件或超过编辑上限时返回 null。 */
    private fun readWholeTextOrNull(resolved: String): String? = runCatching {
        val stat = backend.stat(resolved)
        if (stat.kind != FileToolKind.FILE || stat.sizeBytes > MAX_EDIT_BYTES) return null
        decodeWholeText(readWholeTextBytes(stat))
    }.getOrNull()

    private fun encodeBounded(content: String): ByteArray {
        if (content.length > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "文本超过 512 KiB 大小限制")
        if (!Charsets.UTF_8.newEncoder().canEncode(content)) throw FileToolException("NOT_UTF8_TEXT", "文本包含无效 Unicode 字符")
        return content.toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "文本超过 512 KiB 大小限制")
        }
    }

    private fun base(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)
        .put("environment", backend.environment).put("identity", backend.identity)

    private data class PreparedFile(
        val stat: FileToolStat,
        val originalBytes: ByteArray,
        val original: String,
        val outcome: EditOutcome,
        val replacement: ByteArray,
        val originalDigest: String,
        val replacementDigest: String,
        val writtenStat: FileToolStat = stat,
        val atomic: Boolean = false,
        val backupId: String? = null,
    )

    private data class RollbackFailure(val path: String, val reason: String) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("reason", reason)
    }

    private class EditOutcome(
        val text: String,
        val replacements: Int,
        val firstLine: Int,
        val lastLineEnd: Int,
        val details: JSONArray,
    )

    private companion object {
        const val MAX_EDIT_BYTES = 512 * 1024
        const val MAX_BATCH_EDITS = 50
        const val MAX_BATCH_FILES = 20
        /**
         * `start_line` 定位的扫描预算。
         *
         * 原来是 256 KiB：真机上 386 KiB / 5,217 行的 `AGENT_RUNTIME.md` 传 `start_line=5148` 时，
         * 扫到一半就用完预算，回执是 `content=""` + `stop_reason=scan_limit`，看起来像「文件是空的」。
         * 1 MiB 能覆盖绝大多数源码与文档；真正超大的文件仍会命中 scan_limit，
         * 那种情况回执里已带 `hint` 与 `resume.offset_bytes`。
         */
        const val MAX_LINE_SCAN_BYTES = 1024 * 1024
        const val MAX_DIRECTORY_JSON_CHARS = 16_000
    }
}

internal fun FileToolStat.toJson(): JSONObject = JSONObject().put("path", path).put("name", name)
    .put("kind", kind.wireName).put("size_bytes", sizeBytes).put("revision", revision)

internal fun fileToolOperation(
    tool: String,
    environment: String? = null,
    identity: String? = null,
    path: String? = null,
    block: () -> JSONObject,
): String = try {
    block().toString()
} catch (error: FileToolException) {
    fileToolFailure(tool, error.code, error.message, environment, identity, path, error.hint)
} catch (_: NoSuchFileException) {
    fileToolFailure(tool, "FILE_NOT_FOUND", "文件或目录不存在", environment, identity, path, null)
} catch (_: AccessDeniedException) {
    fileToolFailure(tool, "FILE_ACCESS_DENIED", "文件访问未授权", environment, identity, path, null)
} catch (_: SecurityException) {
    fileToolFailure(tool, "FILE_ACCESS_DENIED", "文件访问未授权", environment, identity, path, null)
} catch (_: IllegalArgumentException) {
    fileToolFailure(tool, "INVALID_ARGUMENT", "文件工具参数无效", environment, identity, path, null)
} catch (_: IOException) {
    fileToolFailure(tool, "FILE_IO_ERROR", "文件读写失败，请检查当前授权和文件状态", environment, identity, path, null)
}

/**
 * 失败结果和成功结果用同一套「在哪个环境、什么身份」字段：
 * 以前失败结果完全不带 environment，模型只能凭记忆猜自己是不是选错了环境。
 */
private fun fileToolFailure(
    tool: String,
    code: String,
    message: String?,
    environment: String?,
    identity: String?,
    path: String?,
    hint: Any?,
): String {
    val result = JSONObject().put("ok", false).put("tool", tool).put("code", code).put("message", message)
    if (hint != null) result.put("hint", hint)
    if (environment != null) result.put("environment", environment)
    if (identity != null) result.put("identity", identity)
    if (environment != null && path != null) {
        EnvironmentMismatchHint.noteForPath(environment, path, code)?.let { result.put("environment_note", it) }
    }
    return result.toString()
}
