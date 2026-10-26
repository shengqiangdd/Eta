package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRetryPolicyTest {
    @Test
    fun `exponential backoff doubles each attempt without jitter`() {
        val policy = AgentRetryPolicy(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        assertEquals(1_000, policy.delayMs(1, random = 0.5))
        assertEquals(2_000, policy.delayMs(2, random = 0.5))
        assertEquals(4_000, policy.delayMs(3, random = 0.5))
    }

    @Test
    fun `delay never exceeds the configured ceiling`() {
        val policy = AgentRetryPolicy(maxRetries = 8, baseDelayMs = 10_000, maxDelayMs = 30_000, jitterRatio = 0.0)
        assertEquals(30_000, policy.delayMs(6, random = 0.5))
    }

    @Test
    fun `server retry after wins when it is longer than local backoff`() {
        val policy = AgentRetryPolicy(baseDelayMs = 2_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        // 服务端要求 20 秒，本地只算 2 秒 → 必须按服务端等待。
        assertEquals(20_000, policy.delayMs(1, retryAfterSeconds = 20, random = 0.5))
    }

    @Test
    fun `local backoff wins when it is longer than a tiny retry after`() {
        val policy = AgentRetryPolicy(baseDelayMs = 8_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        assertEquals(8_000, policy.delayMs(1, retryAfterSeconds = 1, random = 0.5))
    }

    @Test
    fun `retry after above the ceiling is still honoured`() {
        val policy = AgentRetryPolicy(baseDelayMs = 2_000, maxDelayMs = 10_000, jitterRatio = 0.0)
        assertEquals(120_000, policy.delayMs(1, retryAfterSeconds = 120, random = 0.5))
    }

    @Test
    fun `untrusted retry after values are ignored`() {
        val policy = AgentRetryPolicy(baseDelayMs = 2_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        assertEquals(2_000, policy.delayMs(1, retryAfterSeconds = 100_000, random = 0.5))
        assertEquals(2_000, policy.delayMs(1, retryAfterSeconds = -5, random = 0.5))
    }

    @Test
    fun `jitter stays inside the configured ratio`() {
        val policy = AgentRetryPolicy(baseDelayMs = 10_000, maxDelayMs = 60_000, jitterRatio = 0.2)
        val low = policy.delayMs(1, random = 0.0)
        val high = policy.delayMs(1, random = 1.0)
        assertEquals(8_000, low)
        assertEquals(12_000, high)
    }

    @Test
    fun `gateway floor keeps a minimum wait even with jitter`() {
        val policy = AgentRetryPolicy(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterRatio = 0.5)
        assertEquals(5_000, policy.delayMs(1, random = 0.0, minimumDelayMs = 5_000))
        assertEquals(5_000, policy.delayMs(1, random = 0.5, minimumDelayMs = 5_000))
    }

    @Test
    fun `zero retries disables the budget`() {
        assertTrue(!AgentRetryPolicy(maxRetries = 0).canRetry(0))
        assertTrue(AgentRetryPolicy(maxRetries = 3).canRetry(2))
        assertTrue(!AgentRetryPolicy(maxRetries = 3).canRetry(3))
    }

    @Test
    fun `invalid settings fall back to defaults`() {
        assertEquals(AgentRetryPolicy.DEFAULT_MAX_RETRIES, AgentRetryPolicy.fromSettings(99).maxRetries)
        assertEquals(AgentRetryPolicy.DEFAULT_MAX_RETRIES, AgentRetryPolicy.fromSettings(-1).maxRetries)
        assertEquals(0, AgentRetryPolicy.fromSettings(0).maxRetries)
    }

    @Test
    fun `retry after parses seconds and http dates`() {
        assertEquals(30L, AgentRetryPolicy.parseRetryAfter("30"))
        assertEquals(0L, AgentRetryPolicy.parseRetryAfter("0"))
        assertNull(AgentRetryPolicy.parseRetryAfter("not-a-number"))
        assertNull(AgentRetryPolicy.parseRetryAfter(""))
        val now = 1_700_000_000_000L
        // 该 HTTP-date 对应 now + 45 秒。
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 45_000).atZone(java.time.ZoneOffset.UTC),
        )
        assertEquals(45L, AgentRetryPolicy.parseRetryAfter(date, now))
    }
}
