package io.github.mangi.eta.agent.terminal

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** 游标保留有限深度的目录栈和当前文本位置；每轮只扫描有界条目与字节。 */
internal class FileToolSearch(
    private val backend: FileToolBackend,
    private val nanoTime: () -> Long = System::nanoTime,
    private val softTimeLimitNanos: Long = 5_000_000_000L,
) {
    fun glob(path: String, pattern: String, limit: Int, cursor: String?, includeHidden: Boolean): String =
        search(path, pattern, null, limit, cursor, includeHidden, true)

    fun grep(
        path: String,
        query: String,
        glob: String,
        limit: Int,
        cursor: String?,
        includeHidden: Boolean,
        caseSensitive: Boolean,
        regex: Boolean = false,
        filesOnly: Boolean = false,
    ): String = search(path, glob, query, limit, cursor, includeHidden, caseSensitive, regex, filesOnly)

    private fun search(
        path: String,
        pattern: String,
        query: String?,
        limit: Int,
        cursor: String?,
        includeHidden: Boolean,
        caseSensitive: Boolean,
        regex: Boolean = false,
        filesOnly: Boolean = false,
    ): String = fileToolOperation(
        tool = if (query == null) "glob_files" else "grep_files",
        environment = backend.environment,
        identity = backend.identity,
        path = path,
    ) {
        require(limit > 0)
        if (query != null && (query.isEmpty() || query.length > 512 || query.contains('\n') || query.contains('\r'))) {
            throw FileToolException("INVALID_QUERY", "搜索词必须为 1 到 512 个字符的单行普通文本")
        }
        val compiled = if (query != null && regex) compileRegex(query, caseSensitive) else null
        if (query != null && regex && compiled == null) {
            throw FileToolException("INVALID_QUERY", "正则表达式无效或超出限制，请检查语法（不支持多行模式）")
        }
        val root = backend.resolve(path)
        val rootStat = backend.stat(root)
        if (rootStat.kind != FileToolKind.DIRECTORY) throw FileToolException("NOT_DIRECTORY", "搜索范围必须是目录")
        val matcher = FileToolGlob(pattern)
        val signature = digest(JSONObject().put("tool", if (query == null) "glob_files" else "grep_files")
            .put("root", root).put("pattern", pattern).put("query", query ?: JSONObject.NULL)
            .put("include_hidden", includeHidden).put("case_sensitive", caseSensitive)
            .put("regex", regex).put("files_only", filesOnly)
            .put("environment", backend.environment).put("identity", backend.identity).toString())
        // 预扫描：一条 find 拿到整棵树的条目，替代「每个目录一次 list()」。
        // 不支持的后端返回 null，下面的逻辑与改造前完全一致。
        val walk = backend.walkFiles(root, includeHidden, MAX_WALK_ENTRIES)
        val state = if (cursor.isNullOrBlank()) {
            val frames = mutableListOf<Frame>()
            if (walk == null) frames += Frame("", 0, rootStat.revision)
            State(frames, walkMode = walk != null)
        } else {
            decodeCursor(cursor, signature)
        }
        if (state.walkMode && walk == null) {
            throw FileToolException("INVALID_CURSOR", "当前后端不再支持这种搜索游标，请重新开始搜索")
        }
        state.frames.forEach { frame ->
            if (backend.stat(join(root, frame.relative)).revision != frame.revision) {
                throw FileToolException("STALE_CURSOR", "搜索目录已变化，请重新开始搜索")
            }
        }
        // walk 模式没有目录栈：游标只记「最后一个处理过的条目」，续查靠它在列表里定位。
        // 锚点消失（文件被删、目录被换掉）与旧的目录 revision 变化同等对待：STALE_CURSOR。
        val walkEntries: List<FileToolWalkEntry>? = if (state.walkMode) walk?.entries else null
        var walkIndex = 0
        var walkLast = state.walkLast
        var walkTruncated = state.walkMode && walk?.truncated == true
        if (walkEntries != null) {
            val anchor = state.pending?.relative ?: state.walkLast
            if (anchor != null) {
                val at = walkEntries.indexOfFirst { it.relative == anchor }
                if (at < 0) throw FileToolException("STALE_CURSOR", "搜索目录已变化，请重新开始搜索")
                // pending 已经从游标里单独恢复，walkIndex 必须越过它：否则 pending 读完后
                // 会再从列表里取出同一个文件、从头再读一遍（真机续查时重复输出了同一文件）。
                walkIndex = at + 1
                walkLast = anchor
            }
        }
        val startedAt = nanoTime()
        val timeExpired = { nanoTime() - startedAt >= softTimeLimitNanos }
        val matches = JSONArray()
        val fileIndexes = HashMap<String, Int>()
        val skipped = JSONArray()
        val skippedBefore = state.skipped
        var entryBudget = MAX_ENTRIES
        var byteBudget = MAX_BYTES
        var outputChars = 0
        val maxResults = limit.coerceAtMost(200)
        var stopReason = "complete"
        val reader = FileToolTextReader(backend)
        val directoryPages = mutableMapOf<String, CachedPage>()
        val prefetched = HashMap<String, FileToolBatchRead>()
        // 预取：把「接下来几个文件」用一条命令读回来，省掉每文件一次 su/chroot。
        // 只做「先读」、不动遍历进度——先快照 frames/skipped，读完后原样回滚，
        // 所以何时停、停在哪（游标语义）与不预取时完全一致。
        var prefetchLimit = 2
        fun prefetchAhead(first: Pending) {
            // 必须深拷贝：Frame.offset 是 var，浅拷贝会让预取把真实遍历位置一起推走。
            val framesBefore = state.frames.map { Frame(it.relative, it.offset, it.revision) }
            val skippedBefore = state.skipped
            val skippedLen = skipped.length()
            val batch = mutableListOf(first)
            var peekBudget = entryBudget
            while (batch.size < prefetchLimit && peekBudget > 0 && !timeExpired()) {
                val peek = nextFile(root, state, matcher, includeHidden, peekBudget, skipped, directoryPages, timeExpired)
                peekBudget -= peek.visited
                if (peek.file == null) break
                batch += peek.file
            }
            state.frames.clear()
            state.frames.addAll(framesBefore)
            state.skipped = skippedBefore
            while (skipped.length() > skippedLen) skipped.remove(skipped.length() - 1)
            // 预取也要守字节预算：批量里每个文件最多分到「剩余预算 / 批大小」。
            // 否则预算快耗尽时仍按固定 16 KiB 读满一批（6 个文件 × 16 KiB），
            // 大部分读回来根本不会被消费 —— 在 Android 上就是白花一次 su 的 I/O 和时间。
            val perFile = minOf(16_000, byteBudget / batch.size.coerceAtLeast(1))
            if (perFile < PREFETCH_MIN_BYTES / 2) return
            val readStartedAt = nanoTime()
            val reads = try {
                backend.readCheckedBatch(batch.map { join(root, it.relative) }, 0, perFile)
            } catch (error: FileToolException) {
                if (error.isGlobalFailure()) throw error
                return
            }
            // 用实测代价决定下一批大小：便宜就翻倍，贵到「每文件超过预算 1/4」就退回逐个读。
            val perFileCost = (nanoTime() - readStartedAt) / batch.size.coerceAtLeast(1)
            when {
                perFileCost * 8 < softTimeLimitNanos -> prefetchLimit = minOf(PREFETCH_FILES, prefetchLimit * 2)
                perFileCost * 4 > softTimeLimitNanos -> prefetchLimit = 1
            }
            reads.forEach { prefetched[it.path] = it }
        }
        /**
         * walk 模式的预取：候选列表已在内存，这里只是「往后多看几个匹配文件」。
         * 不推进 walkIndex、不改 entryBudget —— 主循环随后仍会按顺序走到同一个文件，
         * 区别只是内容已经批量读回来了（Android 每条命令一次 su，批量能把往返摊薄）。
         */
        fun prefetchWalkAhead(first: String) {
            val list = walkEntries ?: return
            // 当前候选也要算进来：它同样是「一次文件读取」，漏掉它每个预取周期就多一次单读。
            val batch = mutableListOf(first)
            var peek = walkIndex
            var peekBudget = entryBudget
            while (peek < list.size && batch.size < prefetchLimit && peekBudget > 0) {
                val entry = list[peek]
                peek++
                peekBudget--
                if (entry.relative.count { it == '/' } + 1 > MAX_DEPTH) continue
                if (entry.kind == FileToolKind.SYMLINK) continue
                if (!matcher.matches(entry.relative)) continue
                batch += entry.relative
            }
            if (batch.isEmpty()) return
            // 预取也要守字节预算：批量里每个文件最多分到「剩余预算 / 批大小」。
            // 否则预算快耗尽时仍按固定 16 KiB 读满一批（6 个文件 × 16 KiB），
            // 大部分读回来根本不会被消费 —— 在 Android 上就是白花一次 su 的 I/O 和时间。
            val perFile = minOf(16_000, byteBudget / batch.size.coerceAtLeast(1))
            if (perFile < PREFETCH_MIN_BYTES / 2) return
            val readStartedAt = nanoTime()
            val reads = try {
                backend.readCheckedBatch(batch.map { join(root, it) }, 0, perFile)
            } catch (error: FileToolException) {
                if (error.isGlobalFailure()) throw error
                return
            }
            val perFileCost = (nanoTime() - readStartedAt) / batch.size.coerceAtLeast(1)
            when {
                perFileCost * 8 < softTimeLimitNanos -> prefetchLimit = minOf(PREFETCH_FILES, prefetchLimit * 2)
                perFileCost * 4 > softTimeLimitNanos -> prefetchLimit = 1
            }
            reads.forEach { prefetched[it.path] = it }
        }
        while (matches.length() < maxResults && outputChars < MAX_MATCH_CHARS) {
            backend.checkActive()
            if (timeExpired() && prefetched.isEmpty() && (entryBudget < MAX_ENTRIES || byteBudget < MAX_BYTES)) {
                stopReason = "time_limit"
                break
            }
            if (state.pending == null) {
                if (entryBudget <= 0) { stopReason = "entry_limit"; break }
                if (walkEntries != null) {
                    // 候选已在内存里，这里只做过滤，不再有任何后端往返。
                    var candidate: FileToolWalkEntry? = null
                    while (walkIndex < walkEntries.size && entryBudget > 0) {
                        backend.checkActive()
                        val entry = walkEntries[walkIndex]
                        walkIndex++
                        entryBudget--
                        walkLast = entry.relative
                        // 老遍历一直有这道防线（游标里的路径也走它），walk 不能因为「路径来自 find」就绕过去。
                        validateRelative(entry.relative)
                        // 深度按路径段数近似：旧逻辑是「目录栈到 MAX_DEPTH 就不再深入」，
                        // 这里没有目录栈，只能对超深的文件本身记一次 DEPTH_LIMIT。
                        if (entry.relative.count { it == '/' } + 1 > MAX_DEPTH) {
                            skip(state, skipped, entry.relative, "DEPTH_LIMIT")
                            continue
                        }
                        if (entry.kind == FileToolKind.SYMLINK) {
                            skip(state, skipped, entry.relative, "SYMLINK_NOT_FOLLOWED")
                            continue
                        }
                        if (!matcher.matches(entry.relative)) continue
                        candidate = entry
                        break
                    }
                    state.pending = candidate?.let { Pending(it.relative, 0, 1, "") }
                    if (candidate != null && query != null && prefetched.isEmpty() &&
                        byteBudget >= PREFETCH_MIN_BYTES && (nanoTime() - startedAt) * 2 < softTimeLimitNanos
                    ) {
                        prefetchWalkAhead(candidate.relative)
                    }
                    if (candidate == null) {
                        stopReason = if (walkTruncated) "entry_limit" else "complete"
                        break
                    }
                    continue
                }
                val next = nextFile(root, state, matcher, includeHidden, entryBudget, skipped, directoryPages, timeExpired)
                entryBudget -= next.visited
                state.pending = next.file
                if (next.file == null) {
                    if (state.frames.isNotEmpty()) stopReason = if (next.timeLimited) "time_limit" else "entry_limit"
                    break
                }
                if (query != null && prefetched.isEmpty() && byteBudget >= PREFETCH_MIN_BYTES &&
                    (nanoTime() - startedAt) * 2 < softTimeLimitNanos
                ) {
                    prefetchAhead(next.file)
                }
            }
            val pending = state.pending!!
            if (query == null) {
                val item = JSONObject().put("path", join(root, pending.relative)).put("relative_path", pending.relative)
                val cost = item.toString().length
                if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) { stopReason = "output_limit"; break }
                matches.put(item)
                outputChars += cost
                state.pending = null
                continue
            }
            if (timeExpired() && prefetched.isEmpty() && entryBudget < MAX_ENTRIES) { stopReason = "time_limit"; break }
            if (byteBudget < 4) { stopReason = "byte_limit"; break }
            val filePath = join(root, pending.relative)
            try {
                // 一次往返拿齐「读前元数据 + 内容 + 读后元数据」：stat → dd → stat 三次命令
                // 在 Android 上就是三次 su、Linux 上三次 chroot，5 秒预算里能扫的条目直接差三倍。
                val cached = if (pending.offset == 0L) prefetched.remove(filePath) else null
                val checked = if (cached != null) {
                    cached.checked ?: throw FileToolException(cached.errorCode ?: "FILE_UNREADABLE", "读取文件失败")
                } else {
                    backend.readChecked(filePath, pending.offset, minOf(16_000, byteBudget))
                }
                val stat = checked.statBefore
                // walk 模式的文件没有枚举时的 revision（find 不提供），首次读以读到的元数据为准。
                if (pending.revision.isEmpty()) {
                    pending.revision = stat.revision
                } else if (stat.revision != pending.revision) {
                    throw FileToolException("STALE_CURSOR", "搜索中的文件已变化，请重新开始搜索")
                }
                val chunk = reader.decode(stat, pending.offset, checked.bytes)
                if (chunk.text.isEmpty()) { state.pending = null; continue }
                var position = 0
                var paused = false
                while (position < chunk.text.length) {
                    backend.checkActive()
                    if (position > 0 && timeExpired()) { stopReason = "time_limit"; paused = true; break }
                    val newline = chunk.text.indexOf('\n', position)
                    val end = if (newline >= 0) newline else chunk.text.length
                    val segment = chunk.text.substring(position, end)
                    val combined = pending.tail + segment
                    val found = if (pending.lineMatched) null else findMatch(combined, query, compiled, caseSensitive)
                    if (found != null) {
                        pending.matchedCount++
                        if (filesOnly) {
                            val existing = fileIndexes[pending.relative]
                            if (existing == null) {
                                val item = JSONObject().put("path", filePath)
                                    .put("relative_path", pending.relative)
                                    .put("matches", pending.matchedCount)
                                val cost = item.toString().length + 1
                                if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) {
                                    stopReason = "output_limit"
                                    paused = true
                                    break
                                }
                                matches.put(item)
                                fileIndexes[pending.relative] = matches.length() - 1
                                outputChars += cost
                            } else {
                                matches.getJSONObject(existing).put("matches", pending.matchedCount)
                            }
                        } else {
                            val snippetStart = (found.first - 100).coerceAtLeast(0)
                            val snippetEnd = (found.first + found.second + 100).coerceAtMost(combined.length)
                            val item = JSONObject().put("path", filePath).put("relative_path", pending.relative)
                                .put("line", pending.line).put("text", safeSubstring(combined, snippetStart, snippetEnd))
                                .put("match_offset_bytes", pending.offset - pending.tail.toByteArray(Charsets.UTF_8).size + combined.substring(0, found.first).toByteArray(Charsets.UTF_8).size)
                                .put("line_truncated", pending.tail.isNotEmpty() || snippetStart > 0 || snippetEnd < combined.length || (newline < 0 && !chunk.eof))
                            val cost = item.toString().length
                            if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) {
                                stopReason = "output_limit"
                                paused = true
                                break
                            }
                            matches.put(item)
                            outputChars += cost
                        }
                        pending.lineMatched = true
                    }
                    val consumedEnd = if (newline >= 0) newline + 1 else end
                    val consumedBytes = chunk.text.substring(position, consumedEnd).toByteArray(Charsets.UTF_8).size
                    pending.offset += consumedBytes
                    byteBudget -= consumedBytes
                    position = consumedEnd
                    if (newline >= 0) {
                        pending.line++
                        pending.tail = ""
                        pending.lineMatched = false
                    } else {
                        val tailChars = if (compiled != null) REGEX_TAIL_CHARS else (query.length - 1).coerceAtLeast(0)
                        pending.tail = safeSuffix(combined, tailChars)
                    }
                    if (matches.length() >= maxResults) { stopReason = "result_limit"; paused = true; break }
                }
                if (checked.statAfter.revision != stat.revision) throw FileToolException("STALE_CURSOR", "搜索期间文件已变化，请重新开始搜索")
                if (pending.offset >= stat.sizeBytes) state.pending = null
                if (paused) break
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFailure()) throw error
                skip(state, skipped, pending.relative, error.code)
                state.pending = null
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_UNREADABLE")
                state.pending = null
            } catch (_: SecurityException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_ACCESS_DENIED")
                state.pending = null
            }
        }
        val hasMore = if (walkEntries != null) {
            state.pending != null || walkIndex < walkEntries.size
        } else {
            state.pending != null || state.frames.isNotEmpty()
        }
        // 预扫描本身被截断时，后面的条目根本拿不到：既不能报 complete，也不能给一个永远原地打转的游标。
        val walkCut = walkEntries != null && walkTruncated
        // find 报错说明有目录没进去：列表里看不出缺了什么，但绝不能因此报 complete。
        val walkUnreadable = walk != null && walk.unreadable
        val walkProblem = if (walkUnreadable) walk.problem else null
        if (hasMore && stopReason == "complete") stopReason = "result_limit"
        // 只有「目录进不去」这一种缺口时，stop_reason 原本仍是 complete，而 complete=false：
        // 两个字段自相矛盾，只读 stop_reason 的调用方会把「少了一些文件」当成扫完了。
        if (walkUnreadable && !hasMore && stopReason == "complete") stopReason = "unreadable"
        val result = JSONObject().put("ok", true).put("tool", if (query == null) "glob_files" else "grep_files")
            .put("environment", backend.environment).put("identity", backend.identity).put("path", root)
            .put("regex", regex).put("files_only", filesOnly)
            .put("matches", matches).put("count", matches.length()).put("truncated", hasMore)
            .put("partial", hasMore || state.skipped > 0 || walkCut || walkUnreadable)
            .put("complete", !hasMore && state.skipped == 0 && !walkCut && !walkUnreadable)
            .put("stop_reason", stopReason)
            .put("entries_scanned", MAX_ENTRIES - entryBudget).put("bytes_scanned", MAX_BYTES - byteBudget)
            .put("skipped_files", state.skipped - skippedBefore).put("total_skipped", state.skipped).put("skipped", skipped)
            .put("next_cursor", if (hasMore) encodeCursor(state, signature, walkLast) else JSONObject.NULL)
        // 5 秒是软预算：Linux 要进一次 chroot、Android 要起一次 su，每个文件至少两条命令，
        // 目录稍大就扫不完。不说清这一点，entries_scanned 很容易被读成「目录里只有这么多个东西」。
        val hints = mutableListOf<String>()
        if (walkCut && stopReason != "time_limit") {
            hints += "文件列表在 ${MAX_WALK_ENTRIES} 个条目处被截断，后面的内容没有扫到（不是扫完了）：" +
                "截断发生在排序之前（按 find 的遍历顺序），保留的不是「最前面的 N 个」；" +
                "而且没有游标可续（续查会回到同一批）。请缩小 path，或改用 terminal 里的 rg/grep。"
        }
        if (walkUnreadable) {
            // 即使已经因为时间/结果上限停下，也要说清「还有些目录压根没进去」：
            // 否则续查几轮也补不回来，用户会白费力气。
            hints += "扫描时有目录无法读取（${walkProblem ?: "find 报错"}），这些目录里的文件没有出现在结果中 —— " +
                "这不是扫完了。可用 terminal 里的 find / rg 复核，或换成有权限的 path。"
        }
        if (stopReason == "time_limit") {
            hints += "本轮在 5 秒软预算内只访问了 ${MAX_ENTRIES - entryBudget} 个目录/文件条目就停了，" +
                "后面的内容没有扫到（不是扫完了）：后端每次执行命令都有固定开销" +
                "（Android 走 su、Linux 还要进一次 chroot），目录枚举与文件读取都受它限制，目录稍大单轮能扫的条目就很少。" +
                "用 next_cursor 原样续查，或缩小 path；要快速全目录搜索，用 terminal 里的 rg/grep 更快。"
        }
        if (hints.isNotEmpty()) result.put("hint", hints.joinToString(" "))
        result
    }

    private fun nextFile(root: String, state: State, matcher: FileToolGlob, includeHidden: Boolean, budget: Int, skipped: JSONArray, pages: MutableMap<String, CachedPage>, timeExpired: () -> Boolean): NextFile {
        var visited = 0
        while (state.frames.isNotEmpty() && visited < budget) {
            backend.checkActive()
            // 一次列表返回后至少消费一个条目，避免慢文件系统让同一游标反复停在原地。
            if (visited > 0 && timeExpired()) return NextFile(null, visited, timeLimited = true)
            val frame = state.frames.last()
            val cached = pages[frame.relative]
            if (cached != null && frame.offset == cached.offset + cached.page.entries.size && !cached.page.hasMore) {
                pages.remove(frame.relative)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            val page = try {
                if (cached != null && frame.offset >= cached.offset && frame.offset < cached.offset + cached.page.entries.size) cached
                else CachedPage(frame.offset, backend.list(join(root, frame.relative), frame.offset, minOf(64, budget - visited), frame.revision))
                    .also { pages[frame.relative] = it }
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFailure()) throw error
                skip(state, skipped, frame.relative, error.code)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, frame.relative, "DIRECTORY_UNREADABLE")
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            if (page.page.entries.isEmpty()) { state.frames.removeAt(state.frames.lastIndex); continue }
            val entry = page.page.entries[frame.offset - page.offset]
            frame.offset++
            visited++
            if (!includeHidden && entry.name.startsWith('.')) continue
            val relative = if (frame.relative.isEmpty()) entry.name else "${frame.relative}/${entry.name}"
            validateRelative(relative)
            if (entry.kind == FileToolKind.DIRECTORY) {
                if (state.frames.size >= MAX_DEPTH) { skip(state, skipped, relative, "DEPTH_LIMIT"); continue }
                state.frames += Frame(relative, 0, entry.revision)
            } else if (entry.kind == FileToolKind.FILE && matcher.matches(relative)) {
                return NextFile(Pending(relative, 0, 1, entry.revision), visited)
            } else if (entry.kind == FileToolKind.SYMLINK) {
                skip(state, skipped, relative, "SYMLINK_NOT_FOLLOWED")
            }
        }
        return NextFile(null, visited)
    }

    private fun skip(state: State, skipped: JSONArray, relative: String, reason: String) {
        state.skipped++
        if (skipped.length() < 10) skipped.put(JSONObject().put("relative_path", relative).put("reason", reason))
    }

    private fun encodeCursor(state: State, signature: String, walkLast: String?): String {
        val json = JSONObject().put("version", if (state.walkMode) 2 else 1)
            .put("signature", signature).put("skipped", state.skipped)
        if (state.walkMode) {
            // v2 游标只存断点：预扫描每次重跑（一条命令、毫秒级），不需要把目录栈搬来搬去。
            json.put("walk_last", walkLast ?: JSONObject.NULL)
        } else {
            val frames = JSONArray()
            state.frames.forEach { frames.put(JSONObject().put("path", it.relative).put("offset", it.offset).put("revision", it.revision)) }
            json.put("frames", frames)
        }
        state.pending?.let { json.put("pending", JSONObject().put("path", it.relative).put("offset", it.offset).put("line", it.line)
            .put("revision", it.revision).put("tail", it.tail).put("matched", it.lineMatched)
            .put("matched_count", it.matchedCount)) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_CURSOR_CHARS) throw FileToolException("CURSOR_LIMIT", "搜索路径过深或过长，请选择更小的搜索目录")
        return encoded
    }

    private fun decodeCursor(cursor: String, signature: String): State = try {
        require(cursor.length <= MAX_CURSOR_CHARS)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8))
        val version = json.getInt("version")
        require(version == 1 || version == 2)
        require(json.getString("signature") == signature)
        val walkMode = version == 2
        val frames = mutableListOf<Frame>()
        if (!walkMode) {
            val array = json.getJSONArray("frames")
            require(array.length() <= MAX_DEPTH)
            for (index in 0 until array.length()) {
                val frame = array.getJSONObject(index)
                val relative = frame.getString("path").also(::validateRelative)
                val offset = frame.getInt("offset").also { require(it >= 0) }
                frames += Frame(relative, offset, frame.getString("revision"))
            }
        }
        val walkLast = if (walkMode && !json.isNull("walk_last")) {
            json.getString("walk_last").also(::validateRelative)
        } else null
        val pending = json.optJSONObject("pending")?.let {
            Pending(it.getString("path").also(::validateRelative), it.getLong("offset").also { offset -> require(offset >= 0) },
                it.getLong("line").also { line -> require(line > 0) }, it.getString("revision"),
                it.getString("tail").also { tail -> require(tail.length <= 512) }, it.getBoolean("matched"),
                it.optInt("matched_count", 0).also { count -> require(count >= 0) })
        }
        State(frames, pending, json.getInt("skipped").also { require(it >= 0) }, walkMode, walkLast)
    } catch (_: IllegalArgumentException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效或不属于当前搜索条件")
    } catch (_: org.json.JSONException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效")
    }

    private fun validateRelative(value: String) {
        require(!value.startsWith('/') && value.indexOf('\u0000') < 0 && value.split('/').none { it == ".." || it == "." })
    }

    private fun join(root: String, relative: String): String = if (relative.isEmpty()) root else "${root.trimEnd('/')}/$relative"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun safeSubstring(text: String, start: Int, end: Int): String {
        val safeStart = if (start > 0 && text[start].isLowSurrogate()) start - 1 else start
        val safeEnd = if (end < text.length && end > safeStart && text[end - 1].isHighSurrogate()) end - 1 else end
        return text.substring(safeStart, safeEnd)
    }
    private fun safeSuffix(text: String, count: Int): String = if (count == 0) "" else safeSubstring(text, (text.length - count).coerceAtLeast(0), text.length)

    /** 只接受单行、有界长度的正则；编译失败或含内联模式标志时返回 null 由调用方拒绝。 */
    private fun compileRegex(query: String, caseSensitive: Boolean): Regex? {
        if (query.isEmpty() || query.length > 512) return null
        if (query.contains("\n") || query.contains("\r")) return null
        if (query.contains("(?s)") || query.contains("(?m)") || query.contains("(?i)")) return null
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(query, options) }.getOrNull()
    }

    /** 返回命中起点与长度；正则的空匹配不算命中，避免死循环与噪声结果。 */
    private fun findMatch(text: String, query: String, compiled: Regex?, caseSensitive: Boolean): Pair<Int, Int>? {
        if (compiled != null) {
            val match = compiled.find(text) ?: return null
            val value = match.value
            if (value.isEmpty()) return null
            return match.range.first to value.length
        }
        val index = text.indexOf(query, ignoreCase = !caseSensitive)
        return if (index < 0) null else index to query.length
    }
    private data class Frame(val relative: String, var offset: Int, val revision: String)
    private data class Pending(
        val relative: String,
        var offset: Long,
        var line: Long,
        var revision: String,
        var tail: String = "",
        var lineMatched: Boolean = false,
        var matchedCount: Int = 0,
    )
    private data class State(
        val frames: MutableList<Frame>,
        var pending: Pending? = null,
        var skipped: Int = 0,
        /** v2 游标：候选来自一次 find 预扫描，frames 为空。 */
        val walkMode: Boolean = false,
        var walkLast: String? = null,
    )
    private data class NextFile(val file: Pending?, val visited: Int, val timeLimited: Boolean = false)
    private data class CachedPage(val offset: Int, val page: FileToolDirectoryPage)

    companion object {
        /** 一次预取读取的文件数：太少省不下往返，太多会浪费在「已经命中结果」的目录上。 */
        const val PREFETCH_FILES = 6

        /** 剩余字节预算低于此值时不预取，避免读到用不上的内容。 */
        const val PREFETCH_MIN_BYTES = 8_192

        const val MAX_ENTRIES = 2_000
        /**
         * 一轮最多读多少字节。
         *
         * 曾实测过 512 KiB（fix132 之后「时间还有、字节先满」的情况确实存在），但续查用例
         * 是按 256 KiB 设计的：额度一变，就得把文件造到 700 KB 才能测到「续查不重扫前缀」，
         * 而收益只有「大文件少一次往返」。不值得为此改动契约，先保持 256 KiB。
         */
        const val MAX_BYTES = 256 * 1024
        const val MAX_MATCH_CHARS = 12_000
        const val MAX_CURSOR_CHARS = 16_384
        const val MAX_DEPTH = 24

        /**
         * 一次预扫描最多接受多少个条目。
         *
         * 不能取 MAX_ENTRIES：find 的输出顺序是目录遍历序而不是字典序，先截断再排序会丢掉
         * 「字典序靠前」的文件；所以先尽量拿全、在 App 内排序，再由每轮的 entryBudget 逐条消耗。
         * 注意：超过这个数时，截断仍发生在 find 的遍历顺序上 —— 保留下来的不是「字典序前 2 万」，
         * 只是先遍历到的那一批；因此截断时不给游标（按位置续查会在同一批里原地打转），
         * 只提示缩小 path 或改用 rg。
         * 2 万条短路径约 1 MB，落在 4 MB 的输出预算内。
         */
        const val MAX_WALK_ENTRIES = 20_000

        /** 正则命中长度未知，跨块保留的尾部字符数；文件按 16 KiB 分块读取，足够覆盖常见模式。 */
        private const val REGEX_TAIL_CHARS = 256
    }
}
