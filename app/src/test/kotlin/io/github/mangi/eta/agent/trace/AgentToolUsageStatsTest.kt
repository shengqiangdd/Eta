package io.github.mangi.eta.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolUsageStatsTest {

    private fun tool(
        name: String,
        elapsedMs: Long,
        ok: Boolean = true,
    ) = AgentRunTraceStore.Entry(
        kind = AgentRunTraceStore.KIND_TOOL,
        at = 1_000 + elapsedMs,
        name = name,
        ok = ok,
        elapsedMs = elapsedMs,
    )

    private fun trace(vararg entries: AgentRunTraceStore.Entry) = AgentRunTraceStore.Trace(
        runId = "run",
        model = "m",
        provider = "p",
        purpose = "chat",
        startedAt = 1_000,
        finishedAt = 10_000,
        ok = true,
        error = null,
        entries = entries.toList(),
        truncated = false,
    )

    @Test
    fun `aggregates calls failures and average time per tool`() {
        val stats = AgentToolUsageStats.aggregate(
            listOf(
                trace(
                    tool("terminal", 100),
                    tool("terminal", 300, ok = false),
                    tool("read_file", 50),
                ),
                trace(
                    tool("terminal", 200),
                ),
            ),
        )
        assertEquals(2, stats.runs)
        assertEquals(4, stats.calls)
        assertEquals(1, stats.failures)
        val terminal = stats.tools.first { it.name == "terminal" }
        assertEquals(3, terminal.calls)
        assertEquals(1, terminal.failures)
        assertEquals(600, terminal.totalMs)
        assertEquals(200, terminal.avgMs)
        assertEquals(300, terminal.maxMs)
        val read = stats.tools.first { it.name == "read_file" }
        assertEquals(1, read.calls)
    }

    @Test
    fun `sorts by call count and limits top tools`() {
        val stats = AgentToolUsageStats.aggregate(
            listOf(
                trace(
                    tool("a", 10),
                    tool("b", 20),
                    tool("b", 30),
                    tool("c", 40),
                    tool("c", 50),
                    tool("c", 60),
                ),
            ),
            topTools = 2,
        )
        assertEquals(2, stats.tools.size)
        assertEquals(listOf("c", "b"), stats.tools.map { it.name })
    }

    @Test
    fun `empty traces produce no data`() {
        val stats = AgentToolUsageStats.aggregate(emptyList())
        assertFalse(stats.hasData)
        assertEquals(0, stats.calls)
        assertTrue(stats.tools.isEmpty())
    }

    @Test
    fun `unnamed tool is bucketed`() {
        val stats = AgentToolUsageStats.aggregate(
            listOf(trace(tool("", 10))),
        )
        assertEquals("(未命名工具)", stats.tools.single().name)
    }
}
