package io.github.mangi.eta.agent.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 整文件读取与「分块边界落在字符中间」的回归测试。
 *
 * 背景（真机故障）：`edit_file` 对 100 KiB 级的中文资源文件报
 * `NOT_UTF8_TEXT`（内容不是有效 UTF-8 文本），
 * 而同样内容的英文文件正常、小文件正常。根因是整文件读取以前按 16 KiB 逐块解码，
 * 一旦某次读取的末尾切在汉字（3 字节）中间，字节游标就可能落在字符内部，
 * 下一次读取从半个字符开始 → 被误判成文件不是 UTF-8。
 *
 * 这些用例分别守住：大文件能读、块边界切在字符中间也能读、
 * 真的把游标指到字符中间时给出可操作的错误（而不是让人反复换 offset）。
 */
class FileToolLargeChineseReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 记录每次 readBytes 的入参与返回长度，失败时能指出是哪一段。 */
    private class LoggingBackend(private val delegate: FileToolBackend) : FileToolBackend by delegate {
        val calls = ArrayList<String>()
        override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
            val bytes = delegate.readBytes(path, offset, limit)
            calls += "offset=$offset limit=$limit returned=${bytes.size}"
            return bytes
        }
    }

    @Test
    fun editsLargeChineseFileAcrossChunkBoundaries() {
        val body = StringBuilder()
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources>\n")
        var index = 0
        while (body.toString().toByteArray(Charsets.UTF_8).size < 100_000) {
            body.append("  <string name=\"k$index\">中文内容测试第 $index 行，包含全角标点（，。）与 emoji 😀</string>\n")
            index += 1
        }
        body.append("<string name=\"tail_marker\">尾部标记</string>\n")
        val file = temporaryFolder.newFile("large-zh.xml").apply { writeText(body.toString()) }
        assertEquals("尾部标记（改）", editTailMarker(file, LoggingBackend(newRealBackend())))
    }

    /**
     * 第 16000 个字节正好落在「中」字的中间（该字从 15999 开始）。
     * 旧实现按请求的字节数推进游标时，第二次读取会从 16000 开始 → 报 NOT_UTF8_TEXT。
     */
    @Test
    fun editsFileWhoseChunkBoundarySplitsACharacter() {
        val body = StringBuilder("x".repeat(15_999))
        body.append("中文测试")
        while (body.toString().toByteArray(Charsets.UTF_8).size < 40_000) body.append("中文内容")
        body.append("\nMARK\n")
        val file = temporaryFolder.newFile("midchar.txt").apply { writeText(body.toString()) }
        val bytes = file.readBytes()
        assertEquals(15_999, byteIndexWhereCharacterStarts(bytes, 16_000))

        val backend = LoggingBackend(newRealBackend())
        val result = JSONObject(
            AgentFileOperations(backend).editFile(
                path = file.absolutePath,
                oldText = "MARK",
                newText = "MARK2",
            )
        )
        assertTrue("失败时附上读取轨迹：$result\n" + backend.calls.joinToString("\n"), result.optBoolean("ok"))
        assertEquals(1, result.getInt("replacements"))
        assertTrue(file.readText().endsWith("MARK2\n"))
    }

    /** 真把游标指到字符中间时，要说明是游标问题，并给出可续读的提示。 */
    @Test
    fun reportsCursorInsideCharacterWithActionableHint() {
        val file = temporaryFolder.newFile("cursor.txt").apply { writeText("中".repeat(20_000)) }
        val result = JSONObject(
            AgentFileOperations(newRealBackend()).readFile(
                path = file.absolutePath,
                offsetBytes = 1,
                maxBytes = 64,
            )
        )
        // 真机回归：这条以前报 NOT_UTF8_TEXT「文件不是有效 UTF-8」。判据不能靠解码器的
        // input.position()：JVM 停在 0，ART 会把坏字节吃掉后变成 1，于是同一段代码在单测里
        // 走对、在手机上永远走不到提示分支。现在直接看首字节是不是续字节，与解码器无关。
        assertEquals("OFFSET_INSIDE_CHARACTER", result.getString("code"))
        assertTrue(result.getString("message").contains("字符中间"))
        val hint = result.getJSONObject("hint")
        assertEquals(0L, hint.getLong("offset_bytes"))
        assertEquals(1L, hint.getLong("requested_offset_bytes"))
    }

    /** 深文件里第 16000 字节切在字符中间：提示要给该字符的起始偏移，而不是再让人换 offset 试。 */
    @Test
    fun reportsCursorInsideCharacterDeepInFile() {
        val file = temporaryFolder.newFile("deep-cursor.txt").apply {
            writeText("x".repeat(15_999) + "中文测试" + "中文内容".repeat(4_000))
        }
        val result = JSONObject(
            AgentFileOperations(newRealBackend()).readFile(
                path = file.absolutePath,
                offsetBytes = 16_000,
                maxBytes = 64,
            )
        )
        assertEquals("OFFSET_INSIDE_CHARACTER", result.getString("code"))
        assertEquals(15_999L, result.getJSONObject("hint").getLong("offset_bytes"))
        assertEquals(16_000L, result.getJSONObject("hint").getLong("requested_offset_bytes"))
        val aligned = JSONObject(
            AgentFileOperations(newRealBackend()).readFile(
                path = file.absolutePath,
                offsetBytes = 15_999,
                maxBytes = 64,
            )
        )
        assertTrue(aligned.getString("content").startsWith("中文测试"))
    }

    /** 坏字节要报「序列开头」：解码器（ART）会先吃掉首字节再报错，不能报到后面那个合法字节上。 */
    @Test
    fun reportsStartOfInvalidSequence() {
        val file = temporaryFolder.newFile("broken.txt").apply {
            writeBytes(byteArrayOf(0x41, 0xE4.toByte(), 0x41, 0x42))
        }
        val result = JSONObject(
            AgentFileOperations(newRealBackend()).readFile(path = file.absolutePath, maxBytes = 64)
        )
        assertEquals("NOT_UTF8_TEXT", result.getString("code"))
        assertEquals(1L, result.getJSONObject("hint").getLong("offset_bytes"))
    }

    /** 输出字符装不下属于「字符上限」问题，不能报成「文件不是 UTF-8」。 */
    @Test
    fun reportsOverflowAsLimitProblem() {
        val file = temporaryFolder.newFile("ascii.txt").apply { writeText("a".repeat(1_000)) }
        val backend = newRealBackend()
        val failure = runCatching {
            FileToolTextReader(backend).read(backend.stat(file.absolutePath), 0, 16_000, maxChars = 4)
        }.exceptionOrNull()
        assertTrue(failure is FileToolException)
        assertEquals("READ_LIMIT_TOO_SMALL", (failure as FileToolException).code)
    }

    private fun editTailMarker(file: File, backend: FileToolBackend): String {
        val result = JSONObject(
            AgentFileOperations(backend).editFile(
                path = file.absolutePath,
                oldText = "<string name=\"tail_marker\">尾部标记</string>",
                newText = "<string name=\"tail_marker\">尾部标记（改）</string>",
            )
        )
        assertTrue(
            "edit_file 失败：$result\n读取轨迹：\n" + (backend as? LoggingBackend)?.calls?.joinToString("\n"),
            result.optBoolean("ok"),
        )
        assertTrue(file.readText().contains("尾部标记（改）"))
        return "尾部标记（改）"
    }

    /** offset 之前最近的字符起始位置：用来断言偏移确实切在字符中间。 */
    private fun byteIndexWhereCharacterStarts(bytes: ByteArray, offset: Int): Int {
        var index = offset
        while (index > 0 && (bytes[index].toInt() and 0xC0) == 0x80) index -= 1
        return index
    }

    /** 与线上一致的执行语义：stdout 按 maxOutputBytes 截断并标记。 */
    private fun newRealBackend(): ShellFileToolBackend =
        ShellFileToolBackend("linux", "user", temporaryFolder.root.absolutePath, temporaryFolder.root.absolutePath) {
                command, input, maxOutputBytes ->
            runShell(command, input, maxOutputBytes)
        }

    private fun runShell(command: String, input: ByteArray?, maxOutputBytes: Int): OneShotShellResult {
        val inputFile = temporaryFolder.newFile().apply { writeBytes(input ?: byteArrayOf()) }
        val process = ProcessBuilder("sh", "-c", command)
            .directory(temporaryFolder.root)
            .redirectInput(inputFile)
            .start()
        val raw = process.inputStream.readBytes()
        val error = process.errorStream.readBytes()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("测试 Shell 没有正常结束")
        }
        val truncated = raw.size > maxOutputBytes
        val output = if (truncated) raw.copyOfRange(0, maxOutputBytes) else raw
        return OneShotShellResult(process.exitValue(), output, error, outputTruncated = truncated)
    }
}
