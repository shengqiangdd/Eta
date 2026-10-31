package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §32.33 的守护测试：同进程直传必须**语义等价**且**不泄漏**。
 *
 * 这两条都比"快"重要：等价性错了会让模型看到与以前不同的历史；
 * 泄漏了则是每个请求留下几十 MB 的历史副本。
 */
class AgentWireHistoryHandoffTest {

    @After
    fun tearDown() {
        AgentWireHistoryHandoff.clear()
    }

    @Test
    fun offerThenTakeReturnsTheSameMessages() {
        val messages = sample(3)
        val id = AgentWireHistoryHandoff.offer(messages)
        assertEquals(messages, AgentWireHistoryHandoff.take(id))
    }

    @Test
    fun takeIsSingleUse() {
        val id = AgentWireHistoryHandoff.offer(sample(2))
        assertNotNull(AgentWireHistoryHandoff.take(id))
        // 第二次必须是 null：同一次请求不能被解析两遍，也不能拿到上一轮的旧数据。
        assertNull(AgentWireHistoryHandoff.take(id))
    }

    @Test
    fun emptyHistoryIsDistinguishableFromMissingHandoff() {
        // 「空历史」是合法状态（新会话第一条），必须原样返回空列表而不是 null；
        // 否则读侧会把"确实没有历史"和"交接丢了"混为一谈。
        val id = AgentWireHistoryHandoff.offer(emptyList())
        assertEquals(emptyList<AgentModelClient.ConversationMessage>(), AgentWireHistoryHandoff.take(id))
        assertNull(AgentWireHistoryHandoff.take(id))
    }

    @Test
    fun unknownOrBlankHandleIsNull() {
        assertNull(AgentWireHistoryHandoff.take(null))
        assertNull(AgentWireHistoryHandoff.take(""))
        assertNull(AgentWireHistoryHandoff.take("h-does-not-exist"))
    }

    @Test
    fun discardDropsUnconsumedEntry() {
        val id = AgentWireHistoryHandoff.offer(sample(1))
        AgentWireHistoryHandoff.discard(id)
        assertNull(AgentWireHistoryHandoff.take(id))
        assertEquals(0, AgentWireHistoryHandoff.pendingCount())
    }

    @Test
    fun capacityIsBoundedAndDropsOldest() {
        // 客户端 offer 了但服务端一直没来取（发送失败/被杀）时不能无限堆积。
        val ids = (1..20).map { AgentWireHistoryHandoff.offer(sample(1)) }
        assertTrue(
            "pending=${AgentWireHistoryHandoff.pendingCount()}",
            AgentWireHistoryHandoff.pendingCount() <= 8,
        )
        // 最新的必须还在，最旧的必须已被丢弃。
        assertNotNull(AgentWireHistoryHandoff.take(ids.last()))
        assertNull(AgentWireHistoryHandoff.take(ids.first()))
    }

    @Test
    fun callersMutationOfTheirOwnListDoesNotLeakIntoTheHandoff() {
        val mutable = ArrayList(sample(2))
        val id = AgentWireHistoryHandoff.offer(mutable)
        mutable.clear()
        assertEquals(2, AgentWireHistoryHandoff.take(id)?.size)
    }

    @Test
    fun handoffPayloadEqualsTheJsonRoundTrip() {
        // 直传的是 sanitizeTranscript 的结果；原来的路径是
        // decodeTranscript(encodeTranscriptForStorage(...))。两者必须逐字段一致（§32.25）。
        val messages = listOf(
            AgentModelClient.ConversationMessage(role = "system", content = "规则"),
            AgentModelClient.ConversationMessage(role = "user", content = "看看这张图"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "",
                contentJson = """
                    [{"type":"text","text":"a"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]
                """.trimIndent(),
            ),
            AgentModelClient.ConversationMessage(
                role = "tool",
                content = "结果",
                toolCallId = "call-1",
                toolCallsJson = """[{"id":"call-1","type":"function","function":{"name":"x","arguments":"{}"}}]""",
            ),
        )
        val roundTrip = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
        )
        assertEquals(roundTrip, AgentConversationCodec.sanitizeTranscript(messages))
    }

    private fun sample(size: Int): List<AgentModelClient.ConversationMessage> =
        (1..size).map { index ->
            AgentModelClient.ConversationMessage(
                role = if (index % 2 == 0) "assistant" else "user",
                content = "第 $index 条中文内容 with ascii letters",
            )
        }
}
