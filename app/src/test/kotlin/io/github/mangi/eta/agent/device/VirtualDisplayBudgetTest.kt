package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏准入与空闲回收。
 *
 * 背景：`create` 原本没有上限，每块屏挂一个 ImageReader，
 * 1080×1920 的缓冲是 `1080 × 1920 × 4 × 2 = 16,588,800` 字节（约 15.8 MB）。
 * 建到第 10 块就是 158 MB 图形内存——手机上足够把进程拖垮，而工具描述里只有一句
 * 「任务结束必须释放」，靠的是模型自觉。
 */
class VirtualDisplayBudgetTest {

    private fun existing(id: String, width: Int, height: Int, lastUsedAtMs: Long) =
        VirtualDisplayBudget.Existing(id, width, height, lastUsedAtMs)

    @Test
    fun bufferEstimateMatchesImageReaderDoubleBuffer() {
        assertEquals(1080L * 1920 * 4 * 2, VirtualDisplayBudget.bufferBytes(1080, 1920))
        assertEquals(720L * 1280 * 4 * 2, VirtualDisplayBudget.bufferBytes(720, 1280))
        // 非法尺寸按 1 兜底，不返回 0（0 会让预算判断永远放行）。
        assertEquals(1L * 1 * 4 * 2, VirtualDisplayBudget.bufferBytes(0, 0))
    }

    @Test
    fun sessionCountLimitRejectsFifthDisplay() {
        val four = (1..4).map { existing("vd$it", 720, 1280, 0) }
        val admission = VirtualDisplayBudget.admit(four, 720, 1280)
        assertTrue(admission is VirtualDisplayBudget.Admission.Rejected)
        admission as VirtualDisplayBudget.Admission.Rejected
        assertEquals("VIRTUAL_DISPLAY_LIMIT", admission.code)
        assertTrue("拒绝消息必须告诉模型下一步怎么办", admission.message.contains("virtual_display_release"))
    }

    @Test
    fun fourSmallDisplaysAreAllowed() {
        val three = (1..3).map { existing("vd$it", 720, 1280, 0) }
        assertEquals(
            VirtualDisplayBudget.Admission.Allowed,
            VirtualDisplayBudget.admit(three, 720, 1280),
        )
    }

    @Test
    fun memoryLimitRejectsLargeDisplayEvenBelowSessionCap() {
        // 一块 4K 就要 66 MB；再来一块必然超过 96 MB 上限，此时会话数只有 2。
        val used = listOf(existing("vd1", 3840, 2160, 0))
        val admission = VirtualDisplayBudget.admit(used, 3840, 2160)
        assertTrue(admission is VirtualDisplayBudget.Admission.Rejected)
        admission as VirtualDisplayBudget.Admission.Rejected
        assertEquals("VIRTUAL_DISPLAY_MEMORY", admission.code)
        assertTrue("要提示可以改小尺寸", admission.message.contains("720x1280"))
    }

    @Test
    fun sweepOnlyTakesDisplaysIdleLongerThanTimeout() {
        val timeout = VirtualDisplayBudget.IDLE_TIMEOUT_MS
        val now = 10_000_000L
        val list = listOf(
            existing("active", 720, 1280, now - 1_000),
            existing("exactly", 720, 1280, now - timeout),
            existing("stale", 720, 1280, now - timeout - 1),
        )
        assertEquals(listOf("stale"), VirtualDisplayBudget.sweep(list, now, timeout))
    }

    @Test
    fun sweepIsEmptyWhenEverythingWasUsedRecently() {
        val now = 5_000L
        val list = listOf(existing("a", 720, 1280, now), existing("b", 720, 1280, now - 60_000))
        assertTrue(VirtualDisplayBudget.sweep(list, now).isEmpty())
    }

    @Test
    fun usedBytesSumsEveryDisplay() {
        val list = listOf(existing("a", 1080, 1920, 0), existing("b", 1080, 1920, 0))
        assertEquals(2 * 1080L * 1920 * 4 * 2, VirtualDisplayBudget.usedBytes(list))
        assertEquals(0L, VirtualDisplayBudget.usedBytes(emptyList()))
    }
}
