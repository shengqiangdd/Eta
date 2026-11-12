package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存水位记录的纯逻辑部分。
 *
 * 为什么值得测：这段代码平时不跑（只在系统内存压力回调里执行），一旦写错很难
 * 在开发时发现——等真出事时它恰好是唯一能解释 OOM 的证据，错了就等于没有。
 */
class MemoryWatermarkTest {

    // ---- 等级映射 ----

    @Test
    fun `低于 RUNNING_LOW 的等级不采样`() {
        // 这些在日常使用里频繁出现（切后台、滑动），记下来只会刷屏。
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(5))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(20))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(40))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(60))
    }

    @Test
    fun `RUNNING_LOW 起始采样`() {
        assertEquals(
            MemoryWatermark.Trigger.RUNNING_LOW,
            MemoryWatermark.Trigger.fromTrimLevel(10),
        )
        // 15 是 RUNNING_CRITICAL：也是前台压力，只是比 RUNNING_LOW 更重，
        // 归到同一触发器，否则「临界」那一刻反而没有记录。
        assertEquals(
            MemoryWatermark.Trigger.RUNNING_LOW,
            MemoryWatermark.Trigger.fromTrimLevel(15),
        )
    }

    @Test
    fun `后台等级不采样`() {
        // 60=MODERATE、40=BACKGROUND、20=UI_HIDDEN 都比 10 大。
        // 若写成 level >= 10 会把它们全归成 RUNNING_LOW——进程已经不可见了，
        // 那时采到的水位跟用户体验到的内存压力无关。
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(60))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(40))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(20))
    }

    @Test
    fun `COMPLETE 归为 COMPLETE`() {
        assertEquals(MemoryWatermark.Trigger.COMPLETE, MemoryWatermark.Trigger.fromTrimLevel(80))
    }

    @Test
    fun `非法等级不猜`() {
        // 精确匹配而不是范围比较：系统只会发 AOSP 定义的几个等级，
        // 收到别的值说明我们的假设错了，此时安静不采比对到错档更安全。
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(100))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(0))
        assertNull(MemoryWatermark.Trigger.fromTrimLevel(-1))
    }

    // ---- 格式化 ----

    @Test
    fun `按已用量和上限算百分比`() {
        val line = MemoryWatermark.format(
            trigger = MemoryWatermark.Trigger.RUNNING_LOW,
            usedBytes = 128L * 1024 * 1024,
            maxBytes = 256L * 1024 * 1024,
        )
        assertEquals("内存水位[running-low] 已用 128.0MB / 上限 256MB (50%)", line)
    }

    @Test
    fun `上限为零时不除零`() {
        val line = MemoryWatermark.format(
            trigger = MemoryWatermark.Trigger.LOW_MEMORY,
            usedBytes = 1024L,
            maxBytes = 0L,
        )
        assertTrue("应该仍然可读：$line", line.contains("0%"))
    }

    @Test
    fun `接近满堆时百分比能超过九十`() {
        // 真实 OOM 现场就是这种形状：上限满、可用不足 1%。
        val line = MemoryWatermark.format(
            trigger = MemoryWatermark.Trigger.COMPLETE,
            usedBytes = 255L * 1024 * 1024,
            maxBytes = 256L * 1024 * 1024,
        )
        assertTrue("应该反映真实水位：$line", line.contains("99%") || line.contains("100%"))
    }

    // ---- 节流 ----

    @Test
    fun `首次采样总是记录`() {
        assertTrue(
            MemoryWatermark.shouldRecord(
                MemoryWatermark.Trigger.RUNNING_LOW,
                lastAtMs = null,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun `窗口内不重复记录`() {
        assertFalse(
            MemoryWatermark.shouldRecord(
                MemoryWatermark.Trigger.RUNNING_LOW,
                lastAtMs = 1_000L,
                nowMs = 1_000L + 30_000L,
            ),
        )
    }

    @Test
    fun `超过窗口后继续记录`() {
        assertTrue(
            MemoryWatermark.shouldRecord(
                MemoryWatermark.Trigger.RUNNING_LOW,
                lastAtMs = 1_000L,
                nowMs = 1_000L + 60_000L,
            ),
        )
    }

    @Test
    fun `时钟回拨不会永久静音`() {
        // 系统时间被校正回退时，now < last。若按「差值 < 窗口就跳过」处理，
        // 差值恒为负 → 此后永远不再记录，正好丢掉最需要的那一段。
        assertTrue(
            MemoryWatermark.shouldRecord(
                MemoryWatermark.Trigger.COMPLETE,
                lastAtMs = 10_000L,
                nowMs = 5_000L,
            ),
        )
    }
}
