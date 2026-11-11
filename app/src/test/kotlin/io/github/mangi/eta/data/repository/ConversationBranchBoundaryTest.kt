package io.github.mangi.eta.data.repository

<<<<<<< HEAD
import io.github.mangi.eta.data.model.ConversationBranchMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分叉候选的判定规则。
 *
 * 只覆盖不碰 Android 的部分：`resolve()` 依赖 org.json 与 Roleplay 状态，需要 Robolectric
 * 才能跑，而本机（Linux aarch64）跑不了。`eligibleMessageIds` 与 `remapId` 是纯函数，
 * 规则又最容易写错，所以在纯 JVM 里钉住。
 *
 * 这里的断言是照着「用户能看见什么」写的，不是照实现抄的：每条失败信息都要能对上
 * 一个真实的误操作场景，否则测的只是当前写法。
 */
class ConversationBranchBoundaryTest {

    private fun message(
        id: String,
        type: String,
        hasContent: Boolean = true,
        noticeCode: String = "",
    ) = ConversationBranchMessage(
        id = id,
        sortIndex = 0,
        type = type,
        hasContent = hasContent,
        noticeCode = noticeCode,
    )

    @Test
    fun `助手回复被下一条用户消息闭合后成为候选`() {
        val ids = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant"),
                message("u2", "user"),
                message("a2", "assistant"),
            ),
        )
        // a1 已被 u2 闭合；a2 是末尾且非运行态，也可以分叉。
        assertEquals(setOf("a1", "a2"), ids)
    }

    @Test
    fun `运行中时末尾助手回复不可分叉`() {
        val messages = listOf(message("u1", "user"), message("a1", "assistant"))
        assertEquals(setOf("a1"), ConversationBranchBoundary.eligibleMessageIds(messages, running = false))
        // 运行中这条可能还在输出，把过渡正文当最终结果会分叉出半截对话。
        assertTrue(ConversationBranchBoundary.eligibleMessageIds(messages, running = true).isEmpty())
    }

    @Test
    fun `空的助手回复不算候选`() {
        val ids = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant", hasContent = false),
                message("u2", "user"),
            ),
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `工具摘要不闭合轮次，只有其后的助手回复才是候选`() {
        val ids = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant"),
                message("t1", "tool_summary"),
                message("a2", "assistant"),
                message("u2", "user"),
                message("a3", "assistant"),
            ),
        )
        // t1 不闭合 a1，a2 覆盖了候选位置，所以 a1 不是候选 —— 分叉 a1 会漏掉它后面的工具过程。
        assertEquals(setOf("a2", "a3"), ids)
    }

    @Test
    fun `上下文压缩提示不算轮次边界，其它系统提示算`() {
        val withCompaction = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant"),
                message("n1", "system_notice", noticeCode = "context_compaction"),
                message("u2", "user"),
            ),
        )
        // 压缩提示是全局事件，不该把前一轮的回复作废。
        assertEquals(setOf("a1"), withCompaction)

        val withOtherNotice = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant"),
                message("n1", "system_notice", noticeCode = "runtime_failed"),
                message("u2", "user"),
            ),
        )
        // 失败/中断这类提示之后，前面那轮的结果不可靠，不能作为分叉点。
        assertTrue(withOtherNotice.isEmpty())
    }

    @Test
    fun `补充输入会作废此前未闭合的助手回复`() {
        val ids = ConversationBranchBoundary.eligibleMessageIds(
            listOf(
                message("u1", "user"),
                message("a1", "assistant"),
                message("u2-supplement-1", "user"),
                message("a2", "assistant"),
                message("u3", "user"),
            ),
        )
        // 补充输入是同一轮次的延续，a1 的正文在补充到来时已不再是最终回答。
        assertEquals(setOf("a2"), ids)
    }

    @Test
    fun `分叉后的消息 id 保留原类型前缀并插入分支标识`() {
        assertEquals("assistant-b2-x1", ConversationBranchBoundary.remapId("b2", "assistant-x1"))
        assertEquals("user-b2-x2", ConversationBranchBoundary.remapId("b2", "user-x2"))
        assertEquals("greeting-b2-x", ConversationBranchBoundary.remapId("b2", "greeting-x"))
        // 没有已知前缀时整串接在分支 id 后，至少不会撞原会话的 id。
        assertEquals("b2-plain", ConversationBranchBoundary.remapId("b2", "plain"))
        assertEquals("", ConversationBranchBoundary.remapId("b2", ""))
    }
=======
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ConversationBranchMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationBranchBoundaryTest {
    private val emptyRole = RoleplayMessageState()

    @Test
    fun onlyClosedFinalRepliesAreCandidates() {
        val rows = outline("user" to "u1", "assistant" to "a1", "tool" to "t1", "assistant" to "a2",
            "user" to "u2", "assistant" to "a3", "system_notice" to "failed")
        assertEquals(setOf("a2"), ConversationBranchBoundary.eligibleMessageIds(rows))
        assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(rows.take(4), running = true))
        for (code in listOf("stopped", "runtime_failed", "interrupted", "empty_result", "model_retry")) {
            assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(
                outline("user" to "u", "assistant" to "a") + ConversationBranchMessage("notice", 2, "system_notice", true, code)))
        }
    }

    @Test
    fun supplementsDoNotMakeAnIntermediateReplyBranchable() {
        val rows = outline("user" to "user-run", "assistant" to "assistant-run-1-0",
            "user" to "user-run-supplement-1", "assistant" to "assistant-run-2-0")
        assertEquals(setOf("assistant-run-2-0"), ConversationBranchBoundary.eligibleMessageIds(rows))
        assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(rows, running = true))
    }

    @Test
    fun stableIdsIncludeAllBlocksAndToolsButExcludeLaterTurns() {
        val rows = outline("user" to "u1", "tool" to "tool", "assistant" to "assistant-r-2-0",
            "assistant" to "assistant-r-2-1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user("u1"), call(), result(), assistant("assistant-r-2", "第一块\n\n第二块"),
            user("u2"), assistant("a2", "后续内容"))
        assertEquals(journal.take(4), ConversationBranchBoundary.resolve(rows, journal, emptyRole, "assistant-r-2-1"))
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "assistant-r-2-0")
        }
    }

    @Test
    fun repeatedTextDoesNotChangeTheSelectedBoundary() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(journal.take(2), ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a1"))
        assertEquals(journal, ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a2"))
    }

    @Test
    fun missingOrDuplicateToolResultsCannotBecomeAClosedPrefix() {
        val rows = outline("user" to "u", "assistant" to "a")
        for (journal in listOf(
            listOf(user("u"), call(), assistant("a")),
            listOf(user("u"), call(), result(), result(), assistant("a")),
            listOf(user("u"), assistant("a"), call(), result()),
            listOf(user("u"), call().copy(messageId = "a", content = "仍在调用工具")),
        )) assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a")
        }
    }

    @Test
    fun historyWithoutStableIdsIsRejected() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user(""), assistant(""), user(""), assistant(""))
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal.drop(2), emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal.toMutableList().apply { this[0] = user("wrong-user") }, emptyRole, "a1")
        }
    }

    @Test
    fun oldSummaryCannotLeakTurnsPastTheCut() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val summary = ConversationMessage("assistant", "未来内容", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, listOf(summary, user("u2"), assistant("a2")), emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, listOf(summary, assistant("a1")), emptyRole, "a1")
        }
    }

    @Test
    fun greetingAndRemappedBlockIdsRemainBranchable() {
        val greeting = "greeting-source"
        assertEquals(listOf(assistant(greeting)), ConversationBranchBoundary.resolve(
            outline("assistant" to greeting), listOf(assistant(greeting)), emptyRole, greeting))
        val transcriptId = ConversationBranchBoundary.remapId("child", "assistant-r-1")
        val blockId = ConversationBranchBoundary.remapId("child", "assistant-r-1-0")
        val journal = listOf(assistant(transcriptId))
        assertEquals(journal, ConversationBranchBoundary.resolve(outline("assistant" to blockId), journal, emptyRole, blockId))
        assertEquals("greeting-child-source", ConversationBranchBoundary.remapId("child", greeting))
    }

    private fun outline(vararg entries: Pair<String, String>) = entries.mapIndexed { index, (type, id) ->
        ConversationBranchMessage(id, index, type, true)
    }
    private fun user(id: String) = ConversationMessage("user", "相同问题", messageId = id)
    private fun assistant(id: String, content: String = "相同回答") = ConversationMessage("assistant", content, messageId = id)
    private fun call() = ConversationMessage("assistant", toolCallsJson = """[{"id":"tool-1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""")
    private fun result() = ConversationMessage("tool", "完成", toolCallId = "tool-1")
>>>>>>> origin/main
}
