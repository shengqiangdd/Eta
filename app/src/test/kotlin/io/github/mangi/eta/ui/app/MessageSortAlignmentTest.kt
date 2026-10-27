package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息窗口的 sort_index 对齐判据。
 *
 * 真实事故（本机 PKX110，会话 conv-1a25f9f1）：库里该会话只剩尾部，首行 sort_index 是 12328 而不是 0，
 * 而总行数 16200。旧判据要求 `sortIndex == windowStart + index`（windowStart = 总行数 − 500 = 15700），
 * 于是每次启动都被判成「未对齐」：该会话不进保存基线，每次保存都按新会话整段重写，
 * 删除范围又拿【行偏移】当【sort_index】用（`sort_index >= 15700`），
 * 结果把窗口之前的 12828 行历史一并删掉 —— 用户看到的就是「切走再回来、重启之后消息消失」。
 *
 * 这些用例守的是：判据只要求行序严格递增（允许起点不为 0、允许跳号），
 * 以及重写起点必须来自库里的真实 sort_index。
 */
class MessageSortAlignmentTest {

    @Test
    fun `empty list is aligned`() {
        assertTrue(AgentConversationStore.areSortIndexesIncreasing(emptyList()))
    }

    @Test
    fun `indexes starting at zero are aligned`() {
        assertTrue(AgentConversationStore.areSortIndexesIncreasing(listOf(0, 1, 2, 3)))
    }

    @Test
    fun `indexes starting above zero are aligned`() {
        // 本机真实数据：裁剪后首行 sort_index = 12328。
        assertTrue(AgentConversationStore.areSortIndexesIncreasing(listOf(12328, 12329, 12330)))
    }

    @Test
    fun `gaps are allowed as long as order is strict`() {
        assertTrue(AgentConversationStore.areSortIndexesIncreasing(listOf(1, 3, 4, 99)))
    }

    @Test
    fun `duplicate index is not aligned`() {
        assertFalse(AgentConversationStore.areSortIndexesIncreasing(listOf(5, 5, 6)))
    }

    @Test
    fun `descending index is not aligned`() {
        assertFalse(AgentConversationStore.areSortIndexesIncreasing(listOf(7, 6)))
    }

    @Test
    fun `rewind start uses real sort index from database`() {
        // 行偏移 15700 对应的真实 sort_index 是 28028，删除必须从 28028 开始。
        assertEquals(28028, AgentConversationStore.resolveMessageRewindStart(28028, 28527))
    }

    @Test
    fun `rewind start appends after max when offset row is missing`() {
        assertEquals(100, AgentConversationStore.resolveMessageRewindStart(null, 99))
    }

    @Test
    fun `rewind start on empty table is zero`() {
        assertEquals(0, AgentConversationStore.resolveMessageRewindStart(null, null))
    }
}
