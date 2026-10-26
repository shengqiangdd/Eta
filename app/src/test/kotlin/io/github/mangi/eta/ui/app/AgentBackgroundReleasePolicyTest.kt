package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退到后台时释放**当前**会话消息的判据。
 *
 * 为什么需要单独一条规则：[shouldReleaseConversationMessages] 要求 `!isActive`，
 * 于是用户正在看的那个会话永远常驻 —— 它恰好是内存的最大一块（实测 3,569 条消息的会话
 * 让进程 RSS 到 400MB 量级），而 App 在后台时这块内存毫无用处，却是系统回收进程时
 * 最先被记账的部分。后台释放，回前台重载。
 */
class AgentBackgroundReleasePolicyTest {
    private fun should(
        isStreaming: Boolean = false,
        isEditing: Boolean = false,
        isCompacting: Boolean = false,
        messagesDeferred: Boolean = false,
        messageCount: Int = MESSAGE_RELEASE_THRESHOLD,
    ) = shouldReleaseBackgroundMessages(
        isStreaming = isStreaming,
        isEditing = isEditing,
        isCompacting = isCompacting,
        messagesDeferred = messagesDeferred,
        messageCount = messageCount,
    )

    @Test
    fun `后台的大会话会被释放`() {
        assertTrue(should(messageCount = 3_569))
    }

    @Test
    fun `阈值本身会释放`() {
        assertTrue(should(messageCount = MESSAGE_RELEASE_THRESHOLD))
    }

    @Test
    fun `小会话不释放`() {
        assertFalse(should(messageCount = MESSAGE_RELEASE_THRESHOLD - 1))
    }

    @Test
    fun `流式生成中不释放`() {
        assertFalse(should(isStreaming = true, messageCount = 3_569))
    }

    @Test
    fun `编辑中不释放`() {
        assertFalse(should(isEditing = true, messageCount = 3_569))
    }

    @Test
    fun `压缩中不释放`() {
        assertFalse(should(isCompacting = true, messageCount = 3_569))
    }

    @Test
    fun `已经释放过的不重复释放`() {
        assertFalse(should(messagesDeferred = true, messageCount = 3_569))
    }

    @Test
    fun `空会话不释放`() {
        assertFalse(should(messageCount = 0))
    }
}
