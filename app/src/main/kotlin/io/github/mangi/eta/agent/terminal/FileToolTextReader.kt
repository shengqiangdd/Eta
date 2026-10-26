package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/** 字节游标只能越过已完整解码的字符，不把半个 UTF-8 字符记为已读。 */
internal class FileToolTextReader(private val backend: FileToolBackend) {
    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = 16_000): FileToolTextChunk =
        decode(stat, offset, backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, 16_000)), maxChars)

    /** 已拿到的字节直接解码；搜索热路径用它复用 [FileToolBackend.readChecked] 的结果，不再多走一次后端。 */
    fun decode(stat: FileToolStat, offset: Long, bytes: ByteArray, maxChars: Int = 16_000): FileToolTextChunk {
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "文件包含二进制内容，请使用文件导出或终端工具处理")
        // 游标落在字符中间：直接看首字节是不是 UTF-8 续字节（0b10xxxxxx）。
        // 不能靠「解码器报错后 input.position() == 0」判断：那个判据在 JVM 成立、在真机不成立——
        // ART 的 UTF-8 解码器会先吃掉这个坏字节再报错（position 变成 1），于是半个字符
        // 被报成「文件不是有效 UTF-8」，真机上永远走不到「游标在字符中间」的提示分支。
        if (offset > 0 && bytes.isNotEmpty() && (bytes[0].toInt() and 0xC0) == 0x80) {
            val start = characterStart(stat, offset)
            throw FileToolException(
                "OFFSET_INSIDE_CHARACTER",
                "字节游标位于字符中间：offset_bytes=$offset 落在某个 UTF-8 字符内部（该字符从第 $start 字节开始），" +
                    "请改用 offset_bytes=$start，或直接使用上次返回的 next_offset_bytes",
                org.json.JSONObject().put("offset_bytes", start).put("requested_offset_bytes", offset),
            )
        }
        val eof = offset + bytes.size >= stat.sizeBytes
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(maxChars.coerceIn(2, 16_000))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, eof)
        // OVERFLOW 与编码无关（输出字符装满了）；以前它被合并进「不是有效 UTF-8」，
        // 于是模型只能换 offset 重试，永远试不出来。
        if (result.isOverflow) {
            throw FileToolException(
                "READ_LIMIT_TOO_SMALL",
                "本段要返回的字符超过输出上限，请减小 max_bytes（UTF-8 一个汉字 3 字节、一个 emoji 4 字节）",
            )
        }
        if (result.isError) {
            // 走到这里说明文件自身的字节有问题（游标落在字符中间已在上面的首字节检查里拦掉）。
            // 不能用 input.position() 当坏字节位置：ART 的解码器会先吃掉坏字节再报错，
            // 报出来的偏移会落到一个本来就合法的字节上。自己扫一遍才算得准。
            val bad = firstInvalidUtf8Index(bytes)
            val at = if (bad >= 0) offset + bad else offset + input.position()
            throw FileToolException(
                "NOT_UTF8_TEXT",
                "文件内容不是有效 UTF-8：偏移 $at 处开始出现无法解码的字节序列（这不是游标问题），" +
                    "请用终端工具确认编码，或在副本上转换后再读",
                org.json.JSONObject().put("offset_bytes", at).put("requested_offset_bytes", offset),
            )
        }
        if (bytes.isNotEmpty() && input.position() == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "字节上限不足以读取一个完整字符，请使用至少 4 字节")
        }
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化")
        output.flip()
        return FileToolTextChunk(output.toString(), input.position(), eof && input.position() == bytes.size)
    }

    /**
     * 第一个真正无法解码的字节位置；没有则返回 -1。
     * 末尾被截断不算错（那是字节上限/游标问题），留给解码器的 underflow 语义。
     */
    private fun firstInvalidUtf8Index(bytes: ByteArray): Int {
        var index = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xFF
            val continuation = when {
                lead < 0x80 -> 0
                lead in 0xC2..0xDF -> 1
                lead in 0xE0..0xEF -> 2
                lead in 0xF0..0xF4 -> 3
                else -> return index
            }
            if (continuation == 0) {
                index += 1
                continue
            }
            if (index + continuation >= bytes.size) return -1
            for (step in 1..continuation) {
                if ((bytes[index + step].toInt() and 0xC0) != 0x80) return index
            }
            index += continuation + 1
        }
        return -1
    }

    /**
     * 游标所在字符的起始字节：往回最多看 3 个字节，跳过 UTF-8 续字节。
     * UTF-8 一个字符最多 4 字节，所以续字节最多 3 个。
     */
    private fun characterStart(stat: FileToolStat, offset: Long): Long {
        val from = (offset - 3).coerceAtLeast(0)
        val back = runCatching { backend.readBytes(stat.path, from, (offset - from).toInt()) }.getOrNull()
            ?: return (offset - 1).coerceAtLeast(0)
        var index = back.size
        while (index > 0 && (back[index - 1].toInt() and 0xC0) == 0x80) index -= 1
        if (index == 0) return (offset - 1).coerceAtLeast(0)
        return from + index - 1
    }
}
