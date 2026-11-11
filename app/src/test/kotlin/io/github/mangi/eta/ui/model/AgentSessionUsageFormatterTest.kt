package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用量文案的口径：缩写、百分比、tok/s、首字延迟。 */
class AgentSessionUsageFormatterTest {

    @Test
    fun `千分位缩写保留一位小数并去掉无意义的点零`() {
        assertEquals("999", AgentSessionUsageFormatter.abbreviateCount(999))
        assertEquals("1k", AgentSessionUsageFormatter.abbreviateCount(1_000))
        assertEquals("1.2k", AgentSessionUsageFormatter.abbreviateCount(1_234))
        assertEquals("12.3M", AgentSessionUsageFormatter.abbreviateCount(12_345_678))
        assertEquals("1G", AgentSessionUsageFormatter.abbreviateCount(1_000_000_000))
    }

    @Test
    fun `负数不会被缩写成奇怪的单位`() {
        // 理论上不该出现，但一旦出现要可读，而不是 "-1k"。
        assertEquals("-1.2k", AgentSessionUsageFormatter.abbreviateCount(-1_234))
    }

    @Test
    fun `命中率四舍五入到整数百分比`() {
        assertEquals("90%", AgentSessionUsageFormatter.percentOrNull(0.9f))
        assertEquals("0%", AgentSessionUsageFormatter.percentOrNull(0f))
        assertEquals("100%", AgentSessionUsageFormatter.percentOrNull(1f))
        assertEquals("67%", AgentSessionUsageFormatter.percentOrNull(0.6666f))
    }

    @Test
    fun `命中率未知时返回 null 而不是 0 百分比`() {
        // 0% 表示「完全没命中」，与「不知道」必须区分开。
        assertNull(AgentSessionUsageFormatter.percentOrNull(null))
    }

    @Test
    fun `tok 每秒保留一位小数`() {
        assertEquals("50.0", AgentSessionUsageFormatter.tokensPerSecondOrNull(100, 2_000))
        assertEquals("1234.5", AgentSessionUsageFormatter.tokensPerSecondOrNull(2_469, 2_000))
    }

    @Test
    fun `耗时或 token 缺失时不给数字`() {
        assertNull(AgentSessionUsageFormatter.tokensPerSecondOrNull(null, 1_000))
        assertNull(AgentSessionUsageFormatter.tokensPerSecondOrNull(100, null))
        assertNull(AgentSessionUsageFormatter.tokensPerSecondOrNull(100, 0))
        assertNull(AgentSessionUsageFormatter.tokensPerSecondOrNull(0, 1_000))
    }

    @Test
    fun `极低速度显示小于零点一而不是零`() {
        // 1 token / 60s ≈ 0.0167，显示 0.0 会让人以为没输出。
        assertEquals("<0.1", AgentSessionUsageFormatter.tokensPerSecondOrNull(1, 60_000))
    }

    @Test
    fun `首字延迟在秒以下用毫秒`() {
        assertEquals("850ms", AgentSessionUsageFormatter.firstTokenOrNull(850))
        assertEquals("0ms", AgentSessionUsageFormatter.firstTokenOrNull(0))
        assertEquals("1.3s", AgentSessionUsageFormatter.firstTokenOrNull(1_250))
    }

    @Test
    fun `首字延迟缺失或为负时不给数字`() {
        assertNull(AgentSessionUsageFormatter.firstTokenOrNull(null))
        assertNull(AgentSessionUsageFormatter.firstTokenOrNull(-1))
    }

}
