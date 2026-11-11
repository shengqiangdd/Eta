package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentSessionUsageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 生成速度与耗时的文案。
 *
 * 这里最容易犯的错是用**整轮总耗时**当分母：一次长工具调用会把速度拉到个位数，
 * 看起来像模型很慢，实际只是工具在跑。所以速度必须只用解码耗时算。
 */
class SessionSpeedFormattingTest {

    private fun usage(
        firstTokenMs: Long? = null,
        decodeMs: Long? = null,
        outputTokens: Int? = null,
    ) = AgentSessionUsageUi(
        lastFirstTokenMs = firstTokenMs,
        lastDecodeMs = decodeMs,
        lastOutputTokens = outputTokens,
    )

    @Test
    fun `速度只用解码耗时计算`() {
        // 100 token / 2s = 50 tok/s。
        assertEquals("50.0", decodeSpeedValue(usage(firstTokenMs = 800, decodeMs = 2_000, outputTokens = 100)))
    }

    @Test
    fun `首字很慢不影响生成速度`() {
        // 首字 5 秒、解码 1 秒：速度仍是 100 tok/s。
        // 若误用「总耗时 6 秒」当分母会得到 16.7，把首字延迟混进了生成速度。
        assertEquals("100.0", decodeSpeedValue(usage(firstTokenMs = 5_000, decodeMs = 1_000, outputTokens = 100)))
    }

    @Test
    fun `缺解码耗时或输出 token 时不给速度`() {
        // 这两个是速度的必要数据：分子与分母缺一不可。
        assertNull(decodeSpeedValue(usage(firstTokenMs = 800, outputTokens = 100)))
        assertNull(decodeSpeedValue(usage(firstTokenMs = 800, decodeMs = 2_000)))
        assertNull(decodeSpeedValue(usage(firstTokenMs = 800, decodeMs = 0, outputTokens = 100)))
        assertNull(decodeSpeedValue(usage(firstTokenMs = 800, decodeMs = 2_000, outputTokens = 0)))
    }

    @Test
    fun `首字未知但解码耗时已知时仍可给出速度`() {
        // 速度的定义只涉及「解码耗时 / 输出 token」，与首字无关。
        // 把它也设成必要条件是错的 —— 那会让没采到首字的旧轮次连速度都看不到。
        assertEquals("50.0", decodeSpeedValue(usage(decodeMs = 2_000, outputTokens = 100)))
    }

    @Test
    fun `极低速度显示小于零点一`() {
        // 1 token / 60s ≈ 0.017，显示 0.0 会让人以为没输出。
        assertEquals("<0.1", decodeSpeedValue(usage(decodeMs = 60_000, outputTokens = 1)))
    }

    @Test
    fun `耗时不到一秒用毫秒`() {
        assertEquals("0ms", formatDurationMs(0))
        assertEquals("850ms", formatDurationMs(850))
    }

    @Test
    fun `十秒以内保留一位小数，更长取整`() {
        assertEquals("1.3s", formatDurationMs(1_250))
        assertEquals("9.9s", formatDurationMs(9_900))
        // 分钟级再带小数没有意义，取整更好读。
        assertEquals("12s", formatDurationMs(12_400))
        assertEquals("90s", formatDurationMs(90_000))
    }
}
