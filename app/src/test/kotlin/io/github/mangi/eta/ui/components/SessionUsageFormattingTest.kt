package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 用量卡片里的两个数字格式化函数。
 *
 * 卡片用**精确值 + 千分位**，与胶囊上的缩写（`1.2k`）刻意不同：
 * 卡片是用户主动点开看明细的地方，这时候 `1.2k` 反而丢失信息。
 */
class SessionUsageFormattingTest {

    @Test
    fun `token 数带千分位`() {
        // Locale 影响分隔符；用 US 断言，避免在不同 locale 的机器上抖动。
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0", formatExactTokenCount(0))
            assertEquals("999", formatExactTokenCount(999))
            assertEquals("1,234", formatExactTokenCount(1_234))
            assertEquals("1,234,567", formatExactTokenCount(1_234_567))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `命中率四舍五入到整数`() {
        assertEquals("90%", formatCacheHitPercent(0.9f))
        assertEquals("0%", formatCacheHitPercent(0f))
        assertEquals("100%", formatCacheHitPercent(1f))
        assertEquals("67%", formatCacheHitPercent(0.6666f))
    }

    @Test
    fun `命中率未知时显示破折号而不是零`() {
        // 与胶囊一致：0% 是「完全没命中」，与「没数据」是两件事。
        assertEquals("—", formatCacheHitPercent(null))
    }

    @Test
    fun `异常命中率被夹到合法区间`() {
        // 后端口径差异可能给出越界值，界面不能显示 -5% 或 130%。
        assertEquals("100%", formatCacheHitPercent(1.3f))
        assertEquals("0%", formatCacheHitPercent(-0.2f))
    }
}
