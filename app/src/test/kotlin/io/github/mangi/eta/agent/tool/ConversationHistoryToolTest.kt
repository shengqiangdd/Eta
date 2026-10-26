package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryToolTest {

    /** 用真实扫描器 + 内存分块冒充存储，验证工具与「按需读」的接口配合正确。 */
    private class FakeSource(
        journalText: String,
        private val live: List<AgentModelClient.ConversationMessage>,
        chunkChars: Int = 16,
    ) : ConversationHistorySource {
        private val chunks = if (journalText.isEmpty()) emptyList() else journalText.chunked(chunkChars)
        var scanCalls = 0
        var liveCalls = 0
        var lastBudget = -1
        var largestRequest = 0

        override suspend fun scan(
            fromIndex: Int,
            budget: Int,
            query: String?,
        ): JsonArrayElementScanner.Page {
            scanCalls++
            lastBudget = budget
            return JsonArrayElementScanner { from, limit ->
                largestRequest = maxOf(largestRequest, limit)
                if (from >= chunks.size) emptyList() else chunks.subList(from, minOf(chunks.size, from + limit))
            }.scan(fromIndex, budget, query)
        }

        override fun liveMessages(): List<AgentModelClient.ConversationMessage> {
            liveCalls++
            return live
        }
    }

    private fun source(
        journal: List<AgentModelClient.ConversationMessage> = emptyList(),
        live: List<AgentModelClient.ConversationMessage> = emptyList(),
    ) = FakeSource(AgentConversationCodec.encodeTranscriptForStorage(journal), live)

    @Test fun pagesReconstructEveryCharacterOfLargeMessage() {
        val message = AgentModelClient.ConversationMessage("assistant", "事实😀".repeat(15_000))
        val source = source(live = listOf(message))
        val tool = ConversationHistoryTool(source)
        val rebuilt = StringBuilder()
        var index = 0
        var offset = 0
        do {
            val result = JSONObject(
                tool.execute(
                    AgentModelClient.ToolCall(
                        "call", "conversation_history",
                        JSONObject().put("message_index", index).put("offset", offset).put("max_chars", 8000).toString(),
                    )
                ).content
            )
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) rebuilt.append(entries.getJSONObject(i).getString("text"))
            index = result.getInt("next_message_index")
            offset = result.getInt("next_offset")
        } while (result.getBoolean("has_more"))
        assertEquals(AgentConversationCodec.toJsonObject(message).toString(), rebuilt.toString())
        // 续读固定同一份内存快照，不会每页都重新取一遍 live。
        assertEquals(1, source.liveCalls)
    }

    @Test fun journalEntriesAndLiveMessagesShareOneIndexSpace() {
        val journal = listOf(
            AgentModelClient.ConversationMessage("user", "持久一"),
            AgentModelClient.ConversationMessage("assistant", "持久二"),
        )
        val tool = ConversationHistoryTool(source(journal, listOf(AgentModelClient.ConversationMessage("user", "内存三"))))
        val result = JSONObject(
            tool.execute(
                AgentModelClient.ToolCall("c", "conversation_history", """{"message_index":0,"max_chars":8000}""")
            ).content
        )
        assertEquals(3, result.getInt("total_messages"))
        val entries = result.getJSONArray("entries")
        assertEquals(3, entries.length())
        assertEquals(0, entries.getJSONObject(0).getInt("message_index"))
        assertEquals(2, entries.getJSONObject(2).getInt("message_index"))
        assertTrue(entries.getJSONObject(2).getString("text").contains("内存三"))
    }

    @Test fun searchReturnsOnlyMatchingCurrentSessionMessages() {
        val tool = ConversationHistoryTool(
            source(live = listOf(
                AgentModelClient.ConversationMessage("user", "旧请求"),
                AgentModelClient.ConversationMessage("tool", "关键标识 FileABC"),
            ))
        )
        val result = JSONObject(
            tool.execute(AgentModelClient.ToolCall("c", "conversation_history", """{"query":"fileabc"}""")).content
        )
        assertEquals(1, result.getJSONArray("entries").length())
        assertEquals(1, result.getJSONArray("entries").getJSONObject(0).getInt("message_index"))
    }

    /**
     * 这条守的是**内存有界**：工具向存储要的预算是本次要返回的字符数，
     * 而且每次只取固定几块 —— 不是「把整份 journal 交给我」。
     */
    @Test fun neverAsksStorageForTheWholeJournal() {
        val journal = (0 until 400).map {
            AgentModelClient.ConversationMessage("assistant", "内容".repeat(50))
        }
        val source = source(journal)
        val tool = ConversationHistoryTool(source)
        val result = JSONObject(
            tool.execute(
                AgentModelClient.ToolCall("c", "conversation_history", """{"message_index":0,"max_chars":256}""")
            ).content
        )
        assertEquals(400, result.getInt("total_messages"))
        assertEquals(256, source.lastBudget)
        assertTrue("单次取块数必须远小于总块数", source.largestRequest <= 8)
        assertTrue(result.getBoolean("has_more"))
    }

    @Test fun staleOffsetBeyondMessageLengthFailsLoudly() {
        val tool = ConversationHistoryTool(
            source(live = listOf(AgentModelClient.ConversationMessage("user", "短")))
        )
        val failed = runCatching {
            tool.execute(
                AgentModelClient.ToolCall("c", "conversation_history", """{"message_index":0,"offset":9999}""")
            )
        }.isFailure
        assertTrue(failed)
    }
}
