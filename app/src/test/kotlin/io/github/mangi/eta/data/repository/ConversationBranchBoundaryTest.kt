package io.github.mangi.eta.data.repository

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
}
