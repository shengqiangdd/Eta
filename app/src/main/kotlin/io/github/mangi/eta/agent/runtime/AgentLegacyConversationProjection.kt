package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
        )
        if (bounded.isEmpty()) return "[]"
        val direct = json.encodeToString(bounded)
        if (direct.length <= maxChars) return direct

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        val noticeLength = json.encodeToString(notice).length
        // 每条只序列化一次（总开销 O(总字符量)），再按预算从尾部挑起点。
        // 旧实现是「删掉最早一条 → 把剩下的整段重新序列化」，长对话里是
        // O(条数 × 总字符量)：真机上 `agent-result-io` 曾因此把一个核跑满好几分钟（§32.24）。
        val lengths = IntArray(bounded.size) { index -> json.encodeToString(bounded[index]).length }
        var start = tailStartWithinBudget(lengths, noticeLength, maxChars)
        if (start >= bounded.size) return compactedFallback(bounded.last(), notice, maxChars)
        while (start < bounded.size && bounded[start].role == "tool") start++
        // 与旧行为一致：裁到头部只剩 tool 时直接给空投影。
        if (start >= bounded.size) return "[]"

        // 预算是按「单条长度 + 逗号」估的，与列表实际编码只差两个方括号；
        // 留几次校验，失败就多丢一条（最多 8 次），绝不回到「每条都重编码一遍」。
        repeat(8) {
            val encoded = json.encodeToString(listOf(notice) + bounded.subList(start, bounded.size))
            if (encoded.length <= maxChars) return encoded
            if (bounded.size - start <= 1) return compactedFallback(bounded.last(), notice, maxChars)
            start++
            while (start < bounded.size && bounded[start].role == "tool") start++
            if (start >= bounded.size) return "[]"
        }
        return compactedFallback(bounded.last(), notice, maxChars)
    }

    /** 从尾部向前累加，找出「加 notice 后仍不超过 maxChars」的最长后缀起点。 */
    private fun tailStartWithinBudget(lengths: IntArray, noticeLength: Int, maxChars: Int): Int {
        var used = noticeLength.toLong() + 2
        var start = lengths.size
        while (start > 0) {
            val cost = lengths[start - 1].toLong() + 1
            if (used + cost > maxChars) break
            used += cost
            start--
        }
        return start
    }

    private fun compactedFallback(
        last: AgentModelClient.ConversationMessage,
        notice: AgentModelClient.ConversationMessage,
        maxChars: Int,
    ): String {
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

}
