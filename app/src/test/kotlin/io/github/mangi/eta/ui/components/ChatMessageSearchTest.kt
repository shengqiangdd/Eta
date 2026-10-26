package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内搜索：命中范围和顺序。
 *
 * 这里守的是「搜得到什么」。规则本身与会话筛选共用 `matches`，所以两侧只要有一边被改坏，
 * 这些断言会一起红。
 */
class ChatMessageSearchTest {

    private val noticeText: (SystemNoticeCode) -> String = { code ->
        when (code) {
            SystemNoticeCode.Stopped -> "Stopped"
            SystemNoticeCode.EmptyResult -> "Empty result"
            SystemNoticeCode.ContextCompaction -> "上下文压缩"
            SystemNoticeCode.ModelRetry -> "Retrying"
            SystemNoticeCode.RuntimeFailed -> "Runtime failed"
            SystemNoticeCode.Interrupted -> "Interrupted"
        }
    }

    private fun idsOf(messages: List<AgentChatMessageUi>, query: String): List<String> =
        matchMessageIds(messages, query, noticeText)

    @Test
    fun `空查询不返回任何命中`() {
        val messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "u", content = "你好"))
        assertEquals(emptyList<String>(), idsOf(messages, ""))
        assertEquals(emptyList<String>(), idsOf(messages, "   "))
    }

    @Test
    fun `按出现顺序返回命中的消息 id`() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "u1", content = "帮我查签名"),
            AgentMessageUi(id = "a1", content = "签名是 abc"),
            ToolActivityMessageUi(
                id = "t1",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "apksigner verify",
                resultSummary = "签名通过",
            ),
        )

        assertEquals(listOf("u1", "a1", "t1"), idsOf(messages, "签名"))
    }

    @Test
    fun `大小写不敏感`() {
        val messages: List<AgentChatMessageUi> = listOf(
            AgentMessageUi(id = "a1", content = "DeepSeek V4 很强"),
        )
        assertEquals(listOf("a1"), idsOf(messages, "deepseek"))
    }

    @Test
    fun `系统通知按当前语言文案匹配`() {
        val messages: List<AgentChatMessageUi> = listOf(
            SystemNoticeMessageUi(id = "n1", code = SystemNoticeCode.ContextCompaction),
        )
        assertEquals(listOf("n1"), idsOf(messages, "压缩"))
        assertEquals(emptyList<String>(), idsOf(messages, "Stopped"))
    }

    @Test
    fun `没有命中时返回空列表`() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "u1", content = "你好"),
            AgentMessageUi(id = "a1", content = "你好，有什么可以帮你"),
        )
        assertEquals(emptyList<String>(), idsOf(messages, "不存在的词"))
    }

    @Test
    fun `位置标签在没有命中时为空`() {
        assertEquals("", searchUi(matchCount = 0, cursor = 0).positionLabel)
    }

    @Test
    fun `位置标签从 1 开始计数`() {
        assertEquals("1 / 3", searchUi(matchCount = 3, cursor = 0).positionLabel)
        assertEquals("3 / 3", searchUi(matchCount = 3, cursor = 2).positionLabel)
    }

    @Test
    fun `越界游标被夹到有效范围`() {
        assertEquals("3 / 3", searchUi(matchCount = 3, cursor = 9).positionLabel)
        assertEquals("1 / 3", searchUi(matchCount = 3, cursor = -2).positionLabel)
    }

    private fun searchUi(matchCount: Int, cursor: Int) = ChatSearchUi(
        query = "x",
        matchCount = matchCount,
        cursor = cursor,
        hiddenMatchCount = 0,
        onQueryChange = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )

    @Test
    fun `折叠块内命中计数只数本块自己`() {
        val block: List<AgentChatMessageUi> = listOf(
            ToolActivityMessageUi(
                id = "t1",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "rg IgnoreRules",
                resultSummary = "3 处",
            ),
            ToolActivityMessageUi(
                id = "t2",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "cat notes.txt",
                resultSummary = "无关内容",
            ),
        )

        // 别的块里的命中（other）不能算进这一块，否则摘要会虚报。
        assertEquals(1, countProcessBlockMatches(block, setOf("t1", "other")))
        assertEquals(2, countProcessBlockMatches(block, setOf("t1", "t2")))
        assertEquals(0, countProcessBlockMatches(block, setOf("other")))
    }

    @Test
    fun `没有搜索时不显示块内命中`() {
        val block: List<AgentChatMessageUi> = listOf(
            ToolActivityMessageUi(
                id = "t1",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "rg IgnoreRules",
                resultSummary = "3 处",
            ),
        )

        assertEquals(0, countProcessBlockMatches(block, emptySet()))
        assertEquals(0, countProcessBlockMatches(emptyList(), setOf("t1")))
    }

    @Test
    fun `块内当前命中序号按消息顺序数`() {
        val block: List<AgentChatMessageUi> = listOf(
            ToolActivityMessageUi(
                id = "t1",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "rg IgnoreRules",
                resultSummary = "3 处",
            ),
            ToolActivityMessageUi(
                id = "t2",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "rg base",
                resultSummary = "1 处",
            ),
            ToolActivityMessageUi(
                id = "t3",
                toolName = "terminal",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                command = "cat notes.txt",
                resultSummary = "无关内容",
            ),
        )
        val matches = setOf("t1", "t2")

        assertEquals(1, processBlockHitPosition(block, matches, "t1"))
        assertEquals(2, processBlockHitPosition(block, matches, "t2"))
        // 当前命中不在本块、没在搜索、块内无命中，三种情况都不能编出序号。
        assertEquals(0, processBlockHitPosition(block, matches, "t3"))
        assertEquals(0, processBlockHitPosition(block, matches, null))
        assertEquals(0, processBlockHitPosition(block, emptySet(), "t1"))
        assertEquals(0, processBlockHitPosition(emptyList(), matches, "t1"))
    }

    @Test
    fun `命中定位能认出步骤里的那一条`() {
        val toolA = ToolActivityMessageUi(
            id = "t1",
            toolName = "terminal",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "{}",
            command = "rg a",
            resultSummary = "1 处",
        )
        val toolB = ToolActivityMessageUi(
            id = "t2",
            toolName = "terminal",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "{}",
            command = "rg b",
            resultSummary = "2 处",
        )
        val single: AgentWorkStep = AgentWorkStep.Single(toolA)
        val group: AgentWorkStep = AgentWorkStep.ToolGroup(
            toolName = "terminal",
            calls = listOf(toolA, toolB),
        )

        assertTrue(single.containsMessage("t1"))
        assertFalse(single.containsMessage("t2"))
        // 同名工具会被合并成一个步骤：组里任意一条命中都要认，否则展开后找不到那一行。
        assertTrue(group.containsMessage("t2"))
        assertFalse(group.containsMessage("t3"))
        assertFalse(group.containsMessage(null))
    }
}
