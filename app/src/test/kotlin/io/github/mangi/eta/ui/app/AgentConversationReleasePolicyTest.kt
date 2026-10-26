package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 释放消息内存的判据（纯 JVM）。
 *
 * 为什么要有判据而不是「切走就释放」：流式、编辑、压缩期间 messages 还可能被写回，
 * 释放会让改动落到空表上；而数据安全靠的是同一条规则 —— 只有确定「不会再有写入」的会话
 * 才允许标成未载入（标了之后落盘侧就整段跳过消息写入）。
 */
class AgentConversationReleasePolicyTest {
    private fun should(
        isActive: Boolean = false,
        isStreaming: Boolean = false,
        isEditing: Boolean = false,
        isCompacting: Boolean = false,
        messagesDeferred: Boolean = false,
        messageCount: Int = MESSAGE_RELEASE_THRESHOLD,
    ) = shouldReleaseConversationMessages(
        isActive = isActive,
        isStreaming = isStreaming,
        isEditing = isEditing,
        isCompacting = isCompacting,
        messagesDeferred = messagesDeferred,
        messageCount = messageCount,
    )

    @Test
    fun inactiveLargeConversationIsReleased() {
        assertTrue(should(isActive = false, messageCount = 8_435))
    }

    @Test
    fun activeConversationIsKept() {
        assertFalse(should(isActive = true, messageCount = 8_435))
    }

    @Test
    fun streamingConversationIsKept() {
        assertFalse(should(isStreaming = true))
    }

    @Test
    fun editingConversationIsKept() {
        assertFalse(should(isEditing = true))
    }

    @Test
    fun compactingConversationIsKept() {
        assertFalse(should(isCompacting = true))
    }

    @Test
    fun alreadyDeferredConversationIsNotReleasedAgain() {
        assertFalse(should(messagesDeferred = true))
    }

    @Test
    fun smallConversationIsKept() {
        assertFalse(should(messageCount = MESSAGE_RELEASE_THRESHOLD - 1))
    }

    @Test
    fun thresholdItselfIsReleased() {
        assertTrue(should(messageCount = MESSAGE_RELEASE_THRESHOLD))
    }

    @Test
    fun emptyConversationIsKept() {
        assertFalse(should(messageCount = 0))
    }
}
