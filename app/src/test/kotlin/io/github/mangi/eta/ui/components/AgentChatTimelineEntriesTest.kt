package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatTimelineEntriesTest {

    @Test
    fun unchangedWorkProcessReusesPreviousEntry() {
        val thinking = ThinkingMessageUi(id = "think-1", content = "分析", isStreaming = false)
        val first = listOf(
            UserMessageUi(id = "user-1", content = "问题"),
            thinking,
            AgentMessageUi(id = "agent-1", content = "答", isStreaming = true),
        )
        val previous = first.toTimelineEntries()
        val next = listOf(
            first[0],
            thinking,
            AgentMessageUi(id = "agent-1", content = "答案", isStreaming = true),
        ).toTimelineEntries(previous)

        assertSame(previous[1], next[1])
    }

    @Test
    fun changedWorkProcessMemberCreatesNewEntry() {
        val first = listOf(ThinkingMessageUi(id = "think-1", content = "分析", isStreaming = true))
        val previous = first.toTimelineEntries()
        val next = listOf(
            ThinkingMessageUi(id = "think-1", content = "分析中", isStreaming = true),
        ).toTimelineEntries(previous)

        assertNotSame(previous[0], next[0])
    }

    @Test
    fun prependingTheStartOfAWorkProcessPreservesItsVisibleKey() {
        val partialThinking = ThinkingMessageUi("think-tail", "后续思考", isStreaming = false)
        val answer = AgentMessageUi("answer", "结果")
        val previous = listOf(partialThinking, answer).toTimelineEntries()
        val next = listOf(
            UserMessageUi("user", "问题"),
            ThinkingMessageUi("think-start", "起始思考", isStreaming = false),
            partialThinking,
            answer,
        ).toTimelineEntries(previous)

        assertEquals(previous[0].key, next[1].key)
        assertEquals(previous[1].key, next[2].key)
        assertEquals(2, (next[1] as AgentTimelineEntry.WorkProcess).messages.size)
    }

    @Test
    fun splittingAPreviouslyPrependedGroupDoesNotReuseItsKeyTwice() {
        val first = ThinkingMessageUi("think-first", "起始思考", isStreaming = false)
        val last = ThinkingMessageUi("think-last", "后续思考", isStreaming = false)
        val previous = listOf(first, last).toTimelineEntries(listOf(last).toTimelineEntries())

        val next = listOf(first, AgentMessageUi("answer", "中间结果"), last).toTimelineEntries(previous)

        assertEquals(next.size, next.map { it.key }.distinct().size)
    }

    /**
     * 索引只按「块首 id」建，当前块的块首是新消息时必须回退到按块扫描，
     * 否则前插后 key 会变、滚动锚点会丢。
     */
    @Test
    fun prependingWhenPreviousHasSeveralWorkProcessesPicksTheOverlappingOne() {
        val a1 = ThinkingMessageUi("a-1", "A1", isStreaming = false)
        val b1 = ThinkingMessageUi("b-1", "B1", isStreaming = false)
        val answer = AgentMessageUi("answer", "结果")
        val previous = listOf(a1, answer, b1).toTimelineEntries()

        val a0 = ThinkingMessageUi("a-0", "A0", isStreaming = false)
        val next = listOf(a0, a1, answer, b1).toTimelineEntries(previous)

        assertEquals(previous[0].key, next[0].key)
        assertEquals(previous[2].key, next[2].key)
        assertEquals(2, (next[0] as AgentTimelineEntry.WorkProcess).messages.size)
    }

    /** 防御：上一版里出现空块时，首条 id 索引取不到，不能抛异常。 */
    @Test
    fun workProcessWithEmptyMessagesDoesNotCrash() {
        val previous = listOf(AgentTimelineEntry.WorkProcess(key = "empty", messages = emptyList()))
        val next = listOf(ThinkingMessageUi("t-1", "x", isStreaming = false)).toTimelineEntries(previous)

        assertEquals(1, next.size)
        assertEquals("work-t-1", next[0].key)
    }

    /**
     * 长会话的流式刷新：块首不变就要复用原 key。
     * 用 2000 条过程消息跑一遍，覆盖旧实现里要为每个 id 建 map 条目 的那条路径。
     */
    @Test
    fun longConversationKeepsWorkProcessKeyAcrossAppends() {
        val messages = (0 until 2_000).map {
            ThinkingMessageUi("t-$it", "步骤 $it", isStreaming = false)
        }
        val previous = messages.toTimelineEntries()
        assertEquals(1, previous.size)

        val next = (messages + ThinkingMessageUi("t-2000", "新一步", isStreaming = true))
            .toTimelineEntries(previous)

        assertEquals(1, next.size)
        assertEquals(previous[0].key, next[0].key)
        assertNotSame(previous[0], next[0])
    }
}
