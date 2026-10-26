package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentAskUiTest {

    private fun ask(
        id: String = "ask-1",
        question: String = "要清理哪个应用？",
        options: List<String> = listOf("微信", "QQ"),
        status: ToolActivityStatusUi = ToolActivityStatusUi.Success,
    ) = ToolActivityMessageUi(
        id = id,
        toolName = "ask_user",
        status = status,
        argumentsSummary = "",
        askQuestion = question,
        askOptions = options,
    )

    @Test
    fun noAskMeansNothingPending() {
        assertNull(pendingAskOf(listOf(UserMessageUi("u1", "你好"))))
    }

    @Test
    fun anAskWithoutALaterUserMessageStaysPending() {
        val pending = pendingAskOf(listOf(UserMessageUi("u1", "清理一下"), ask()))
        assertEquals("ask-1", pending?.messageId)
        assertEquals("要清理哪个应用？", pending?.question)
        assertEquals(listOf("微信", "QQ"), pending?.options)
    }

    @Test
    fun aUserMessageAfterTheAskCountsAsAnswered() {
        // 点选项与手打文字都会落成 UserMessageUi，所以同一判据同时覆盖两种回答方式。
        assertNull(pendingAskOf(listOf(ask(), UserMessageUi("u2", "微信"))))
    }

    @Test
    fun onlyTheNewestAskIsPending() {
        val pending = pendingAskOf(listOf(ask(id = "old"), ask(id = "new")))
        assertEquals("new", pending?.messageId)
    }

    @Test
    fun failedOrQuestionlessAsksAreIgnored() {
        assertNull(pendingAskOf(listOf(ask(status = ToolActivityStatusUi.Failed))))
        assertNull(pendingAskOf(listOf(ask(question = "  "))))
    }

    @Test
    fun otherToolsNeverProduceAPendingAsk() {
        val other = ToolActivityMessageUi(
            id = "tool-1",
            toolName = "todo_write",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "",
        )
        assertNull(pendingAskOf(listOf(other)))
    }
}
