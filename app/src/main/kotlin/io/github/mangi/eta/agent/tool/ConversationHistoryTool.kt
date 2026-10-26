package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话历史的**有界**读取入口。
 *
 * 实现方负责「怎么按需读」：持久 journal 从存储分块流式取，只有本回合内存里的消息才在堆上。
 * 这样分页时内存只跟「本次要返回的字符数」有关，与整份历史大小无关。
 */
internal interface ConversationHistorySource {
    /**
     * 扫描持久 journal，只返回本次需要的几条。
     *
     * @param fromIndex 从这个消息下标开始捕获
     * @param budget 捕获文本的字符预算
     * @param query 非空时只捕获命中的消息
     */
    suspend fun scan(fromIndex: Int, budget: Int, query: String?): JsonArrayElementScanner.Page

    /** 本回合内存中的消息（已脱敏），下标接在持久 journal 之后。 */
    fun liveMessages(): List<AgentModelClient.ConversationMessage>
}

/** 分页只限制返回内容，不修改持久历史；续读固定同一份内存快照。 */
internal class ConversationHistoryTool(
    private val source: ConversationHistorySource,
) : AgentModelClient.ToolExecutor {
    private var liveSnapshot: List<AgentModelClient.ConversationMessage>? = null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        runBlocking { executeSuspending(toolCall) }

    private suspend fun executeSuspending(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = JSONObject(toolCall.argumentsJson)
        val query = args.optString("query")
        require(query.length <= 500)
        val startIndex = args.optInt("message_index", 0)
        var offset = args.optInt("offset", 0)
        val maxChars = args.optInt("max_chars", 8000)
        require(startIndex >= 0 && offset >= 0 && maxChars in 256..8000)
        // 续读固定同一份历史，不能追着本工具新产生的日志无限读取。
        if (liveSnapshot == null || (startIndex == 0 && offset == 0)) liveSnapshot = source.liveMessages()
        val live = checkNotNull(liveSnapshot)

        val startedNs = System.nanoTime()
        val page = source.scan(
            fromIndex = startIndex,
            budget = maxChars,
            query = query.ifBlank { null },
        )
        val journalTotal = page.totalMessages
        // 真机上「读了 3100 万字符还是 8 千字符」只有日志能看出来；单测里没有 Android Log，吞掉。
        runCatching {
            AndroidAgentLogger.info(
                "read_history from=$startIndex budget=$maxChars messages=$journalTotal " +
                    "captured=${page.captured.size} chars=${page.captured.sumOf { it.second.length }} " +
                    "ms=${(System.nanoTime() - startedNs) / 1_000_000}",
            )
        }

        // 两个来源拼成同一串候选：journal 的（已按 fromIndex/query 过滤）在前，本回合在后。
        val candidates = buildList(page.captured.size + live.size) {
            addAll(page.captured)
            for (position in live.indices) {
                val text = AgentConversationCodec.toJsonObject(live[position]).toString()
                if (query.isNotBlank() && !text.contains(query, ignoreCase = true)) continue
                add((journalTotal + position) to text)
            }
        }

        val entries = JSONArray()
        var remaining = maxChars
        var nextIndex = startIndex
        var nextOffset = offset
        var cursor = 0
        var truncated = false
        while (cursor < candidates.size && entries.length() < 20 && remaining > 0) {
            val (messageIndex, text) = candidates[cursor]
            cursor++
            if (messageIndex < nextIndex) continue
            if (messageIndex > nextIndex) {
                // 中间的消息被 query 过滤掉了，下标要跟上。
                nextIndex = messageIndex
                nextOffset = 0
            }
            require(nextOffset <= text.length)
            var end = minOf(text.length, nextOffset + remaining)
            if (end < text.length && end > nextOffset && text[end - 1].isHighSurrogate()) end--
            if (end <= nextOffset) {
                // 只剩一个代理对时也要前进，绝不能让调用方原地打转。
                end = minOf(text.length, nextOffset + 2)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
            }
            entries.put(
                JSONObject().put("message_index", messageIndex).put("offset", nextOffset)
                    .put("text", text.substring(nextOffset, end)).put("complete", end == text.length)
            )
            remaining -= end - nextOffset
            if (end < text.length) {
                nextOffset = end
                nextIndex = messageIndex
                truncated = true
                break
            }
            nextOffset = 0
            nextIndex = messageIndex + 1
        }
        return AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", true)
                .put("total_messages", journalTotal + live.size)
                .put("entries", entries)
                .put("has_more", truncated || cursor < candidates.size)
                .put("next_message_index", nextIndex)
                .put("next_offset", nextOffset)
                .toString()
        )
    }
}
