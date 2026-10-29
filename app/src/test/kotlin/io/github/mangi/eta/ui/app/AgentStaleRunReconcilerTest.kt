package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStaleRunReconcilerTest {

    @Test
    fun emptyLocalStateHasNothingStale() {
        val stale = find()
        assertTrue(stale.isEmpty)
        assertTrue(stale.runIds.isEmpty())
        assertTrue(stale.conversationIds.isEmpty())
    }

    /** 真机上「停止没反应」的形态：本地有映射，服务端快照里已经没有这条 run。 */
    @Test
    fun runMissingFromSnapshotIsStaleAlongWithItsConversation() {
        val stale = find(
            localRunToConversation = mapOf("run-dead" to "conv-1"),
            streamingConversationIds = setOf("conv-1"),
        )
        assertEquals(setOf("run-dead"), stale.runIds)
        assertEquals(setOf("conv-1"), stale.conversationIds)
        assertFalse(stale.isEmpty)
    }

    @Test
    fun runReportedByServerIsKept() {
        val stale = find(
            localRunToConversation = mapOf("run-live" to "conv-1"),
            streamingConversationIds = setOf("conv-1"),
            liveRunIds = setOf("run-live"),
            liveConversationIds = setOf("conv-1"),
        )
        assertTrue(stale.isEmpty)
    }

    @Test
    fun runWithPendingCheckpointIsKept() {
        val stale = find(
            localRunToConversation = mapOf("run-pending" to "conv-1"),
            streamingConversationIds = setOf("conv-1"),
            pendingRunIds = setOf("run-pending"),
        )
        assertTrue(stale.isEmpty)
    }

    @Test
    fun currentlyFollowedRunIsKept() {
        val stale = find(
            localRunToConversation = mapOf("run-current" to "conv-1"),
            streamingConversationIds = setOf("conv-1"),
            currentRunId = "run-current",
        )
        assertTrue(stale.isEmpty)
    }

    /** 没有映射、但界面仍标着运行中 —— 所有针对 run 的操作都会静默落空。 */
    @Test
    fun streamingConversationWithoutRunIsStale() {
        val stale = find(streamingConversationIds = setOf("conv-1"))
        assertTrue(stale.runIds.isEmpty())
        assertEquals(setOf("conv-1"), stale.conversationIds)
    }

    /** 多个会话并发：只清服务端不认的那一个，另一个必须保留。 */
    @Test
    fun onlyStaleConversationIsCleared() {
        val stale = find(
            localRunToConversation = mapOf("run-dead" to "conv-1", "run-live" to "conv-2"),
            streamingConversationIds = setOf("conv-1", "conv-2"),
            liveRunIds = setOf("run-live"),
            liveConversationIds = setOf("conv-2"),
        )
        assertEquals(setOf("run-dead"), stale.runIds)
        assertEquals(setOf("conv-1"), stale.conversationIds)
    }

    /**
     * 会话被一条活着的 run 跟踪时不能清：哪怕它自己也标着 streaming。
     * 这里同时验证「保护只看清理之后仍然存在的 run」。
     */
    @Test
    fun conversationTrackedByLiveRunSurvives() {
        val stale = find(
            localRunToConversation = mapOf("run-dead" to "conv-1", "run-live" to "conv-2"),
            streamingConversationIds = setOf("conv-2"),
            liveRunIds = setOf("run-live"),
        )
        assertEquals(setOf("run-dead"), stale.runIds)
        assertTrue(stale.conversationIds.isEmpty())
    }

    private fun find(
        localRunToConversation: Map<String, String> = emptyMap(),
        streamingConversationIds: Set<String> = emptySet(),
        liveRunIds: Set<String> = emptySet(),
        liveConversationIds: Set<String> = emptySet(),
        pendingRunIds: Set<String> = emptySet(),
        currentRunId: String? = null,
    ): AgentStaleRunReconciler.Stale = AgentStaleRunReconciler.find(
        localRunToConversation = localRunToConversation,
        streamingConversationIds = streamingConversationIds,
        liveRunIds = liveRunIds,
        liveConversationIds = liveConversationIds,
        pendingRunIds = pendingRunIds,
        currentRunId = currentRunId,
    )
}