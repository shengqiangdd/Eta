package io.github.mangi.eta.agent.terminal

import java.io.File
import java.security.MessageDigest

/** 文件操作在所选命名空间内执行，Linux 的链接不能交给 Android 宿主解析。 */
internal class ShellFileToolBackend(
    override val environment: String,
    override val identity: String,
    private val cwd: String,
    private val home: String,
    private val ensureActive: () -> Unit = {},
    private val execute: (command: String, stdin: ByteArray?, maxOutputBytes: Int) -> OneShotShellResult,
) : FileToolBackend {
    override val maxReadBytes: Int get() = MAX_READ_BYTES

    override fun checkActive() = ensureActive()

    override fun resolve(path: String): String {
        checkActive()
        if ('\u0000' in path || path.length > 4096) fail("INVALID_PATH", "文件路径无效或过长")
        val raw = path.ifEmpty { cwd }
        val absolute = when {
            raw == "~" -> home
            raw.startsWith("~/") -> "$home/${raw.removePrefix("~/")}"
            raw.startsWith('/') -> raw
            else -> "$cwd/$raw"
        }
        // 先让目标命名空间解析链接；提前折叠 link/.. 会改变内核实际访问的目录。
        val result = run("readlink -f -- ${shellQuote(absolute)}", maxOutputBytes = 8192, allowFailure = true)
        return if (result.exitCode == 0) {
            decode(result.output).removeSuffix("\n").takeIf { it.startsWith('/') }
                ?: fail("FILE_METADATA_INVALID", "系统返回的规范路径无效")
        } else absolute
    }

    override fun stat(path: String): FileToolStat {
        val result = run(statScript(shellQuote(path)), maxOutputBytes = MAX_STAT_BYTES)
        return parseStat(path, decode(result.output))
    }

    private fun statScript(quotedPath: String): String {
        val format = STAT_FORMAT
        return """
            if [ -L $quotedPath ]; then printf 'symlink\n'
            elif [ -f $quotedPath ]; then printf 'file\n'
            elif [ -d $quotedPath ]; then printf 'directory\n'
            elif [ -e $quotedPath ]; then printf 'other\n'
            else exit 41; fi
            LC_ALL=C stat -c $format -- $quotedPath
        """.trimIndent()
    }

    private fun parseStat(path: String, output: String): FileToolStat {
        val fields = output.split('\n', limit = 3)
        val kind = FileToolKind.entries.firstOrNull { it.wireName == fields.firstOrNull() }
            ?: fail("FILE_METADATA_INVALID", "无法识别系统返回的文件类型")
        val size = fields.getOrNull(1)?.toLongOrNull()
            ?: fail("FILE_METADATA_INVALID", "无法识别系统返回的文件大小")
        val metadata = fields.getOrNull(2)?.trimEnd('\n').orEmpty()
        val versionFields = metadata.split(':', limit = 6)
        if (size < 0 || versionFields.size != 6 ||
            versionFields.take(2).any { !it.matches(Regex("[0-9]+")) } ||
            versionFields[2].toLongOrNull() != size ||
            versionFields[3].toLongOrNull() == null || versionFields[4].toLongOrNull() == null ||
            versionFields[5].isBlank() || '?' in metadata || '%' in metadata || '\n' in metadata
        ) fail("FILE_METADATA_INVALID", "系统返回的文件版本信息无效")
        return FileToolStat(path, File(path).name.ifEmpty { "/" }, kind, size, digest(metadata.toByteArray()))
    }

    override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
        if (offset < 0 || limit !in 1..MAX_READ_BYTES) fail("INVALID_ARGUMENT", "读取范围超出限制")
        val blockOffset = offset / BLOCK_BYTES
        val prefix = (offset % BLOCK_BYTES).toInt()
        val blocks = (prefix + limit + BLOCK_BYTES - 1) / BLOCK_BYTES
        val quoted = shellQuote(path)
        val result = run(
            "[ -f $quoted ] || exit 42; [ -r $quoted ] || exit 43; " +
                "dd if=$quoted bs=$BLOCK_BYTES skip=$blockOffset count=$blocks",
            maxOutputBytes = blocks * BLOCK_BYTES,
        )
        val from = prefix.coerceAtMost(result.output.size)
        return result.output.copyOfRange(from, (from + limit).coerceAtMost(result.output.size))
    }

    /**
     * 一次 find 拿到整棵树的文件列表。
     *
     * 输出用 \0 分隔（文件名可以含换行），排序交给 App 侧：设备的 sort 不一定支持 -z，
     * 在 App 内排序结果是确定的，不依赖固件版本。
     */
    override fun walkFiles(root: String, includeHidden: Boolean, maxEntries: Int): FileToolWalk? {
        val quoted = shellQuote(root)
        // 类型只能分两条 find 拿：本机（busybox 1.36）实测 `find -printf` 不被支持，
        // toybox 也各家不同，所以不依赖任何 -printf。段标记自带 \x01 且以 NUL 结尾，
        // 路径里不可能出现 NUL，标记不会与真实文件名混淆。
        val script = buildString {
            append("[ -d $quoted ] || exit 41\n")
            append("cd $quoted || exit 41\n")
            append("printf '\\001F\\001\\000'\n")
            // 不能把 find 的 stderr 丢掉：目录进不去时它只写 stderr，结果列表看不出缺口。
            // 也不能只看最后一条 find 的退出码：前一条报错、后一条正常时脚本照样返回 0。
            append("find . -mindepth 1 -type f -print0; s1=\$?\n")
            append("printf '\\001L\\001\\000'\n")
            append("find . -mindepth 1 -type l -print0; s2=\$?\n")
            // find 的 1 = 「有些目录/文件没读到」，属于不完整覆盖（由 stderr 说明）；
            // 其他非零才是真的跑不动，必须让上层当成失败。
            append("[ \"\$s1\" -le 1 ] && [ \"\$s2\" -le 1 ] || exit 1\n")
            append("exit 0\n")
        }
        val result = execute(script, null, MAX_WALK_BYTES)
        if (result.exitCode == 41) fail("FILE_NOT_FOUND", "搜索范围不存在")
        if (result.exitCode != 0) fail("FILE_COMMAND_FAILED", "目录扫描失败（exit=${result.exitCode}）")
        // find 对「进不去的目录」只写 stderr，本身几乎不改变输出内容：不把这条线索带出去，
        // 调用方就会把「少了几个文件」当成完整结果（旧实现会记 DIRECTORY_UNREADABLE）。
        val stderrText = decode(result.stderr).trim()
        val text = decode(result.output)
        val tokens = text.split('\u0000').let { raw ->
            // 输出被截断时最后一段可能是半个路径：丢掉它，别返回一个根本不存在的文件名。
            if (result.outputTruncated && text.isNotEmpty() && !text.endsWith('\u0000')) raw.dropLast(1) else raw
        }
        if (tokens.isNotEmpty() && tokens[0] != MARK_FILES) {
            fail("FILE_COMMAND_FAILED", "目录扫描输出缺少分段标记")
        }
        val entries = ArrayList<FileToolWalkEntry>(tokens.size)
        var truncated = result.outputTruncated
        var links = false
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            index++
            if (token.isEmpty()) continue
            if (token == MARK_FILES) { links = false; continue }
            if (token == MARK_LINKS) { links = true; continue }
            val relative = if (token.startsWith("./")) token.substring(2) else token
            if (relative.isEmpty()) continue
            if (!includeHidden && relative.split('/').any { it.startsWith('.') }) continue
            if (entries.size >= maxEntries) { truncated = true; break }
            entries += FileToolWalkEntry(relative, if (links) FileToolKind.SYMLINK else FileToolKind.FILE)
        }
        // 排序在 App 内做：设备的 sort 不一定支持 -z，自己排结果是确定的，不依赖固件版本。
        entries.sortWith(compareBy { it.relative })
        return FileToolWalk(
            entries = entries,
            truncated = truncated,
            unreadable = stderrText.isNotEmpty(),
            problem = stderrText.lineSequence().firstOrNull { it.isNotBlank() }?.let(::summarizeFindProblem),
        )
    }


    /**
     * 搜索热路径：一次命令完成 stat → 读 → stat。
     * 内容用 base64 包一层，元数据与内容之间就不用猜边界（文件里可以有任意字节）；
     * 输出固定七行：kind、size、metadata、base64、kind、size、metadata（末尾一个换行）。
     * base64 行不含换行符，所以按行切分不会把内容里的 \n 当成分隔。
     */
    override fun readChecked(path: String, offset: Long, limit: Int): FileToolCheckedRead {
        if (offset < 0 || limit !in 1..MAX_READ_BYTES) fail("INVALID_ARGUMENT", "读取范围超出限制")
        val blockOffset = offset / BLOCK_BYTES
        val prefix = (offset % BLOCK_BYTES).toInt()
        val blocks = (prefix + limit + BLOCK_BYTES - 1) / BLOCK_BYTES
        val quoted = shellQuote(path)
        val script = buildString {
            append("eta_file_stat() {\n")
            append(statScript("\"${'$'}1\""))
            append("\n}\n")
            append("eta_file_stat $quoted || exit ${'$'}?\n")
            append("[ -f $quoted ] || exit 42\n")
            append("[ -r $quoted ] || exit 43\n")
            append("dd if=$quoted bs=$BLOCK_BYTES skip=$blockOffset count=$blocks 2>/dev/null | base64 | tr -d '\\n'\n")
            append("printf '\\n'\n")
            append("eta_file_stat $quoted || exit ${'$'}?\n")
        }
        val result = run(
            script,
            maxOutputBytes = blocks * BLOCK_BYTES / 3 * 4 + 2 * MAX_STAT_BYTES + 256,
        )
        val fields = decode(result.output).split('\n')
        if (fields.size != 8 || fields[7].isNotEmpty()) {
            fail("FILE_METADATA_INVALID", "文件读取结果不完整，请重试")
        }
        val before = parseStat(path, fields[0] + "\n" + fields[1] + "\n" + fields[2])
        val after = parseStat(path, fields[4] + "\n" + fields[5] + "\n" + fields[6])
        val raw = if (fields[3].isEmpty()) {
            ByteArray(0)
        } else {
            try {
                java.util.Base64.getDecoder().decode(fields[3])
            } catch (_: IllegalArgumentException) {
                fail("FILE_METADATA_INVALID", "文件读取结果不是有效编码")
            }
        }
        val from = prefix.coerceAtMost(raw.size)
        return FileToolCheckedRead(raw.copyOfRange(from, (from + limit).coerceAtMost(raw.size)), before, after)
    }

    override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
        if (offset < 0 || limit !in 1..200) fail("INVALID_ARGUMENT", "目录分页范围无效")
        val quoted = shellQuote(path)
        // 目录元数据 + 枚举 + 改后元数据合成一条命令：一个目录页从 4 次往返降到 2 次
        // （目录多的树里，这部分开销比读文件还大）。帧格式：
        //   <dir 元数据> \0 <name1>\0<name2>\0 ... \0 <dir 元数据>
        // 名字不可能为空，所以两个连续的 \0 就是“名字列表结束”的标记。
        val script = buildString {
            append("eta_file_stat() {\n")
            append(statScript("\"${'$'}1\""))
            append("\n}\n")
            append("eta_file_stat $quoted || exit ${'$'}?\n")
            append("printf '\\000'\n")
            append("[ -r $quoted ] && [ -x $quoted ] || exit 43\n")
            // find 失败必须显式中断：否则（例如旧固件不认识 -print0）会输出空列表，
            // 被当成“空目录”这种错误的成功结果。
            append("find $quoted -mindepth 1 -maxdepth 1 -print0 || exit ${'$'}?\n")
            append("printf '\\000'\n")
            append("eta_file_stat $quoted || exit ${'$'}?\n")
        }
        val result = run(
            script,
            maxOutputBytes = MAX_DIRECTORY_BYTES + 2 * MAX_STAT_BYTES + 256,
        )
        val parts = decode(result.output).split('\u0000')
        if (parts.size < 3) fail("FILE_METADATA_INVALID", "目录枚举结果不完整，请重试")
        val before = parseStat(path, parts.first())
        if (before.kind != FileToolKind.DIRECTORY) fail("NOT_DIRECTORY", "目标不是目录")
        if (expectedRevision != null && expectedRevision != before.revision) {
            fail("STALE_CURSOR", "目录已变化，请从第一页重新读取")
        }
        if (parseStat(path, parts.last()).revision != before.revision) {
            fail("STALE_CURSOR", "目录枚举期间已变化，请重新读取")
        }
        val nameParts = parts.subList(1, parts.size - 1)
        if (nameParts.isEmpty() || nameParts.last().isNotEmpty()) {
            fail("DIRECTORY_TOO_LARGE", "目录枚举没有正常结束，请缩小目录范围后重试")
        }
        val names = nameParts.dropLast(1)
        if (names.size > MAX_DIRECTORY_ENTRIES) fail("DIRECTORY_TOO_LARGE", "目录条目过多，请缩小范围")
        val sorted = names.sorted()
        val page = statPage(sorted.drop(offset).take(limit), verifyDirectory = path)
        // 分页取完再确认一次目录没变——这次校验也在同一条命令里，不额外起 su。
        if (page.directoryAfter?.revision != before.revision) {
            fail("STALE_CURSOR", "目录读取期间已变化，请重新读取")
        }
        val next = (offset.toLong() + page.entries.size).coerceAtMost(sorted.size.toLong()).toInt()
        return FileToolDirectoryPage(page.entries, next, next < sorted.size, before.revision)
    }

    private class StatPage(val entries: List<FileToolStat>, val directoryAfter: FileToolStat?)

    /**
     * 一页条目的元数据。
     *
     * 这里是目录列举的真正瓶颈：旧实现给**每个条目**起一个 `stat` 进程，本机实测 51 条 ≈ 1.1s。
     * 现在改成一次批量 `stat`，并用 `%F` 让 stat 自己报告类型——连逐条 `[ -d ]` 判断也省掉
     * （mksh 里 51 次函数调用也要 ~360ms）。
     *
     * 输出是纯行帧：每条目三行 `类型 / 大小 / 元数据`，顺序与参数一致。
     * 路径本身不出现在输出里，所以名字带换行也破坏不了帧。
     */
    private fun statPage(paths: List<String>, verifyDirectory: String? = null): StatPage {
        if (paths.isEmpty() && verifyDirectory == null) return StatPage(emptyList(), null)
        val all = paths + listOfNotNull(verifyDirectory)
        val script = buildString {
            // 按字节切块，避免超出 exec 的参数上限；一页通常只有一块。
            val chunk = mutableListOf<String>()
            var chunkBytes = 0
            fun flush() {
                if (chunk.isEmpty()) return
                append("LC_ALL=C stat -c ").append(PAGE_FORMAT).append(" --")
                chunk.forEach { append(' ').append(it) }
                append(" || exit 41\n")
                chunk.clear()
                chunkBytes = 0
            }
            all.forEach { path ->
                val quoted = shellQuote(path)
                if (chunk.isNotEmpty() && chunkBytes + quoted.length > STAT_CHUNK_BYTES) flush()
                chunk.add(quoted)
                chunkBytes += quoted.length + 1
            }
            flush()
        }
        val expected = all.size
        val result = run(
            "sh -s",
            stdin = script.toByteArray(Charsets.UTF_8),
            maxOutputBytes = minOf(expected * MAX_STAT_BYTES, MAX_DIRECTORY_BYTES),
        )
        val lines = decode(result.output).split('\n')
        if (lines.size != expected * 3 + 1 || lines.last().isNotEmpty()) {
            fail("FILE_METADATA_INVALID", "目录条目元数据不完整，请重新读取")
        }
        val stats = all.mapIndexed { index, path ->
            val base = index * 3
            val type = lines[base]
            // %F 在 toybox / busybox / coreutils 上都可用；万一不支持，输出会是字面 "%F"，
            // 那会把所有条目错判成 other，所以这里显式拒绝。
            if (type == "%F") fail("FILE_METADATA_INVALID", "当前系统的 stat 不支持 %F，无法判断文件类型")
            parseStat(path, kindOf(type) + "\n" + lines[base + 1] + "\n" + lines[base + 2])
        }
        return StatPage(stats.take(paths.size), if (verifyDirectory != null) stats.last() else null)
    }

    /** 把 `stat -c %F` 的类型描述归一成线上类型名；三家实现的措辞在特殊文件上不一致，其余一致。 */
    private fun kindOf(type: String): String = when {
        type.startsWith("regular") -> "file"
        type.startsWith("directory") -> "directory"
        type.contains("symbolic link") -> "symlink"
        else -> "other"
    }

    /**
     * 搜索热路径的批量版：一条命令读 N 个文件。
     *
     * 帧格式：每个文件先一行标记 —— `:eta:ok` 或 `:eta:fail`。
     * ok 后面固定 7 行（kind / size / meta / base64 / kind / size / meta），
     * fail 后面固定 1 行退出码（41 不存在 / 42 非普通文件 / 43 无权限）。
     * 单文件失败只在帧里记一笔，**不中断整批**：搜索对单文件失败的语义是跳过并继续。
     */
    override fun readCheckedBatch(paths: List<String>, offset: Long, limit: Int): List<FileToolBatchRead> {
        if (paths.isEmpty()) return emptyList()
        if (offset < 0 || limit !in 1..MAX_READ_BYTES) fail("INVALID_ARGUMENT", "读取范围超出限制")
        val blockOffset = offset / BLOCK_BYTES
        val prefix = (offset % BLOCK_BYTES).toInt()
        val blocks = (prefix + limit + BLOCK_BYTES - 1) / BLOCK_BYTES
        val script = buildString {
            append("eta_file_stat() {\n")
            append(statScript("\"${'$'}1\""))
            append("\n}\n")
            paths.forEach { path ->
                val quoted = shellQuote(path)
                // 读前元数据先拿到变量里：一条命令里只 stat 一次，且失败也只影响这个文件。
                append("if st=${'$'}(eta_file_stat $quoted 2>/dev/null); then\n")
                append("if [ ! -f $quoted ]; then printf ':eta:fail\\n42\\n'\n")
                append("elif [ ! -r $quoted ]; then printf ':eta:fail\\n43\\n'\n")
                append("else printf ':eta:ok\\n%s\\n' \"${'$'}st\"\n")
                append("dd if=$quoted bs=$BLOCK_BYTES skip=$blockOffset count=$blocks 2>/dev/null | base64 | tr -d '\\n'\n")
                append("printf '\\n'\n")
                // 读后元数据拿不到时给一个**必然校验失败**的占位，保证帧的行数不变。
                append("after=${'$'}(eta_file_stat $quoted 2>/dev/null) || after=${'$'}(printf 'other\\n0\\n0:0:0:0:0:?:?')\n")
                append("printf '%s\\n' \"${'$'}after\"\n")
                append("fi\n")
                append("else printf ':eta:fail\\n41\\n'\n")
                append("fi\n")
            }
        }
        val result = run(
            script,
            maxOutputBytes = paths.size * (blocks * BLOCK_BYTES / 3 * 4 + 2 * MAX_STAT_BYTES) + 1024,
        )
        val lines = decode(result.output).split('\n')
        if (lines.last().isNotEmpty()) fail("FILE_METADATA_INVALID", "批量读取结果不完整，请重试")
        var index = 0
        val reads = ArrayList<FileToolBatchRead>(paths.size)
        paths.forEach { path ->
            val marker = lines.getOrNull(index) ?: fail("FILE_METADATA_INVALID", "批量读取结果格式异常")
            index++
            when (marker) {
                ":eta:ok" -> {
                    if (index + 7 > lines.size - 1) fail("FILE_METADATA_INVALID", "批量读取结果不完整，请重试")
                    val beforeFields = lines[index] + "\n" + lines[index + 1] + "\n" + lines[index + 2]
                    val encoded = lines[index + 3]
                    val afterFields = lines[index + 4] + "\n" + lines[index + 5] + "\n" + lines[index + 6]
                    index += 7
                    reads += try {
                        val before = parseStat(path, beforeFields)
                        val after = parseStat(path, afterFields)
                        val raw = if (encoded.isEmpty()) ByteArray(0) else java.util.Base64.getDecoder().decode(encoded)
                        val from = prefix.coerceAtMost(raw.size)
                        FileToolBatchRead(
                            path,
                            FileToolCheckedRead(raw.copyOfRange(from, (from + limit).coerceAtMost(raw.size)), before, after),
                        )
                    } catch (error: FileToolException) {
                        if (error.isGlobalFailure()) throw error
                        FileToolBatchRead(path, errorCode = error.code)
                    } catch (_: IllegalArgumentException) {
                        FileToolBatchRead(path, errorCode = "FILE_METADATA_INVALID")
                    }
                }
                ":eta:fail" -> {
                    val code = lines.getOrNull(index) ?: fail("FILE_METADATA_INVALID", "批量读取结果格式异常")
                    index++
                    reads += FileToolBatchRead(path, errorCode = batchFailureCode(code))
                }
                else -> fail("FILE_METADATA_INVALID", "批量读取结果格式异常")
            }
        }
        if (index != lines.size - 1) fail("FILE_METADATA_INVALID", "批量读取结果格式异常")
        return reads
    }

    private fun batchFailureCode(exitCode: String): String = when (exitCode) {
        "41" -> "FILE_NOT_FOUND"
        "42" -> "NOT_REGULAR_FILE"
        "43" -> "FILE_ACCESS_DENIED"
        else -> "FILE_COMMAND_FAILED"
    }

    override fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String?,
        expectedSha256: String?,
    ): FileToolWriteResult {
        if (bytes.size > MAX_READ_BYTES) fail("FILE_TOO_LARGE", "单次写入内容超过限制")
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
            fail("INVALID_ARGUMENT", "内容版本格式无效")
        }
        val quoted = shellQuote(path)
        val parent = shellQuote(File(path).parent ?: "/")
        expectedRevision?.let {
            if (stat(path).revision != it) fail("FILE_CHANGED", "文件已变化，请重新读取后编辑")
        }
        val hashCheck = expectedSha256?.let {
            "eta_hash=${'$'}(sha256sum < $quoted) || exit 45; " +
                "[ \"${'$'}{eta_hash%% *}\" = ${shellQuote(it)} ] || exit 44; "
        }.orEmpty()
        // 保留既有 inode 的属主、模式和 SELinux 标签；如实报告非原子写入。
        run(
            "mkdir -p -- $parent || exit 43; " +
                "if [ -e $quoted ] && [ ! -f $quoted ]; then exit 42; fi; " +
                hashCheck + "cat ${if (append) ">>" else ">"} $quoted",
            stdin = bytes,
            maxOutputBytes = 8192,
        )
        return FileToolWriteResult(stat(path), atomic = false)
    }

    private fun run(
        command: String,
        stdin: ByteArray? = null,
        maxOutputBytes: Int,
        allowFailure: Boolean = false,
    ): OneShotShellResult {
        checkActive()
        val result = execute(command, stdin, maxOutputBytes)
        checkActive()
        when (result.exitCode) {
            SHELL_STDIN_FAILED_EXIT_CODE -> fail("FILE_INPUT_FAILED", "文件写入内容未能完整传输，请重新读取并确认文件状态")
            -3 -> fail("CANCELLED", "文件操作已取消")
            -2 -> fail("FILE_TIMEOUT", "文件操作超时")
            -1 -> fail("FILE_PROCESS_FAILED", "无法启动文件操作")
            41 -> fail("FILE_NOT_FOUND", "文件不存在或当前身份无法访问")
            42 -> fail("NOT_REGULAR_FILE", "目标不是普通文件")
            43 -> fail("FILE_ACCESS_DENIED", "当前身份没有文件访问权限")
            44 -> fail("FILE_CHANGED", "文件内容已变化，请重新读取后编辑")
            45 -> fail("FILE_HASH_UNAVAILABLE", "当前环境无法验证文件内容版本")
            0 -> Unit
            else -> if (!allowFailure || result.exitCode != 1) fail("FILE_COMMAND_FAILED", "文件操作失败（exit=${result.exitCode}）")
        }
        if (result.outputTruncated) fail("FILE_OUTPUT_LIMIT", "文件操作结果超过预算，请缩小范围")
        return result
    }

    /**
     * find 的错误行是 `find: <path>: <reason>`，而路径可能长到超过 PATH_MAX。
     * 从头截 200 字符会把唯一有用的原因（Permission denied / File name too long）切掉，
     * 提示里只剩一长串目录名 —— 那等于没告诉用户为什么少了文件。
     */
    private fun summarizeFindProblem(line: String): String {
        val text = line.trim()
        if (text.length <= MAX_PROBLEM_CHARS) return text
        val reason = text.substringAfterLast(": ", missingDelimiterValue = "")
        if (reason.isNotEmpty() && reason.length <= MAX_PROBLEM_CHARS) return "…: $reason"
        return "…" + text.takeLast(MAX_PROBLEM_CHARS)
    }

    private fun decode(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        fail("UNSUPPORTED_ENCODING", "文件名或元数据不是有效 UTF-8")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(code: String, message: String): Nothing = throw FileToolException(code, message)

    private companion object {
        const val BLOCK_BYTES = 4096
        const val MAX_READ_BYTES = 512 * 1024
        const val MAX_DIRECTORY_BYTES = 1024 * 1024
        /** 一次全树扫描的输出上限：约能装下十万个短路径；超出就标记 truncated，由调用方告知用户。 */
        const val MAX_WALK_BYTES = 4 * 1024 * 1024
        /** find 错误摘要的长度上限：够装下完整原因，又不至于把一条提示变成一堵墙。 */
        const val MAX_PROBLEM_CHARS = 200
        const val MAX_DIRECTORY_ENTRIES = 20_000
        /** 全树扫描的分段标记：\x01F\x01 / \x01L\x01，自带 NUL 结尾，不会与真实路径混淆。 */
        const val MARK_FILES = "\u0001F\u0001"
        const val MARK_LINKS = "\u0001L\u0001"
        const val MAX_STAT_BYTES = 8192
        const val STAT_CHUNK_BYTES = 32 * 1024
        val STAT_FORMAT = shellQuote("%s\n%d:%i:%s:%Y:%Z:%y:%z")
        val PAGE_FORMAT = shellQuote("%F\n%s\n%d:%i:%s:%Y:%Z:%y:%z")
    }
}
