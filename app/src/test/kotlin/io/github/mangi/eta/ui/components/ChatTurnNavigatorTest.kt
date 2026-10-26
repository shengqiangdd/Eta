package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 长会话的回合导航。
 *
 * 守两件事：① 只有用户提问算回合起点，别把助手正文或系统通知当锚点（会跳到半途）；
 * ② 回合归属必须按**时间线条目**算 —— 过程消息会被折叠成工作过程块，按消息数算的 index
 * 与 firstVisibleItemIndex 对不上，读数会一直差几格。
 */
class ChatTurnNavigatorTest {
    private fun user(id: String) = UserMessageUi(id = id, content = "问 $id")

    private fun agent(id: String) = AgentMessageUi(id = id, content = "答 $id")

    private fun thinking(id: String) = ThinkingMessageUi(id = id, content = "想", isStreaming = false)

    private fun notice(id: String) = SystemNoticeMessageUi(id = id, code = SystemNoticeCode.Stopped)

    @Test
    fun anchorsAreUserQuestionsOnly() {
        val messages: List<AgentChatMessageUi> = listOf(
            notice("n0"),
            user("u1"),
            thinking("t1"),
            agent("m1"),
            user("u2"),
            thinking("t2"),
        )
        assertEquals(listOf("u1", "u2"), chatTurnAnchorIds(messages))
    }

    @Test
    fun anchorsAreEmptyWithoutQuestions() {
        val messages: List<AgentChatMessageUi> = listOf(thinking("t1"), agent("m1"), notice("n1"))
        assertEquals(emptyList<String>(), chatTurnAnchorIds(messages))
    }

    @Test
    fun entriesBeforeFirstQuestionBelongToNoTurn() {
        // 开场卡片、系统通知之类的条目出现在第一次提问之前，不能算进任何回合。
        assertEquals(listOf(-1, -1, 0), turnIndexByEntryStarts(listOf(false, false, true)))
    }

    @Test
    fun entryTurnsIncrementOnQuestionEntries() {
        val turns = turnIndexByEntryStarts(listOf(false, true, false, false, true, false, true))
        assertEquals(listOf(-1, 0, 0, 0, 1, 1, 2), turns)
    }

    @Test
    fun emptyTimelineHasNoEntries() {
        assertEquals(emptyList<Int>(), turnIndexByEntryStarts(emptyList()))
    }

    @Test
    fun currentTurnWalksBackFromVisibleEntry() {
        val turns = turnIndexByEntryStarts(listOf(true, false, false, true, false))
        assertEquals(0, currentTurnIndex(turns, 0))
        assertEquals(0, currentTurnIndex(turns, 2))
        assertEquals(1, currentTurnIndex(turns, 3))
        assertEquals(1, currentTurnIndex(turns, 4))
    }

    @Test
    fun currentTurnIsNullBeforeFirstQuestion() {
        val turns = turnIndexByEntryStarts(listOf(false, false, true))
        assertNull(currentTurnIndex(turns, 0))
        assertNull(currentTurnIndex(turns, 1))
        assertEquals(0, currentTurnIndex(turns, 2))
    }

    @Test
    fun currentTurnDoesNotReadPastTheEnd() {
        val turns = turnIndexByEntryStarts(listOf(true, false, true))
        // 列表滚动时的索引可能比当前时间线长一帧，必须夹取而不是越界。
        assertEquals(1, currentTurnIndex(turns, 99))
    }

    @Test
    fun emptyTimelineHasNoCurrentTurn() {
        assertNull(currentTurnIndex(emptyList(), 0))
    }

    @Test
    fun atEndReportsTheNewestTurn() {
        // 滚到最底部时视口顶部仍是上一回合的尾巴；只看顶部会报 19/20 这种「没到最新」的假象。
        val turns = turnIndexByEntryStarts(listOf(true, false, true, false, false))
        assertEquals(0, currentTurnIndex(turns, 1, atEnd = false))
        assertEquals(1, currentTurnIndex(turns, 1, atEnd = true))
    }

    @Test
    fun atEndOnEmptyOrLeadingEntriesFallsBackToNull() {
        val turns = turnIndexByEntryStarts(listOf(false, false))
        assertNull(currentTurnIndex(turns, 1, atEnd = true))
    }

    @Test
    fun moveFromBeforeTheFirstQuestion() {
        // 视口停在开场（第一个提问之前）：上面没有回合，「下一个」必须正好落到第 1 回合。
        assertNull(moveTurnIndex(null, total = 3, step = -1))
        assertEquals(0, moveTurnIndex(null, total = 3, step = 1))
    }

    @Test
    fun moveDoesNotWrapAround() {
        // 回合少，循环会让人以为跳成功了，实际跨过了整段对话。
        assertNull(moveTurnIndex(0, total = 3, step = -1))
        assertNull(moveTurnIndex(2, total = 3, step = 1))
    }

    @Test
    fun moveStepsWithinRange() {
        assertEquals(1, moveTurnIndex(2, total = 3, step = -1))
        assertEquals(2, moveTurnIndex(1, total = 3, step = 1))
    }

    @Test
    fun moveOnEmptyConversationDoesNothing() {
        assertNull(moveTurnIndex(0, total = 0, step = 1))
        assertNull(moveTurnIndex(null, total = 0, step = -1))
    }

    @Test
    fun moveClampsWhenTurnCountShrinksUnderIt() {
        // 编辑消息会把后续消息截掉（visibleMessagesForEdit），锚点集合随之变小：
        // 上一刻还停在 20/21，下一刻 total 变成 5。此时界面上的 currentTurn 仍是上一帧的 19，
        // 必须夹取到新的 total-1，否则 getOrNull(19) 返回 null，按钮看着可用却毫无反应。
        //
        // 这条守的是「显示与点击必须同一个口径」：ChatTurnNavigator 显示端用 coerceIn 夹过，
        // 所以箭头是亮的；回调若用未夹取的 currentTurn，moveTurnIndex 整体越界返回 null，
        // 就出现「箭头亮着但点了没反应」。两端必须一起夹。
        val shrunkTotal = 5
        val staleCurrent = 19
        val shown = staleCurrent.coerceIn(0, shrunkTotal - 1)
        assertEquals(4, shown)

        // 夹取后：上一回合可用，下一回合正确越界（显示端也会把下箭头置灰）。
        assertEquals(3, moveTurnIndex(shown, shrunkTotal, -1))
        assertNull(moveTurnIndex(shown, shrunkTotal, 1))
        // 未夹取的旧值会两个方向全哑 —— 这正是要防的。
        assertNull(moveTurnIndex(staleCurrent, shrunkTotal, -1))
        assertNull(moveTurnIndex(staleCurrent, shrunkTotal, 1))
    }

    @Test
    fun editTruncationShrinksAnchorsMonotonically() {
        // 复刻编辑态：截到被编辑消息为止。提问数只能变少，不会出现「编辑后多出一个回合」。
        val messages: List<AgentChatMessageUi> = listOf(
            notice("n0"), user("u1"), agent("m1"), user("u2"), agent("m2"), user("u3"), agent("m3"),
        )
        val full = chatTurnAnchorIds(messages)
        assertEquals(listOf("u1", "u2", "u3"), full)
        // 编辑 u2：截到 u2 为止（含它自己）。
        val truncated = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "u2")
        val after = chatTurnAnchorIds(truncated)
        assertEquals(listOf("u1", "u2"), after)
        assert(after.size < full.size) { "编辑截断后回合数不应增加" }
    }
}
