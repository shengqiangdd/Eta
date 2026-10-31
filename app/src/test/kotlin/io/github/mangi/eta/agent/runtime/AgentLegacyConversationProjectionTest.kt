package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守 `AgentLegacyConversationProjection.encode` 的两件事：
 *
 * 1. 语义：超预算时保留**尾部**、加压缩提示；
 * 2. 复杂度：裁长对话不能是「删一条 → 整段重新序列化」（§32.24 真机踩过：
 *    `agent-result-io` 因此把一个核跑满好几分钟，用户感觉「手机很卡」）。
 */
class AgentLegacyConversationProjectionTest {
    private fun msg(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)

    @Test
    fun shortTranscriptIsReturnedVerbatim() {
        val encoded = AgentLegacyConversationProjection.encode(
            listOf(msg("user", "你好"), msg("assistant", "在")),
            96_000,
        )
        assertTrue(encoded.contains("你好"))
        assertTrue(encoded.contains("在"))
        assertFalse("没超预算不该出现压缩提示", encoded.contains("上下文提示"))
    }

    @Test
    fun oversizedTranscriptKeepsTheTailWithinBudget() {
        val messages = (1..400).map { index -> msg("assistant", "第 $index 段：" + "x".repeat(500)) }
        val encoded = AgentLegacyConversationProjection.encode(messages, 4_000)
        assertTrue("长度 ${encoded.length} 超预算", encoded.length <= 4_000)
        assertTrue("应带压缩提示", encoded.contains("上下文提示"))
        assertTrue("必须保留最后一条", encoded.contains("第 400 段"))
        assertTrue("最早的内容应被裁掉", !encoded.contains("第 1 段"))
    }

    @Test
    fun tailOfToolMessagesProjectsToEmpty() {
        // 预算只够放尾部那条 tool、跳过后什么都不剩：与旧实现一致给空投影。
        val messages = listOf(msg("user", "u".repeat(5_000)), msg("tool", "t".repeat(10)))
        assertEquals("[]", AgentLegacyConversationProjection.encode(messages, 300))
    }

    @Test
    fun longTranscriptIsNotQuadratic() {
        val messages = (1..2_000).map { index -> msg("assistant", "第 $index 段：" + "y".repeat(1_000)) }
        val elapsed = measureTimeMillis {
            val encoded = AgentLegacyConversationProjection.encode(messages, 16_000)
            assertTrue(encoded.length <= 16_000)
            assertTrue(encoded.contains("第 2000 段"))
        }
        // 旧实现约 2000 次 × 2MB 全量重编码；新实现是两次 O(总量)。5 秒足够宽松。
        assertTrue("耗时 ${elapsed}ms，疑似回到 O(n²)", elapsed < 5_000)
    }

    /**
     * 投影现在直接走 `sanitizeTranscript`，不再「序列化 → 解析」一次往返。
     * 这里钉住两者等价，否则清洗逻辑一旦分叉就会静默泄露到 IPC。
     */
    @Test
    fun sanitizePathMatchesJsonRoundTrip() {
        val messages = listOf(
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "答案",
                toolCallId = "call-1",
                reasoningContent = "先分析",
                toolCallsJson = "[]",
                contextSummary = true,
                compactedUserTurns = 2,
                summaryThroughUserTurn = 1,
                messageId = "m-1",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果", toolCallId = "call-1"),
            AgentModelClient.ConversationMessage(role = "user", content = "下一问"),
        )
        val roundTrip = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
        )
        assertEquals(roundTrip, AgentConversationCodec.sanitizeTranscript(messages))
    }
}
