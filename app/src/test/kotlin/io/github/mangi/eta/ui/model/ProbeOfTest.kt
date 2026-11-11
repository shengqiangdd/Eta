package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [probeOf]：把追踪查询结果映射成界面状态。
 *
 * 这段以前埋在 `produceState` 的 lambda 里，没有测试覆盖 —— 而它决定了
 * 「有没有速度」「要不要告诉用户记录被清理了」。抽出来才测得到。
 */
class ProbeOfTest {

    private fun trace(entries: List<AgentRunTraceStore.Entry>) = AgentRunTraceStore.Trace(
        runId = "run-x",
        model = "m",
        provider = "p",
        purpose = "chat",
        startedAt = 0L,
        finishedAt = null,
        ok = true,
        error = null,
        entries = entries,
        truncated = false,
        conversationId = "conv-x",
    )

    @Test
    fun `Found 且追踪可用时带上耗时数据`() {
        val t = trace(
            listOf(
                AgentRunTraceStore.Entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
                AgentRunTraceStore.Entry(AgentRunTraceStore.KIND_FIRST, 1_300, round = 1),
            ),
        )
        val probe = probeOf(AgentRunTraceStore.LookupResult(AgentRunTraceStore.LookupStatus.Found, "run-x"), t)

        assertEquals(TraceLookupStatus.Found, probe.status)
        assertEquals(300, probe.timing?.firstTokenMs)
    }

    @Test
    fun `Missing 时不带数据，状态如实反映`() {
        val probe = probeOf(AgentRunTraceStore.LookupResult(AgentRunTraceStore.LookupStatus.Missing), null)

        assertEquals(TraceLookupStatus.Missing, probe.status)
        assertNull(probe.timing)
    }

    @Test
    fun `NoStore 与 NoConversation 分别映射`() {
        assertEquals(
            TraceLookupStatus.NoStore,
            probeOf(AgentRunTraceStore.LookupResult(AgentRunTraceStore.LookupStatus.NoStore), null).status,
        )
        assertEquals(
            TraceLookupStatus.NoConversation,
            probeOf(null, null).status,
        )
    }

    @Test
    fun `Found 但读文件失败时保留状态、不给数据`() {
        // 文件在 lookup 之后被回收（或读失败）：状态仍是 Found，
        // 但没有 timing —— 界面据此不显示数字，也不该声称「被回收」。
        val probe = probeOf(AgentRunTraceStore.LookupResult(AgentRunTraceStore.LookupStatus.Found, "run-x"), null)

        assertEquals(TraceLookupStatus.Found, probe.status)
        assertNull(probe.timing)
    }

    @Test
    fun `追踪里没有首字时 timing 仍存在但首字为 null`() {
        // 只有 request 没有 first：timing 不应为 null（会话确实跑过），
        // 但首字延迟要如实为空。
        val t = trace(listOf(AgentRunTraceStore.Entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1)))
        val probe = probeOf(AgentRunTraceStore.LookupResult(AgentRunTraceStore.LookupStatus.Found, "run-x"), t)

        assertEquals(TraceLookupStatus.Found, probe.status)
        assertNull(probe.timing?.firstTokenMs)
    }
}
