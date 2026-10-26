package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话「完整历史」导出：JSON Lines，逐块流式写。
 *
 * 为什么不是 Markdown：工具调用的完整参数、结果、思考过程只存在于
 * `conversation_context_checkpoints.journal_json`（内联或分块在 `agent_text_chunks`），
 * 而 `messages.tools_json` 在真机全库都是 `[]`。单个大会话的 journal 有 3100 万字符、
 * 1898 个 16 KB 分块；把它还原成 String 再写文件需要 60 MB 以上连续堆，实测会 OOM，
 * 所以这里**只按块搬运**：读 N 块、写 N 行、丢掉，峰值内存与文件大小无关。
 *
 * 格式（每行一个 JSON 对象）：
 * - `{"kind":"meta",...}`：格式版本、会话 id/标题、消息数、journal 块数与字符数
 * - `{"kind":"message",...}`：一条消息的元数据 + 其 content/images 的块数与字符数
 * - `{"kind":"content"|"images","messageId":...,"index":k,"text":"..."}`：正文分块
 * - `{"kind":"journal","index":k,"text":"..."}`：journal 分块，按 index 顺序拼起来就是原 JSON
 * - `{"kind":"end",...}`：正常结束标记。**没有这一行就说明导出被中断**（文件是半截的）。
 */
internal object ConversationHistoryExporter {
    const val FORMAT = "eta.chatlog"
    const val VERSION = 1
    const val MIME_TYPE = "application/x-ndjson"
    const val FILE_EXTENSION = "jsonl"

    /** 一次读多少条消息、多少块文本。块 16 KB 量级，24 块 ≈ 384 KB 峰值。 */
    const val MESSAGE_PAGE = 100
    const val CHUNK_PAGE = 24

    private const val OWNER_CHECKPOINT = "conversation_context_checkpoints"
    private const val OWNER_MESSAGE = "conversation_messages"
    private const val FIELD_JOURNAL = "journal"
    private const val FIELD_CONTENT = "content"
    private const val FIELD_IMAGES = "images"
    private const val REFERENCE_PREFIX = "@eta:chunks:v1:"

    private val WHITESPACE_RUNS = Regex("\\s+")
    private val FILENAME_UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|]")
    private const val MAX_FILENAME_TITLE_CHARS = 40
    private const val FILENAME_TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

    data class Summary(
        val messageCount: Int,
        val journalChunks: Int,
        val journalChars: Int,
        val bytes: Long,
    )

    /** 一段被存储文本的规模。内联存的是原文本身，分块存的是 `@eta:chunks:v1:块数:长度`。 */
    data class ChunkSpan(val chunks: Int, val chars: Int)

    fun describeStored(stored: String?): ChunkSpan {
        if (stored.isNullOrEmpty()) return ChunkSpan(0, 0)
        if (!stored.startsWith(REFERENCE_PREFIX)) return ChunkSpan(1, stored.length)
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        if (parts.size != 2) return ChunkSpan(0, 0)
        return ChunkSpan(
            chunks = parts[0].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            chars = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    suspend fun export(
        context: Context,
        conversationId: String,
        title: String,
        output: OutputStream,
        exportedAt: Long = System.currentTimeMillis(),
    ): Summary = withContext(Dispatchers.IO) {
        val dao = EtaDatabase.get(context.applicationContext).conversationDao()
        val counting = CountingOutputStream(output)
        val writer = BufferedWriter(OutputStreamWriter(counting, Charsets.UTF_8), 64 * 1024)
        try {
            val written = writeHistory(
                source = DaoHistorySource(dao),
                conversationId = conversationId,
                title = title,
                exportedAt = exportedAt,
                out = writer,
            )
            writer.flush()
            written.copy(bytes = counting.count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // 写失败不删用户选的文件：留下半截也比默默丢掉强，靠「没有 end 行」可判定。
            AndroidAgentLogger.error(
                "conversation history export failed: conversation=$conversationId " +
                    "type=${failure::class.simpleName}",
            )
            throw failure
        } finally {
            runCatching { writer.flush() }
        }
    }

    fun defaultFileName(
        title: String,
        fallback: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val sanitized = title
            .replace(WHITESPACE_RUNS, " ")
            .replace(FILENAME_UNSAFE_CHARS, "")
            .trim()
            .take(MAX_FILENAME_TITLE_CHARS)
            .trim()
            .ifBlank { fallback }
        val timestamp = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US).format(Date(nowMillis))
        return "Eta-$sanitized-$timestamp.$FILE_EXTENSION"
    }

    /** 纯逻辑部分：不碰 Android，可用假数据源做 JVM 测试。 */
    internal suspend fun writeHistory(
        source: HistorySource,
        conversationId: String,
        title: String,
        exportedAt: Long,
        out: Writer,
    ): Summary {
        val messageCount = source.messageCount(conversationId)
        val journalRef = source.journalRef(conversationId)
        val journalSpan = describeStored(journalRef)

        out.write("{\"kind\":\"meta\",\"format\":\"")
        out.write(FORMAT)
        out.write("\",\"version\":")
        out.write(VERSION.toString())
        out.write(",\"exportedAt\":")
        out.write(exportedAt.toString())
        out.write(",\"conversationId\":")
        writeJsonString(out, conversationId)
        out.write(",\"title\":")
        writeJsonString(out, title)
        out.write(",\"messageCount\":")
        out.write(messageCount.toString())
        out.write(",\"journal\":{\"chunks\":")
        out.write(journalSpan.chunks.toString())
        out.write(",\"chars\":")
        out.write(journalSpan.chars.toString())
        out.write("}}\n")

        var writtenMessages = 0
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = source.messages(conversationId, offset, MESSAGE_PAGE)
            if (page.isEmpty()) break
            for (message in page) {
                writeMessage(source, message, out)
                writtenMessages++
            }
            offset += page.size
            if (page.size < MESSAGE_PAGE) break
        }

        var journalChunks = 0
        var chunkIndex = 0
        while (journalChunks < journalSpan.chunks) {
            currentCoroutineContext().ensureActive()
            val chunks = source.chunks(
                ownerTable = OWNER_CHECKPOINT,
                ownerId = conversationId,
                field = FIELD_JOURNAL,
                ref = journalRef.orEmpty(),
                from = chunkIndex,
                limit = CHUNK_PAGE,
            )
            if (chunks.isEmpty()) break
            for (text in chunks) {
                out.write("{\"kind\":\"journal\",\"index\":")
                out.write(chunkIndex.toString())
                out.write(",\"text\":")
                writeJsonString(out, text)
                out.write("}\n")
                chunkIndex++
                journalChunks++
            }
            if (chunks.size < CHUNK_PAGE) break
        }

        out.write("{\"kind\":\"end\",\"messages\":")
        out.write(writtenMessages.toString())
        out.write(",\"journalChunks\":")
        out.write(journalChunks.toString())
        out.write(",\"journalChars\":")
        out.write(journalSpan.chars.toString())
        out.write("}\n")

        return Summary(
            messageCount = writtenMessages,
            journalChunks = journalChunks,
            journalChars = journalSpan.chars,
            bytes = 0,
        )
    }

    private suspend fun writeMessage(source: HistorySource, message: HistoryMessage, out: Writer) {
        val content = describeStored(message.contentRef)
        val images = describeStored(message.imagesRef)
        out.write("{\"kind\":\"message\",\"id\":")
        writeJsonString(out, message.id)
        out.write(",\"sortIndex\":")
        out.write(message.sortIndex.toString())
        out.write(",\"type\":")
        writeJsonString(out, message.type)
        out.write(",\"edited\":")
        out.write(if (message.isEdited) "true" else "false")
        out.write(",\"content\":{\"chunks\":")
        out.write(content.chunks.toString())
        out.write(",\"chars\":")
        out.write(content.chars.toString())
        out.write("},\"images\":{\"chunks\":")
        out.write(images.chunks.toString())
        out.write(",\"chars\":")
        out.write(images.chars.toString())
        out.write("}")
        if (message.toolName != null || message.toolStatus != null) {
            out.write(",\"tool\":{\"name\":")
            writeJsonStringOrNull(out, message.toolName)
            out.write(",\"status\":")
            writeJsonStringOrNull(out, message.toolStatus)
            out.write(",\"arguments\":")
            writeJsonStringOrNull(out, message.toolArguments)
            out.write(",\"result\":")
            writeJsonStringOrNull(out, message.toolResult)
            out.write("}")
        }
        out.write("}\n")

        writeChunks(
            source = source,
            ownerTable = OWNER_MESSAGE,
            ownerId = message.id,
            field = FIELD_CONTENT,
            ref = message.contentRef,
            kind = "content",
            out = out,
        )
        writeChunks(
            source = source,
            ownerTable = OWNER_MESSAGE,
            ownerId = message.id,
            field = FIELD_IMAGES,
            ref = message.imagesRef,
            kind = "images",
            out = out,
        )
    }

    private suspend fun writeChunks(
        source: HistorySource,
        ownerTable: String,
        ownerId: String,
        field: String,
        ref: String,
        kind: String,
        out: Writer,
    ) {
        val span = describeStored(ref)
        var index = 0
        while (index < span.chunks) {
            currentCoroutineContext().ensureActive()
            val chunks = source.chunks(ownerTable, ownerId, field, ref, index, CHUNK_PAGE)
            if (chunks.isEmpty()) return
            for (text in chunks) {
                out.write("{\"kind\":\"")
                out.write(kind)
                out.write("\",\"messageId\":")
                writeJsonString(out, ownerId)
                out.write(",\"index\":")
                out.write(index.toString())
                out.write(",\"text\":")
                writeJsonString(out, text)
                out.write("}\n")
                index++
            }
            if (chunks.size < CHUNK_PAGE) return
        }
    }

    private fun writeJsonStringOrNull(out: Writer, value: String?) {
        if (value == null) out.write("null") else writeJsonString(out, value)
    }

    /**
     * 直接写转义结果，不先拼成新字符串：单个分块 16 KB，逃逸后可能更大，
     * 逐字符写只占一个固定缓冲。
     *
     * 孤立代理项（不成对的 UTF-16 半个字符）按 `\uXXXX` 逃逸：交给 CharsetEncoder
     * 会被替换成 `?`，那是真的丢数据。
     */
    internal fun writeJsonString(out: Writer, text: String) {
        out.write("\"")
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' -> out.write("\\\"")
                ch == '\\' -> out.write("\\\\")
                ch == '\n' -> out.write("\\n")
                ch == '\r' -> out.write("\\r")
                ch == '\t' -> out.write("\\t")
                ch == '\b' -> out.write("\\b")
                ch == '\u000C' -> out.write("\\f")
                ch < ' ' -> writeUnicodeEscape(out, ch.code)
                ch.isHighSurrogate() -> {
                    val next = text.getOrNull(index + 1)
                    if (next != null && next.isLowSurrogate()) {
                        out.write(ch.code)
                        out.write(next.code)
                        index++
                    } else {
                        writeUnicodeEscape(out, ch.code)
                    }
                }
                ch.isLowSurrogate() -> writeUnicodeEscape(out, ch.code)
                else -> out.write(ch.code)
            }
            index++
        }
        out.write("\"")
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun writeUnicodeEscape(out: Writer, code: Int) {
        out.write("\\u")
        out.write(HEX[(code shr 12) and 0xF].code)
        out.write(HEX[(code shr 8) and 0xF].code)
        out.write(HEX[(code shr 4) and 0xF].code)
        out.write(HEX[code and 0xF].code)
    }

    /** 供纯逻辑测试与 DB 适配层共用的最小数据源接口。 */
    internal interface HistorySource {
        suspend fun messageCount(conversationId: String): Int
        suspend fun messages(conversationId: String, offset: Int, limit: Int): List<HistoryMessage>
        suspend fun journalRef(conversationId: String): String?
        suspend fun chunks(
            ownerTable: String,
            ownerId: String,
            field: String,
            ref: String,
            from: Int,
            limit: Int,
        ): List<String>
    }

    internal data class HistoryMessage(
        val id: String,
        val sortIndex: Int,
        val type: String,
        val isEdited: Boolean,
        val contentRef: String,
        val imagesRef: String,
        val toolName: String?,
        val toolStatus: String?,
        val toolArguments: String?,
        val toolResult: String?,
    )

    private class DaoHistorySource(private val dao: ConversationDao) : HistorySource {
        override suspend fun messageCount(conversationId: String): Int =
            dao.messageCount(conversationId)

        override suspend fun messages(
            conversationId: String,
            offset: Int,
            limit: Int,
        ): List<HistoryMessage> = dao.messageRowsPage(conversationId, limit, offset)
            .map { it.toHistoryMessage() }

        override suspend fun journalRef(conversationId: String): String? =
            dao.journalLengthRow(conversationId)?.journalJson

        override suspend fun chunks(
            ownerTable: String,
            ownerId: String,
            field: String,
            ref: String,
            from: Int,
            limit: Int,
        ): List<String> = dao.readStoredChunks(ownerTable, ownerId, field, ref, from, limit)

        private fun ConversationMessageEntity.toHistoryMessage() = HistoryMessage(
            id = id,
            sortIndex = sortIndex,
            type = type,
            isEdited = isEdited,
            contentRef = content,
            imagesRef = imagesJson,
            toolName = toolName,
            toolStatus = toolStatus,
            toolArguments = argumentsSummary,
            toolResult = resultSummary,
        )
    }

    private class CountingOutputStream(target: OutputStream) : FilterOutputStream(target) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
