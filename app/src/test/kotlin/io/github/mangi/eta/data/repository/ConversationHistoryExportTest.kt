package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.repository.ConversationHistoryExporter.ChunkSpan
import io.github.mangi.eta.data.repository.ConversationHistoryExporter.HistoryMessage
import io.github.mangi.eta.data.repository.ConversationHistoryExporter.HistorySource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ConversationHistoryExportTest {

    private fun ref(chunks: Int, chars: Int) = "@eta:chunks:v1:$chunks:$chars"

    @Test
    fun describeStoredDistinguishesInlineAndChunked() {
        assertEquals(ChunkSpan(0, 0), ConversationHistoryExporter.describeStored(null))
        assertEquals(ChunkSpan(0, 0), ConversationHistoryExporter.describeStored(""))
        assertEquals(ChunkSpan(1, 5), ConversationHistoryExporter.describeStored("hello"))
        assertEquals(ChunkSpan(1898, 31_087_123), ConversationHistoryExporter.describeStored(ref(1898, 31_087_123)))
        // 引用格式坏了不能抛异常：宁可报 0，也不要让导出整体失败。
        assertEquals(ChunkSpan(0, 0), ConversationHistoryExporter.describeStored("@eta:chunks:v1:oops"))
        assertEquals(ChunkSpan(0, 0), ConversationHistoryExporter.describeStored("@eta:chunks:v1:1:2:3"))
    }

    @Test
    fun jsonStringEscapesEverythingThatWouldBreakALine() {
        val out = StringWriter()
        ConversationHistoryExporter.writeJsonString(out, "a\"b\\c\nd\re\tf\u0001g")
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"", out.toString())
    }

    @Test
    fun jsonStringKeepsValidSurrogatePairsAndEscapesLoneOnes() {
        val emoji = "\uD83D\uDE00" // 😀，成对代理项
        val out = StringWriter()
        ConversationHistoryExporter.writeJsonString(out, "x${emoji}y")
        assertEquals("\"x${emoji}y\"", out.toString())

        val lone = StringWriter()
        ConversationHistoryExporter.writeJsonString(lone, "a\uD83Db")
        assertEquals("\"a\\ud83db\"", lone.toString())
    }

    @Test
    fun everyLineIsValidJsonAndChunksReassembleTheOriginalText() = runBlocking {
        val journalText = buildString {
            append("[")
            repeat(50) { index ->
                if (index > 0) append(',')
                append("{\"i\":").append(index).append("}")
            }
            append("]")
        }
        val journalChunks = chunk(journalText, 10)
        val contentText = "第一段含中文与 emoji 😀\n第二行"
        val contentChunks = chunk(contentText, 7)

        val source = FakeSource(
            messageTotal = 2,
            messages = listOf(
                message(
                    id = "m1",
                    sortIndex = 0,
                    type = "user",
                    contentRef = ref(contentChunks.size, contentText.length),
                ),
                message(
                    id = "m2",
                    sortIndex = 1,
                    type = "assistant",
                    contentRef = "短回答",
                    toolName = "terminal",
                    toolStatus = "succeeded",
                    toolArguments = "{\"command\":\"id\\n\"}",
                    toolResult = "uid=0",
                ),
            ),
            journalRef = ref(journalChunks.size, journalText.length),
            journalChunks = journalChunks,
            contentChunks = mapOf("m1" to contentChunks),
        )

        val out = StringWriter()
        val summary = ConversationHistoryExporter.writeHistory(
            source = source,
            conversationId = "conv-1",
            title = "标题 \"引号\"",
            exportedAt = 1_700_000_000_000L,
            out = out,
        )

        val lines = out.toString().trimEnd('\n').split('\n')
        lines.forEach { line -> JSONObject(line) } // 任何一行不合法都会抛异常

        val meta = JSONObject(lines.first())
        assertEquals("meta", meta.getString("kind"))
        assertEquals(ConversationHistoryExporter.FORMAT, meta.getString("format"))
        assertEquals(ConversationHistoryExporter.VERSION, meta.getInt("version"))
        assertEquals(2, meta.getInt("messageCount"))
        assertEquals(journalChunks.size, meta.getJSONObject("journal").getInt("chunks"))
        assertEquals(journalText.length, meta.getJSONObject("journal").getInt("chars"))

        val end = JSONObject(lines.last())
        assertEquals("end", end.getString("kind"))
        assertEquals(2, end.getInt("messages"))
        assertEquals(journalChunks.size, end.getInt("journalChunks"))
        assertEquals(summary.messageCount, end.getInt("messages"))
        assertEquals(summary.journalChunks, end.getInt("journalChunks"))

        // 分块按 index 拼回去必须逐字节等于原文。
        val contentBack = lines.map { JSONObject(it) }
            .filter { it.optString("kind") == "content" && it.optString("messageId") == "m1" }
            .sortedBy { it.getInt("index") }
            .joinToString("") { it.getString("text") }
        assertEquals(contentText, contentBack)

        val journalBack = lines.map { JSONObject(it) }
            .filter { it.optString("kind") == "journal" }
            .sortedBy { it.getInt("index") }
            .joinToString("") { it.getString("text") }
        assertEquals(journalText, journalBack)

        // 工具调用的元数据要带上，否则「完整历史」名不副实。
        val toolMessage = lines.map { JSONObject(it) }
            .single { it.optString("kind") == "message" && it.getString("id") == "m2" }
        assertEquals("terminal", toolMessage.getJSONObject("tool").getString("name"))
        assertEquals("uid=0", toolMessage.getJSONObject("tool").getString("result"))
        assertEquals(1, toolMessage.getJSONObject("content").getInt("chunks"))
    }

    @Test
    fun pagingCoversEveryMessageAndChunkExactlyOnce() = runBlocking {
        val messageTotal = ConversationHistoryExporter.MESSAGE_PAGE * 2 + 7
        val messages = (0 until messageTotal).map { index ->
            message(
                id = "m$index",
                sortIndex = index,
                type = "user",
                contentRef = "c$index",
            )
        }
        val journalChunks = (0 until ConversationHistoryExporter.CHUNK_PAGE * 3 + 5).map { "j$it" }
        val source = FakeSource(
            messageTotal = messageTotal,
            messages = messages,
            journalRef = ref(journalChunks.size, journalChunks.sumOf { it.length }),
            journalChunks = journalChunks,
            contentChunks = emptyMap(),
            countFromList = true,
        )

        val out = StringWriter()
        val summary = ConversationHistoryExporter.writeHistory(
            source = source,
            conversationId = "conv-2",
            title = "t",
            exportedAt = 0L,
            out = out,
        )

        assertEquals(messageTotal, summary.messageCount)
        assertEquals(journalChunks.size, summary.journalChunks)
        val parsed = out.toString().trimEnd('\n').split('\n').map { JSONObject(it) }
        val exportedIds = parsed.filter { it.optString("kind") == "message" }.map { it.getString("id") }
        assertEquals(messages.map { it.id }, exportedIds)
        val journalIndices = parsed.filter { it.optString("kind") == "journal" }.map { it.getInt("index") }
        assertEquals(journalChunks.indices.toList(), journalIndices)
    }

    @Test
    fun defaultFileNameSanitizesAndKeepsExtension() {
        val name = ConversationHistoryExporter.defaultFileName(
            title = "a/b:c*? \"d\"\n e",
            fallback = "未命名",
            nowMillis = 1_700_000_000_000L,
        )
        assertTrue(name, name.endsWith(".jsonl"))
        assertTrue(name, name.startsWith("Eta-"))
        assertTrue(name, !name.contains('/') && !name.contains(':'))
    }

    private fun chunk(text: String, size: Int): List<String> =
        text.chunked(size).ifEmpty { listOf() }

    private fun message(
        id: String,
        sortIndex: Int,
        type: String,
        contentRef: String,
        toolName: String? = null,
        toolStatus: String? = null,
        toolArguments: String? = null,
        toolResult: String? = null,
    ) = HistoryMessage(
        id = id,
        sortIndex = sortIndex,
        type = type,
        isEdited = false,
        contentRef = contentRef,
        imagesRef = "[]",
        toolName = toolName,
        toolStatus = toolStatus,
        toolArguments = toolArguments,
        toolResult = toolResult,
    )

    private class FakeSource(
        private val messageTotal: Int,
        private val messages: List<HistoryMessage>,
        private val journalRef: String?,
        private val journalChunks: List<String>,
        private val contentChunks: Map<String, List<String>>,
        private val countFromList: Boolean = false,
    ) : HistorySource {
        override suspend fun messageCount(conversationId: String): Int =
            if (countFromList) messages.size else messageTotal

        override suspend fun messages(
            conversationId: String,
            offset: Int,
            limit: Int,
        ): List<HistoryMessage> = messages.drop(offset).take(limit)

        override suspend fun journalRef(conversationId: String): String? = journalRef

        override suspend fun chunks(
            ownerTable: String,
            ownerId: String,
            field: String,
            ref: String,
            from: Int,
            limit: Int,
        ): List<String> {
            val source = when {
                ownerTable == "conversation_context_checkpoints" -> journalChunks
                field == "content" -> contentChunks[ownerId] ?: emptyList()
                else -> emptyList()
            }
            return source.drop(from).take(limit)
        }
    }
}
