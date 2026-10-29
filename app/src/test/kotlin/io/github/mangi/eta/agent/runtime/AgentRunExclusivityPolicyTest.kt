package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunExclusivityPolicyTest {

    @Test
    fun `same conversation older run is superseded`() {
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = "conv-a",
            active = mapOf("run-old" to "conv-a"),
        )
        assertEquals(listOf("run-old"), stale)
    }

    @Test
    fun `other conversations keep running`() {
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = "conv-a",
            active = mapOf(
                "run-other" to "conv-b",
                "run-same" to "conv-a",
            ),
        )
        assertEquals(listOf("run-same"), stale)
    }

    @Test
    fun `terminal run is left alone`() {
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = "conv-a",
            active = mapOf("run-done" to "conv-a"),
            terminal = setOf("run-done"),
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `incoming run never supersedes itself`() {
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = "conv-a",
            active = mapOf("run-new" to "conv-a"),
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `unknown conversation never triggers cancellation`() {
        // 不是 App 内发起的 run（例如压缩、后台归档）没有会话归属，不能误杀任何东西。
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = null,
            active = mapOf("run-old" to null, "run-a" to "conv-a"),
        )
        assertTrue(stale.isEmpty())
        assertTrue(
            AgentRunExclusivityPolicy.supersededRuns(
                incomingRunId = "run-new",
                incomingConversationId = "   ",
                active = mapOf("run-old" to null),
            ).isEmpty()
        )
    }

    @Test
    fun `run without conversation id is never cancelled`() {
        val stale = AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = "run-new",
            incomingConversationId = "conv-a",
            active = mapOf("run-unknown" to null),
        )
        assertTrue(stale.isEmpty())
    }
}
