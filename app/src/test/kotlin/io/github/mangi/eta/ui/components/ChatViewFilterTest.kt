package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.CapabilityUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 聊天页三种视图的过滤规则。
 *
 * 这些断言守的是「哪些消息会被收起来」这件事本身 —— 一旦有人新增消息类型，默认走 else
 * 分支被保留是安全的；但如果有人把某个正文类型写进过程消息（或反过来），这里会立刻红。
 */
class ChatViewFilterTest {
    private fun user(id: String) = UserMessageUi(id = id, content = "问 $id")

    private fun agent(id: String) = AgentMessageUi(id = id, content = "答 $id")

    private fun thinking(id: String) = ThinkingMessageUi(id = id, content = "想", isStreaming = false)

    private fun toolActivity(id: String) = ToolActivityMessageUi(
        id = id,
        toolName = "search",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "{}",
    )

    private fun toolSummary(id: String) = ToolSummaryMessageUi(id = id, tools = listOf("search"))

    private fun runTrace(id: String) = RunTraceMessageUi(
        id = id,
        capabilities = listOf(CapabilityUi(title = "搜索", items = listOf("search"))),
    )

    private fun notice(id: String) = SystemNoticeMessageUi(id = id, code = SystemNoticeCode.Stopped)

    private fun chips(id: String) = SuggestionChipsMessageUi(id = id, prompts = listOf("继续"))

    private fun mixed(): List<AgentChatMessageUi> = listOf(
        user("u1"),
        thinking("t1"),
        agent("m1"),
        toolActivity("a1"),
        toolSummary("s1"),
        runTrace("r1"),
        notice("n1"),
        chips("c1"),
        user("u2"),
    )

    @Test
    fun `过程消息四类都判为可隐藏`() {
        assertEquals(true, isProcessMessage(thinking("t")))
        assertEquals(true, isProcessMessage(toolActivity("a")))
        assertEquals(true, isProcessMessage(toolSummary("s")))
        assertEquals(true, isProcessMessage(runTrace("r")))
    }

    @Test
    fun `对话主干与解释性消息都不隐藏`() {
        assertEquals(false, isProcessMessage(user("u")))
        assertEquals(false, isProcessMessage(agent("m")))
        assertEquals(false, isProcessMessage(notice("n")))
        assertEquals(false, isProcessMessage(chips("c")))
    }

    @Test
    fun `对话视图只掉过程消息且顺序不变`() {
        assertEquals(
            listOf("u1", "m1", "n1", "c1", "u2"),
            filterForViewMode(mixed(), ChatViewMode.DIALOG).map { it.id },
        )
    }

    @Test
    fun `轨迹视图只掉正文与推荐提问且顺序不变`() {
        // 用户提问是回合锚点、系统提示是对结果的解释，两者都必须留下 —— 否则「哪一步属于
        // 哪一问」「为什么中途停了」都会失去依据。
        assertEquals(
            listOf("u1", "t1", "a1", "s1", "r1", "n1", "u2"),
            filterForViewMode(mixed(), ChatViewMode.TRACE).map { it.id },
        )
    }

    @Test
    fun `全部模式原样返回同一实例`() {
        val messages = mixed()
        assertSame(messages, filterForViewMode(messages, ChatViewMode.ALL))
        assertEquals(0, countHiddenByViewMode(messages, ChatViewMode.ALL))
    }

    @Test
    fun `没有过程消息时对话视图返回同一实例`() {
        val messages: List<AgentChatMessageUi> = listOf(user("u1"), agent("m1"))
        assertSame(messages, filterForViewMode(messages, ChatViewMode.DIALOG))
    }

    @Test
    fun `没有正文时轨迹视图返回同一实例`() {
        val messages: List<AgentChatMessageUi> = listOf(user("u1"), thinking("t1"), notice("n1"))
        assertSame(messages, filterForViewMode(messages, ChatViewMode.TRACE))
    }

    @Test
    fun `空列表在三种模式下都返回同一实例`() {
        val messages: List<AgentChatMessageUi> = emptyList()
        ChatViewMode.entries.forEach { mode ->
            assertSame(messages, filterForViewMode(messages, mode))
            assertEquals(0, countHiddenByViewMode(messages, mode))
        }
    }

    @Test
    fun `计数按视图分别统计过程消息与正文`() {
        assertEquals(4, countHiddenByViewMode(mixed(), ChatViewMode.DIALOG))
        assertEquals(2, countHiddenByViewMode(mixed(), ChatViewMode.TRACE))
    }

    @Test
    fun `轨迹视图把只有正文的一屏收成空`() {
        // 这一屏全是被轨迹视图收起的正文时，界面必须走「被收起来了」的空状态，
        // 而不是落到「这个会话还没有内容」。
        val messages: List<AgentChatMessageUi> = listOf(agent("m1"), chips("c1"))
        assertEquals(emptyList<String>(), filterForViewMode(messages, ChatViewMode.TRACE).map { it.id })
    }
}
