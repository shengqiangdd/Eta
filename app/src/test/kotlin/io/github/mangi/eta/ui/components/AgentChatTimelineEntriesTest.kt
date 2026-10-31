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
     * 普通消息条目也要复用：流式刷新时只有最后一条在变，前面几千条不必每帧新建包装对象。
     * 长会话（真机见过 8,435 条）里这决定了每帧是「新建几个对象」还是「新建几千个」。
     */
    @Test
    fun unchangedPlainMessagesReusePreviousEntries() {
        val user = UserMessageUi(id = "user-1", content = "问题")
        val previous = listOf(
            user,
            AgentMessageUi(id = "agent-1", content = "答", isStreaming = true),
        ).toTimelineEntries()

        val next = listOf(
            user,
            AgentMessageUi(id = "agent-1", content = "答案", isStreaming = true),
        ).toTimelineEntries(previous)

        assertSame("没变的那条不该每帧重建", previous[0], next[0])
        assertNotSame("变了的那条必须新建", previous[1], next[1])
    }

    @Test
    fun appendingOneMessageKeepsEveryEarlierEntryIdentical() {
        val head = (0 until 500).map {
            UserMessageUi(id = "u-$it", content = "历史 $it")
        }
        val previous = head.toTimelineEntries()

        val next = (head + UserMessageUi(id = "u-500", content = "新的一条"))
            .toTimelineEntries(previous)

        assertEquals(previous.size + 1, next.size)
        previous.indices.forEach { index -> assertSame("第 $index 条没变却重建了", previous[index], next[index]) }
    }

    /** 前插历史（向上翻页）不该把后面那些没变的普通消息条目全部重建。 */
    @Test
    fun prependingOlderMessagesKeepsTheFollowingEntriesIdentical() {
        val head = (0 until 300).map { UserMessageUi(id = "u-$it", content = "历史 $it") }
        val previous = head.toTimelineEntries()

        val next = (listOf(UserMessageUi(id = "u-old", content = "更早的一条")) + head)
            .toTimelineEntries(previous)

        assertEquals(previous.size + 1, next.size)
        assertNotSame("新插入的那条必须是新建的", previous[0], next[0])
        previous.indices.forEach { index ->
            assertSame("第 $index 条没变却重建了", previous[index], next[index + 1])
        }
    }

    /** 中间一条被新实例顶替：只该重建那一条，后面的游标不能永久卡住。 */
    @Test
    fun replacingOneMiddleMessageOnlyRebuildsThatEntry() {
        val head = (0 until 100).map { UserMessageUi(id = "u-$it", content = "历史 $it") }
        val previous = head.toTimelineEntries()

        val changed = head.toMutableList()
            .also { it[50] = UserMessageUi(id = "u-50", content = "历史 50（改）") }
        val next = changed.toTimelineEntries(previous)

        assertEquals(previous.size, next.size)
        assertNotSame(previous[50], next[50])
        previous.indices.filter { it != 50 }.forEach { index ->
            assertSame("第 $index 条没变却重建了", previous[index], next[index])
        }
    }

    /** previous 头部被裁掉几条（窗口滑动）：前瞻要在窗口内对齐，余下的继续复用。 */
    @Test
    fun droppingLeadingMessagesStillReusesTheRemainder() {
        val all = (0 until 100).map { UserMessageUi(id = "u-$it", content = "历史 $it") }
        val previous = all.toTimelineEntries()
        val next = all.drop(3).toTimelineEntries(previous)

        assertEquals(97, next.size)
        next.indices.forEach { index ->
            assertSame("第 $index 条没变却重建了", previous[index + 3], next[index])
        }
    }

    /** 错位超出前瞻窗口时只该少复用，绝不能错位配对：内容永远取自当前列表。 */
    @Test
    fun alignmentBeyondTheLookaheadOnlyCostsReuseNotCorrectness() {
        val all = (0 until 40).map { UserMessageUi(id = "u-$it", content = "历史 $it") }
        val previous = all.toTimelineEntries()
        val next = all.drop(20).toTimelineEntries(previous)

        assertEquals(20, next.size)
        next.forEachIndexed { index, entry ->
            val message = (entry as AgentTimelineEntry.Message).message as UserMessageUi
            assertEquals("第 $index 条内容必须来自当前列表", "u-${index + 20}", message.id)
        }
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
