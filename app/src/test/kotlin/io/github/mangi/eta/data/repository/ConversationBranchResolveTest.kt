package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ConversationBranchMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 分叉点解析：把「界面上的消息」映射回「模型层的 journal 前缀」。
 *
 * `resolve()` 是分叉真正落地的地方，也是错得最不显眼的地方 —— 它要同时对上三套标识：
 * 界面的消息 id、journal 里的 `messageId`、以及 Roleplay 修订表的 `transcriptMessageId`。
 * 对不上不会崩，只会把错误的前缀复制进分支，用户看到的是「分叉结果少了一轮」。
 *
 * 不依赖 Android：只用 JSON 与两个纯数据类，所以能在 Linux 上跑。
 */
class ConversationBranchResolveTest {

    private fun msg(
        role: String,
        id: String,
        content: String = "正文",
        contextSummary: Boolean = false,
        compactedUserTurns: Int = 0,
        summaryThroughUserTurn: Int = 0,
        toolCallsJson: String = "",
        toolCallId: String = "",
    ) = ConversationMessage(
        role = role,
        content = content,
        contentJson = "",
        toolCallId = toolCallId,
        toolCallsJson = toolCallsJson,
        contextSummary = contextSummary,
        compactedUserTurns = compactedUserTurns,
        summaryThroughUserTurn = summaryThroughUserTurn,
        messageId = id,
    )

    private fun boundaryMsg(id: String, type: String) = ConversationBranchMessage(
        id = id,
        sortIndex = 0,
        type = type,
        hasContent = true,
        noticeCode = "",
    )

    /** 一轮完整对话：u1 → a1。 */
    private fun oneTurn(assistantId: String = "a1") = listOf(
        msg("user", "u1"),
        msg("assistant", assistantId),
    )

    @Test
    fun `能解析到闭合的那一轮前缀`() {
        val journal = oneTurn()
        val prefix = ConversationBranchBoundary.resolve(
            messages = listOf(boundaryMsg("u1", "user"), boundaryMsg("a1", "assistant")),
            journal = journal,
            roleplay = RoleplayMessageState(),
            targetId = "a1",
        )
        assertEquals(listOf("u1", "a1"), prefix.map { it.messageId })
    }

    @Test
    fun `消息不在候选集合时直接拒绝`() {
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(boundaryMsg("u1", "user")),
                journal = oneTurn(),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `界面 id 带轮次后缀时按最长前缀匹配到 journal 消息`() {
        // 界面上的 id 常是 `a1-3-2` 这种带轮次的形态，journal 里只有 `a1`。
        val prefix = ConversationBranchBoundary.resolve(
            messages = listOf(boundaryMsg("a1-3-2", "assistant")),
            journal = oneTurn(),
            roleplay = RoleplayMessageState(),
            targetId = "a1-3-2",
        )
        assertEquals(listOf("u1", "a1"), prefix.map { it.messageId })
    }

    @Test
    fun `Roleplay 修订过的回复按 transcriptMessageId 定位`() {
        // 角色扮演的正文可能被改写，link 把界面 id 指回原始 transcript。
        val roleplay = RoleplayMessageState(
            links = mapOf("a1" to RoleplayMessageLink(transcriptMessageId = "orig-7")),
        )
        val prefix = ConversationBranchBoundary.resolve(
            messages = listOf(boundaryMsg("a1", "assistant")),
            journal = listOf(msg("user", "u1"), msg("assistant", "orig-7")),
            roleplay = roleplay,
            targetId = "a1",
        )
        assertEquals(listOf("u1", "orig-7"), prefix.map { it.messageId })
    }

    @Test
    fun `匹配不到唯一 journal 消息时拒绝而不是猜一个`() {
        // 两个候选长度相同且都命中 → 无法确定，必须拒绝。
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(boundaryMsg("a1", "assistant")),
                journal = listOf(
                    msg("user", "u1"),
                    msg("assistant", "a1"),
                    msg("user", "u2"),
                    msg("assistant", "a1"), // 重复 id，真实数据不该有，但一旦出现必须拒绝
                ),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `目标之后还有未结束的助手输出时拒绝`() {
        // journal 在目标之后还有 assistant/tool，说明这一轮没结束。
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(boundaryMsg("a1", "assistant")),
                journal = listOf(
                    msg("user", "u1"),
                    msg("assistant", "a1"),
                    msg("assistant", "a1-late"),
                ),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `前缀末尾不是助手回复时拒绝`() {
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(
                    boundaryMsg("u1", "user"),
                    boundaryMsg("a1", "assistant"),
                ),
                journal = listOf(msg("user", "u1"), msg("assistant", "a1"), msg("user", "u2")),
                roleplay = RoleplayMessageState(),
                targetId = "u1",
            )
        }
    }

    @Test
    fun `助手回复正文为空时拒绝`() {
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(
                    boundaryMsg("u1", "user"),
                    boundaryMsg("a1", "assistant"),
                ),
                journal = listOf(msg("user", "u1"), msg("assistant", "a1", content = "   ")),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `工具调用没有配对结果时拒绝`() {
        // assistant 发起了 tool_call，但前缀里没有对应的 tool 结果。
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(
                    boundaryMsg("u1", "user"),
                    boundaryMsg("a1", "assistant"),
                ),
                journal = listOf(
                    msg("user", "u1"),
                    msg(
                        "assistant",
                        "a1",
                        toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}]""",
                    ),
                ),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `工具调用配对完整时允许在终稿处分叉`() {
        // 真实形态：助手先发 tool_call，工具结果回来，助手再写终稿。分叉点在终稿 a2，
        // 此时 a1 的调用与 t1 的结果都在前缀内，闭环。
        val prefix = ConversationBranchBoundary.resolve(
            messages = listOf(
                boundaryMsg("u1", "user"),
                boundaryMsg("a2", "assistant"),
            ),
            journal = listOf(
                msg("user", "u1"),
                msg(
                    "assistant",
                    "a1",
                    toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}]""",
                ),
                msg("tool", "t1", content = "结果", toolCallId = "call_1"),
                msg("assistant", "a2"),
            ),
            roleplay = RoleplayMessageState(),
            targetId = "a2",
        )
        assertEquals(listOf("u1", "a1", "t1", "a2"), prefix.map { it.messageId })
    }

    @Test
    fun `在工具调用未完成时那条消息上分叉会被拒绝`() {
        // 分叉点不能选在「刚发出 tool_call、结果还没回来」的地方：
        // 那样得到的分支里工具永远等不到结果，下一轮必然报错。
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(
                    boundaryMsg("u1", "user"),
                    boundaryMsg("a1", "assistant"),
                ),
                journal = listOf(
                    msg("user", "u1"),
                    msg(
                        "assistant",
                        "a1",
                        toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}]""",
                    ),
                    msg("tool", "t1", content = "结果", toolCallId = "call_1"),
                ),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }

    @Test
    fun `上下文压缩越界时拒绝`() {
        // summaryThroughUserTurn 指到前缀里还不存在的用户轮次 → 被压缩掉的历史不完整。
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(
                messages = listOf(
                    boundaryMsg("u1", "user"),
                    boundaryMsg("a1", "assistant"),
                ),
                journal = listOf(
                    msg("user", "u1"),
                    msg(
                        "assistant",
                        "a1",
                        contextSummary = true,
                        summaryThroughUserTurn = 9,
                    ),
                ),
                roleplay = RoleplayMessageState(),
                targetId = "a1",
            )
        }
    }
}
