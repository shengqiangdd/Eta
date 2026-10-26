package io.github.mangi.eta.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNetworkRetryStatsTest {

    @Test
    fun aggregatesRetriesRecoveryGatewayReasonsAndWait() {
        val stats = AgentNetworkRetryStats.aggregate(
            listOf(
                trace(ok = true, retries = listOf("HTTP_522" to 5_000L, "HTTP_522" to 5_000L)),
                trace(ok = false, retries = listOf("MODEL_TIMEOUT" to 2_000L)),
                trace(ok = null, retries = listOf("HTTP_520" to 5_000L)),
                trace(ok = true, retries = emptyList()),
            ),
        )

        assertEquals(4, stats.runs)
        assertEquals(3, stats.retryRuns)
        assertEquals(4, stats.retries)
        assertEquals(1, stats.recoveredRuns)
        assertEquals(1, stats.failedRetryRuns)
        assertEquals(1, stats.unknownRuns)
        assertEquals(17_000L, stats.totalDelayMs)
        assertEquals(4_250L, stats.avgDelayMs)
        assertEquals(3, stats.gatewayRetries)
        assertEquals(0.5f, stats.recoveryRate, 0.0001f)
        assertEquals(2, stats.reasons.first().count)
        assertEquals("HTTP_522", stats.reasons.first().code)
        assertEquals(10_000L, stats.reasons.first().totalDelayMs)
        assertTrue(stats.hasData)
    }

    @Test
    fun emptyTracesHaveNoDataAndNoFakeRecovery() {
        val stats = AgentNetworkRetryStats.aggregate(emptyList())
        assertFalse(stats.hasData)
        assertEquals(0, stats.retries)
        assertEquals(0f, stats.recoveryRate, 0.0001f)
        assertTrue(stats.reasons.isEmpty())
    }

    private fun trace(ok: Boolean?, retries: List<Pair<String, Long>>): AgentRunTraceStore.Trace =
        AgentRunTraceStore.Trace(
            runId = "run-${ok}-${retries.size}",
            model = "test",
            provider = "test",
            purpose = "chat",
            startedAt = 1_000L,
            finishedAt = 2_000L,
            ok = ok,
            error = null,
            truncated = false,
            entries = retries.mapIndexed { index, (code, delay) ->
                AgentRunTraceStore.Entry(
                    kind = AgentRunTraceStore.KIND_RETRY,
                    at = 1_000L + index,
                    attempt = index + 1,
                    maxAttempts = 4,
                    delayMs = delay,
                    code = code,
                )
            },
        )
}
