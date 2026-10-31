package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 虚拟屏归属判定。
 *
 * 这些用例守的是一个**用户体验**问题：另一个对话的屏不该出现在「我的」列表里，
 * 而同一对话跨轮建的屏必须仍然算我的 —— 否则用户切回那个对话就再也管不到它。
 */
class VirtualDisplayOwnershipTest {
    private fun match(
        ownerConversationId: String = "",
        ownerRunId: String = "",
        currentConversationId: String = "",
        currentRunId: String = "",
    ) = VirtualDisplayOwnership.match(
        ownerConversationId = ownerConversationId,
        ownerRunId = ownerRunId,
        currentConversationId = currentConversationId,
        currentRunId = currentRunId,
    )

    @Test
    fun sameConversationAcrossRunsIsSelf() {
        // 同一对话的上一轮建的屏：runId 不同也必须算自己的。
        assertEquals(
            VirtualDisplayOwnership.Match.SELF,
            match(
                ownerConversationId = "conv-1",
                ownerRunId = "run-1",
                currentConversationId = "conv-1",
                currentRunId = "run-2",
            ),
        )
    }

    @Test
    fun otherConversationIsOther() {
        assertEquals(
            VirtualDisplayOwnership.Match.OTHER,
            match(
                ownerConversationId = "conv-1",
                ownerRunId = "run-1",
                currentConversationId = "conv-2",
                currentRunId = "run-2",
            ),
        )
    }

    @Test
    fun conversationScreenIsOtherWhenCurrentHasNoConversation() {
        // 语音 / 一次性入口没有 conversationId：别人对话的屏一律算别人的，不能顺手拆掉。
        assertEquals(
            VirtualDisplayOwnership.Match.OTHER,
            match(
                ownerConversationId = "conv-1",
                ownerRunId = "run-1",
                currentConversationId = "",
                currentRunId = "run-2",
            ),
        )
    }

    @Test
    fun sameRunWithoutConversationIsSelf() {
        assertEquals(
            VirtualDisplayOwnership.Match.SELF,
            match(ownerRunId = "run-1", currentRunId = "run-1"),
        )
    }

    @Test
    fun otherRunWithoutConversationIsOther() {
        assertEquals(
            VirtualDisplayOwnership.Match.OTHER,
            match(ownerRunId = "run-1", currentRunId = "run-2"),
        )
    }

    @Test
    fun unknownRunWithoutConversationIsOther() {
        // 归到「未知运行」的屏也不能当成自己的：宁可多问一次，不要静默拆掉别人的。
        assertEquals(
            VirtualDisplayOwnership.Match.OTHER,
            match(ownerRunId = "run-1", currentConversationId = "conv-1", currentRunId = ""),
        )
    }

    @Test
    fun legacyScreenWithoutOwnerIsUnknown() {
        // 升级前建的屏没有归属信息：既不能当成自己的（会误释放），也不能当成别人的（会挡住收尾）。
        assertEquals(
            VirtualDisplayOwnership.Match.UNKNOWN,
            match(currentConversationId = "conv-1", currentRunId = "run-1"),
        )
    }

    @Test
    fun labelsAreStableForToolOutput() {
        // 标签会进模型上下文，改动等于改契约。
        assertEquals("self", VirtualDisplayOwnership.label(VirtualDisplayOwnership.Match.SELF))
        assertEquals("other", VirtualDisplayOwnership.label(VirtualDisplayOwnership.Match.OTHER))
        assertEquals("unknown", VirtualDisplayOwnership.label(VirtualDisplayOwnership.Match.UNKNOWN))
    }
}
