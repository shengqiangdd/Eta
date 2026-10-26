package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 扫描器必须与「整体 JSON 解析」口径一致：同样的条数、同样的下标、同样的原文，
 * 但堆上只留少量分块 —— 这是 conversation_history 不再整份还原 journal 的前提。
 */
class JsonArrayElementScannerTest {

    private fun reader(text: String, chunkChars: Int = 8) = JsonArrayElementScanner.ChunkReader { from, limit ->
        val chunks = text.chunked(chunkChars)
        if (from >= chunks.size) emptyList() else chunks.subList(from, minOf(chunks.size, from + limit))
    }

    private fun journal(vararg messages: AgentModelClient.ConversationMessage) =
        AgentConversationCodec.encodeTranscriptForStorage(messages.toList())

    @Test fun countsEveryElementAndCapturesFromRequestedIndex() = runBlocking {
        val text = journal(
            AgentModelClient.ConversationMessage("user", "第一条"),
            AgentModelClient.ConversationMessage("assistant", "第二条"),
            AgentModelClient.ConversationMessage("user", "第三条"),
        )
        val page = JsonArrayElementScanner(reader(text)).scan(fromIndex = 1, budget = 8_000, query = null)
        assertEquals(3, page.totalMessages)
        assertEquals(listOf(1, 2), page.captured.map { it.first })
        assertTrue(page.captured[0].second.contains("第二条"))
        assertFalse(page.captured[0].second.contains("第一条"))
    }

    @Test fun budgetStopsCaptureButCountingContinues() = runBlocking {
        val messages = (0 until 50).map {
            AgentModelClient.ConversationMessage("assistant", "填充".repeat(200))
        }
        val text = journal(*messages.toTypedArray())
        val page = JsonArrayElementScanner(reader(text, chunkChars = 32)).scan(0, budget = 1_000, query = null)
        assertEquals(50, page.totalMessages)
        assertTrue("预算后不应继续留原文", page.captured.sumOf { it.second.length } < 3_000)
        assertTrue("但至少要留下一条", page.captured.isNotEmpty())
    }

    @Test fun queryMatchesCaseInsensitivelyAcrossChunks() = runBlocking {
        val text = journal(
            AgentModelClient.ConversationMessage("user", "旧请求"),
            AgentModelClient.ConversationMessage("tool", "关键标识 FileABC"),
            AgentModelClient.ConversationMessage("user", "无关内容"),
        )
        val page = JsonArrayElementScanner(reader(text, chunkChars = 5)).scan(0, 8_000, "fileabc")
        assertEquals(3, page.totalMessages)
        assertEquals(listOf(1), page.captured.map { it.first })
    }

    @Test fun handlesNestedArraysAndEscapedQuotes() = runBlocking {
        val text = journal(
            AgentModelClient.ConversationMessage("user", """消息里有 ] 和 } 还有 "引号" 和 \\ 反斜杠"""),
            AgentModelClient.ConversationMessage(
                "assistant",
                contentJson = """[{"type":"text","text":"嵌套数组"}]""",
            ),
        )
        val page = JsonArrayElementScanner(reader(text, chunkChars = 3)).scan(0, 8_000, null)
        assertEquals(2, page.totalMessages)
        assertEquals(2, page.captured.size)
    }

    @Test fun emptyOrBrokenPayloadDoesNotCrash() = runBlocking {
        assertEquals(0, JsonArrayElementScanner(reader("[]")).scan(0, 8_000, null).totalMessages)
        assertEquals(0, JsonArrayElementScanner(reader("")).scan(0, 8_000, null).totalMessages)
        // 元素本身坏掉：条数照数，但不捕获（不能因此让整次分页读取失败）。
        val broken = """[{"role":"user","content":"ok"},{"role":12345},]"""
        val page = JsonArrayElementScanner(reader(broken, chunkChars = 4)).scan(0, 8_000, null)
        assertEquals(2, page.totalMessages)
        assertEquals(listOf(0), page.captured.map { it.first })
    }
}
