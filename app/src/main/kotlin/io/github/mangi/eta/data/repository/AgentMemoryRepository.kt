package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.agent.memory.AgentMemoryLearnings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
    /** 命中片段所属的最近章节名；分页读取时为空。 */
    val matchedHeadings: List<String> = emptyList(),
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation

    /**
     * 按章节替换正文（保留标题行）。
     *
     * 相比 [ReplaceRange] 用行号定位，章节名对人类与模型都更稳定 —— 插入一行不会让范围漂移。
     */
    data class ReplaceSection(
        override val revision: String,
        val section: String,
        val content: String,
        /**
         * 可选的行号定位。标题重名时（真实 MEMORY.md 里「踩坑记录」「门禁与产物」
         * 都重复出现）按标题会报歧义，UI 传行号可以精确到具体那一节。
         */
        val startLine: Int? = null,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    /**
     * 归档：记忆整理时把旧段落移到这里，但以前代码里根本没有入口，
     * 写进去就再也读不到（只能靠 root 手看文件）。归档只读，不参与 revision 校验。
     */
    private val archiveFile = File(memoryDir, ARCHIVE_FILE_NAME)
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
        section: String? = null,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (!section.isNullOrBlank()) {
            readSection(snapshot, section.trim(), boundedChars)
        } else if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    fun outline(): List<AgentMemorySection> = synchronized(lock) {
        AgentMemoryDocument.sections(snapshotLocked().content)
    }

    /** 读归档文件；不存在或超出安全上限时返回 null（调用方据此报「没有归档」）。 */
    fun readArchive(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
        section: String? = null,
    ): AgentMemoryReadResult? = synchronized(lock) {
        if (!archiveFile.isFile) return null
        val bytes = try {
            archiveFile.readBytes()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆归档文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆归档文件超过 1 MiB 安全上限",
            )
        }
        val snapshot = snapshotOf(bytes.toString(Charsets.UTF_8), bytes)
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (!section.isNullOrBlank()) {
            readSection(snapshot, section.trim(), boundedChars)
        } else if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    /**
     * 由章节名定位唯一章节。
     *
     * 重名时以前会静默取第一节，读写都可能落到用户没打算动的那一段；现在直接报歧义并列出行号。
     */
    private fun resolveSection(sections: List<AgentMemorySection>, name: String): AgentMemorySection {
        val candidates = AgentMemoryDocument.candidates(sections, name)
        if (candidates.size == 1) return candidates.first()
        if (candidates.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_NOT_FOUND",
                message = "没有找到名为「$name」的章节；先调用 memory_get 查看目录",
            )
        }
        val where = candidates.joinToString("、") { "第 ${it.startLine} 行「${it.title}」" }
        throw AgentMemoryException(
            code = "MEMORY_SECTION_AMBIGUOUS",
            message = "有 ${candidates.size} 个章节都匹配「$name」：$where；请用更完整的章节名或行号范围重试（重名会读错/改错段落，所以不自动选第一个）",
        )
    }

    private fun readSection(
        snapshot: AgentMemorySnapshot,
        section: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val target = resolveSection(AgentMemoryDocument.sections(snapshot.content), section)
        val text = target.text
        val bounded = text.take(maxChars)
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = bounded,
            startLine = target.startLine,
            endLine = target.endLine,
            hasMore = text.length > bounded.length,
            matchedLines = target.endLine - target.startLine + 1,
            matchedHeadings = listOf(target.title),
        )
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.ReplaceSection -> replaceSection(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated))
    }

    /**
     * 记录一条工具失败经验到「经验教训」章节。
     *
     * 返回 true 表示确实改写了文件。调用方在工具执行路径上，任何异常都不能影响这次工具结果，
     * 因此顶层 [AgentMemoryRepository.recordLearning] 会把异常收成 false。
     */
    fun recordLearning(
        tool: String,
        code: String,
        message: String,
        date: String,
    ): Boolean = synchronized(lock) {
        val snapshot = snapshotLocked()
        val sections = AgentMemoryDocument.sections(snapshot.content)
        val existing = sections.firstOrNull { it.title == AgentMemoryLearnings.SECTION_TITLE }
        val merged = AgentMemoryLearnings.merge(
            body = existing?.body.orEmpty(),
            tool = tool,
            code = code,
            message = message,
            date = date,
        )
        if (!merged.changed) return@synchronized false
        val updated = if (existing != null) {
            AgentMemoryDocument.replaceSection(snapshot.content, existing, merged.body)
        } else {
            buildString {
                append(snapshot.content.trimEnd('\n'))
                if (isNotEmpty()) append("\n\n")
                append(AgentMemoryLearnings.SECTION_HEADING)
                append("\n\n")
                append(AgentMemoryLearnings.SECTION_NOTE)
                append("\n\n")
                append(merged.body)
                append('\n')
            }
        }
        writeLocked(updated)
        true
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content))
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    /**
     * 章节替换：只改动目标章节覆盖的行，其余内容逐字节保留。
     *
     * 章节目录来自当前快照，不需要调用方提供行号 —— 模型只要给出章节名即可，
     * 中途插入/删除别的段落不会让范围错位。
     */
    private fun replaceSection(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceSection,
    ): String {
        val sections = AgentMemoryDocument.sections(snapshot.content)
        // 带行号时优先按行号定位，避开重名歧义；没带则维持原来的按标题解析。
        val target = mutation.startLine
            ?.let { line -> sections.firstOrNull { it.startLine == line } }
            ?: resolveSection(sections, mutation.section)
        return AgentMemoryDocument.replaceSection(snapshot.content, target, mutation.content)
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take(maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
            matchedHeadings = io.github.mangi.eta.agent.memory.AgentMemoryHeadings.collect(lines, matched),
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val ARCHIVE_FILE_NAME = "MEMORY-ARCHIVE.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
        section: String? = null,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars, section)
    }

    /** 读归档记忆（只读）；没有归档文件时返回 null。 */
    fun readArchive(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
        section: String? = null,
    ): AgentMemoryReadResult? {
        ensureInitialized()
        return store.readArchive(query, startLine, maxChars, section)
    }

    /** 记忆目录：标题、行号与字符数，供界面与模型定位。 */
    fun outline(): List<AgentMemorySection> {
        ensureInitialized()
        return store.outline()
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    /**
     * 工具失败经验入库（同步、尽力而为）。
     *
     * 为什么吞异常：这是工具执行链路里的旁路记账，磁盘满/文件损坏都不该让一次正常调用失败。
     */
    fun recordLearning(
        tool: String,
        code: String,
        message: String,
        date: String,
    ): Boolean {
        if (!::store.isInitialized) return false
        return runCatching { store.recordLearning(tool, code, message, date) }.getOrDefault(false)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
