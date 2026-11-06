package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.backup.StorageFailures
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.SequenceInputStream
import java.math.BigDecimal
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream

/** 沿用 JSON 备份合同，用磁盘暂存大数组，校验与恢复均逐条解码。 */
@OptIn(ExperimentalSerializationApi::class)
internal object EtaBackupJsonStreams {
    const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
    const val CHECKPOINTS = "contextCheckpoints"
    private val recordFields = setOf(CONVERSATIONS, MESSAGES, CHECKPOINTS)
    private val knownFields = EtaBackupDocument.serializer().descriptor.let { descriptor ->
        (0 until descriptor.elementsCount).mapTo(mutableSetOf(), descriptor::getElementName)
    }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    suspend fun export(
        context: Context,
        output: OutputStream,
        maxBytes: Long = MAX_BACKUP_BYTES,
        onPhase: (String) -> Unit = {},
        write: suspend (DocumentWriter) -> EtaBackupSummary,
    ): EtaBackupSummary {
        onPhase("stage_export")
        val directory = createDirectory(context)
        try {
            val file = File(directory, "export.json")
            val summary = file.outputStream().buffered().use { stream ->
                val writer = DocumentWriter(LimitedOutput(stream, maxBytes))
                val result = write(writer)
                writer.finish()
                result
            }
            onPhase("copy_export")
            currentCoroutineContext().ensureActive()
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            return summary
        } finally {
            removeDirectory(directory)
        }
    }

    suspend fun stage(context: Context, input: InputStream, onPhase: (String) -> Unit = {}): StagedDocument {
        val directory = createDirectory(context)
        try {
            val header = File(directory, "header.json")
            val cancellation = currentCoroutineContext()
            // 自动备份是 gzip 写出的，手动导出仍是明文 JSON：按魔数自动识别，
            // 两种文件走同一条导入路径，用户不需要记「这个文件要不要解压」。
            val source = openMaybeGzip(input)
            val checked = PushbackInputStream(LimitedInput(source, MAX_BACKUP_BYTES, cancellation::ensureActive))
            val first = checked.read()
            if (first < 0) throw EtaBackupException("备份文件为空")
            checked.unread(first)
            JsonReader(checked.reader(Charsets.UTF_8)).use { reader ->
                JsonWriter(header.writer(Charsets.UTF_8).buffered()).use { writer ->
                    if (reader.peek() == JsonToken.END_DOCUMENT) throw EtaBackupException("备份文件为空")
                    reader.beginObject()
                    writer.beginObject()
                    val seen = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        cancellation.ensureActive()
                        val name = reader.nextName()
                        if (!seen.add(name)) throw EtaBackupException("备份中存在重复字段")
                        onPhase(if (name in knownFields) "read_$name" else "read_unknown")
                        when {
                            name in recordFields -> {
                                if (reader.peek() != JsonToken.BEGIN_ARRAY) throw EtaBackupException("备份中的记录列表格式无效")
                                JsonWriter(File(directory, "$name.json").writer(Charsets.UTF_8).buffered()).use { records ->
                                    copyValue(reader, records, 0, cancellation::ensureActive)
                                }
                            }
                            name in knownFields -> {
                                writer.name(name)
                                copyValue(reader, writer, 0, cancellation::ensureActive)
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    writer.endObject()
                    if (reader.peek() != JsonToken.END_DOCUMENT) throw EtaBackupException("备份文件末尾存在额外内容")
                }
            }
            onPhase("read_header")
            val document = header.inputStream().use { json.decodeFromStream<EtaBackupDocument>(it) }
            return StagedDocument(directory, document)
        } catch (failure: Throwable) {
            removeDirectory(directory)
            if (failure is CancellationException || failure is EtaBackupException || failure is Error) throw failure
            // 这条路径上的失败只在用户主动导入时发生，频率低；把完整异常链记下来，
            // 否则「导入失败」在日志里只剩一个 type，没法判断是读不了源文件还是写不了缓存。
            AndroidAgentLogger.error("Agent backup stage failed", failure)
            throw stagingFailure(failure)
        }
    }

    /**
     * 暂存失败的真实归因。
     *
     * 原实现把所有非 [EtaBackupException] 的异常都记成「备份文件格式无效」——磁盘满是这条
     * 路径上最常见的失败（导入要把整份备份解压到应用缓存，406 MB 的库就要同量级临时空间），
     * 却会把人引向「换个备份文件试试」这条错路。
     */
    internal fun stagingFailure(failure: Throwable): EtaBackupException = when {
        StorageFailures.isOutOfSpace(failure) ->
            EtaBackupException("存储空间不足：导入要先把备份完整解压到应用缓存，清理空间后重试（数据未改动）", failure)
        StorageFailures.isNotWritable(failure) ->
            EtaBackupException("无法写入应用缓存（目录只读或权限不足），导入未开始，数据未改动", failure)
        else -> EtaBackupException("备份文件格式无效", failure)
    }

    /**
     * gzip 备份与明文 JSON 都认：读前两字节看 1F 8B，命中就解压，否则原样返回。
     *
     * 大小上限必须放在解压之后（[LimitedInput] 包在返回的流上）—— 包在压缩侧的话，
     * 一个几十 MB 的压缩包可以解出 GB 级内容绕过检查。
     */
    internal fun openMaybeGzip(input: InputStream): InputStream {
        val peeked = PushbackInputStream(input, 2)
        val magic = ByteArray(2)
        var read = 0
        while (read < magic.size) {
            val count = peeked.read(magic, read, magic.size - read)
            if (count <= 0) break
            read += count
        }
        if (read > 0) peeked.unread(magic, 0, read)
        val gzip = read == magic.size &&
            magic[0] == 0x1f.toByte() &&
            magic[1] == 0x8b.toByte()
        return if (gzip) GZIPInputStream(peeked) else peeked
    }

    /** 只读文件开头取统计的上限：超过这个量还没见到 stats，就说明该走完整路径。 */
    internal const val STATS_PEEK_LIMIT = 64 * 1024

    /**
     * 只读开局取统计，**同时返回一个能从头再读一遍的流**。
     *
     * 为什么要把流还回去：peek 会把输入读掉一段（gz 还要解压），而导入拿到的流只能读一次。
     * 第一版只返回统计，结果「旧备份（统计不在开头）」在 peek 失败后接着 stage，
     * 解析从被读过的中间开始，真机直接报 MalformedJsonException——**预览全线坏掉**。
     * 现在把 peek 期间底层真正读过的字节全缓存下来，再拼上剩余流交给完整路径。
     *
     * 缓存的是压缩后的原始字节，量级与 [STATS_PEEK_LIMIT] 相当（几十 KB），
     * 不是整份备份。
     */
    internal fun peekOrReplay(input: InputStream): Pair<EtaBackupStats?, InputStream> {
        val cached = ByteArrayOutputStream()
        var capturing = true
        val recording = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also {
                if (capturing && it >= 0 && cached.size() < STATS_PEEK_LIMIT) cached.write(it)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also {
                    if (capturing && it > 0 && cached.size() < STATS_PEEK_LIMIT) cached.write(buffer, offset, it)
                }
        }
        val stats = try {
            val stream = openMaybeGzip(recording)
            val buffer = ByteArray(STATS_PEEK_LIMIT)
            var total = 0
            while (total < buffer.size) {
                val count = stream.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            statsFromPrefix(String(buffer, 0, total, Charsets.UTF_8))
        } catch (failure: Throwable) {
            // 不是 Eta 备份、开头就坏了：交给完整路径去看真正的错误。
            null
        }
        // 关键：peek 结束后必须停掉捕获，否则 stage 接着把 93 MB 压缩数据全录进内存，
        // 真机上直接把堆顶到 201 MB 并 OOM（已踩）。
        capturing = false
        val replay = SequenceInputStream(ByteArrayInputStream(cached.toByteArray()), recording)
        return stats to replay
    }

    /** 从文本前缀里找 `"stats":{...}`；供 [peekStats] 与单测共用。 */
    internal fun statsFromPrefix(text: String): EtaBackupStats? {
        val key = "\"stats\":"
        val keyIndex = text.indexOf(key)
        if (keyIndex < 0) return null
        val start = text.indexOf('{', keyIndex + key.length)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        val raw = text.substring(start, end + 1)
        val stats = runCatching { json.decodeFromString(EtaBackupStats.serializer(), raw) }.getOrNull()
            ?: return null
        // 数字不全（fix130 那种只写了对话/消息两项的备份）就当没拿到，
        // 否则界面上会凭空出现「0 个提供商」。
        return stats.takeIf {
            it.providerCount != null && it.modelCount != null &&
                it.memoryBytes != null && it.characterCount != null
        }
    }

    class StagedDocument(private val directory: File, val header: EtaBackupDocument) : AutoCloseable {
        suspend fun conversations(action: suspend (ConversationEntity) -> Unit) = records(CONVERSATIONS, action)
        suspend fun messages(action: suspend (ConversationMessageEntity) -> Unit) = records(MESSAGES, action)
        suspend fun checkpoints(action: suspend (ConversationContextCheckpointEntity) -> Unit) = records(CHECKPOINTS, action)

        private suspend inline fun <reified T> records(field: String, noinline action: suspend (T) -> Unit) {
            val file = File(directory, "$field.json")
            if (!file.exists()) return
            file.inputStream().buffered().use { input ->
                for (row in json.decodeToSequence<T>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                    currentCoroutineContext().ensureActive()
                    action(row)
                }
            }
        }

        override fun close() = removeDirectory(directory)
    }

    class DocumentWriter(private val output: OutputStream) {
        private var firstField = true
        private var firstRecord = true

        init { literal("{") }

        fun <T> value(name: String, serializer: SerializationStrategy<T>, value: T) {
            field(name)
            json.encodeToStream(serializer, value, output)
        }

        fun beginArray(name: String) {
            field(name)
            literal("[")
            firstRecord = true
        }

        fun <T> record(serializer: SerializationStrategy<T>, value: T) {
            if (!firstRecord) literal(",")
            firstRecord = false
            json.encodeToStream(serializer, value, output)
        }

        fun endArray() = literal("]")
        fun finish() = literal("}")

        private fun field(name: String) {
            if (!firstField) literal(",")
            firstField = false
            json.encodeToStream(name, output)
            literal(":")
        }

        private fun literal(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
    }

    internal class LimitedInput(
        input: InputStream,
        private val limit: Long,
        private val checkCancelled: () -> Unit = {},
    ) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            checkCancelled()
            return `in`.read().also { if (it >= 0) count(1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return `in`.read(buffer, offset, length).also { if (it > 0) count(it) }
        }
        // 输入流由调用方持有，暂存读取结束不提前关闭调用方资源。
        override fun close() = Unit
        private fun count(bytes: Int) {
            total += bytes
            if (total > limit) throw sizeLimitFailure(limit)
        }
    }

    private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var total = 0L
        override fun write(value: Int) { count(1); out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { count(length); out.write(buffer, offset, length) }
        private fun count(bytes: Int) {
            total += bytes
            if (total > limit) throw sizeLimitFailure(limit)
        }
    }

    private fun sizeLimitFailure(limit: Long): EtaBackupException {
        val size = if (limit >= 1024 * 1024) "${limit / (1024 * 1024)} MiB" else "$limit 字节"
        return EtaBackupException("备份文件超过 $size 限制")
    }

    private fun copyValue(reader: JsonReader, writer: JsonWriter, depth: Int, checkCancelled: () -> Unit) {
        checkCancelled()
        if (depth > 128) throw EtaBackupException("备份中的 JSON 嵌套过深")
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray(); writer.beginArray()
                while (reader.hasNext()) copyValue(reader, writer, depth + 1, checkCancelled)
                reader.endArray(); writer.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject(); writer.beginObject()
                while (reader.hasNext()) {
                    writer.name(reader.nextName())
                    copyValue(reader, writer, depth + 1, checkCancelled)
                }
                reader.endObject(); writer.endObject()
            }
            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
            else -> throw EtaBackupException("备份文件格式无效")
        }
    }

    private fun createDirectory(context: Context): File {
        val directory = File(context.cacheDir, "eta_backup_${UUID.randomUUID()}")
        // mkdirs() 在目录已存在时返回 false：直接拿它当失败判据，会把「本来就能用」报成不可写。
        if (directory.isDirectory || directory.mkdirs()) return directory
        AndroidAgentLogger.warn("Agent backup staging mkdir failed: cacheDir=${context.cacheDir.absolutePath}")
        throw IOException("Unable to create backup staging directory")
    }

    private fun removeDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            AndroidAgentLogger.warn("Agent backup cleanup failed: type=IOException")
        }
    }
}
