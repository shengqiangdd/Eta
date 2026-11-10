package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取帧几何：越界保护的核心算术。
 *
 * 这些用例对应真机上出现过的具体形态 —— 正常帧、resize 后只填了一半、首帧几乎为空、
 * 以及「缓冲区比整幅多出几个字节」这种既不该越界也不该被判成不完整的情况。
 */
class ImageBufferGeometryTest {

    // 常见虚拟屏：1080 宽、pixelStride 4（ARGB），rowStride 因对齐到 1088 多出 8 字节。
    private val width = 1080
    private val height = 2400
    private val pixelStride = 4
    private val rowStride = 1088 * 4 // 4352

    @Test
    fun `paddedWidth 按 rowStride 换算而不是有效像素宽度`() {
        // 无 padding 时应与 width 相同。
        assertEquals(1080, ImageBufferGeometry.paddedWidth(1080 * 4, 4, 1080))
        // 有 padding 时要变宽，否则每一行都会错位。
        assertEquals(1088, ImageBufferGeometry.paddedWidth(rowStride, 4, width))
    }

    @Test
    fun `非法步长返回 null 而不是猜一个值`() {
        assertNull(ImageBufferGeometry.paddedWidth(0, 4, width))
        assertNull(ImageBufferGeometry.paddedWidth(rowStride, 0, width))
        assertNull(ImageBufferGeometry.paddedWidth(rowStride, 4, 0))
        // rowStride 连有效像素都放不下 —— 数据本身是坏的。
        assertNull(ImageBufferGeometry.paddedWidth(100, 4, width))
        assertNull(ImageBufferGeometry.rowBytes(rowStride, -1, width))
    }

    @Test
    fun `完整帧返回整幅高度`() {
        val remaining = rowStride * height
        assertEquals(height, ImageBufferGeometry.safeHeight(remaining, rowStride, height))
        assertTrue(ImageBufferGeometry.isFullFrame(remaining, rowStride, height))
    }

    @Test
    fun `缓冲区比整幅多出几个字节时仍按整幅处理`() {
        // 这是最容易写错的一条：直接 remaining / rowStride 会得到 height + 1，
        // 拿去建图就比真实数据多一行，重新引入越界。
        val remaining = rowStride * height + 7
        assertEquals(height, ImageBufferGeometry.safeHeight(remaining, rowStride, height))
        assertTrue(ImageBufferGeometry.isFullFrame(remaining, rowStride, height))
    }

    @Test
    fun `resize 后只填了一半时按实际行数截断`() {
        val half = height / 2
        assertEquals(half, ImageBufferGeometry.safeHeight(rowStride * half, rowStride, height))
        assertFalse(ImageBufferGeometry.isFullFrame(rowStride * half, rowStride, height))
    }

    @Test
    fun `不足一行的缓冲区视为没拿到帧`() {
        assertNull(ImageBufferGeometry.safeHeight(rowStride - 1, rowStride, height))
        assertNull(ImageBufferGeometry.safeHeight(0, rowStride, height))
        assertNull(ImageBufferGeometry.safeHeight(-8, rowStride, height))
    }

    @Test
    fun `非法高度或步长不会返回正数`() {
        assertNull(ImageBufferGeometry.safeHeight(rowStride * 10, 0, height))
        assertNull(ImageBufferGeometry.safeHeight(rowStride * 10, rowStride, 0))
        assertNull(ImageBufferGeometry.safeHeight(rowStride * 10, -4, height))
    }

    @Test
    fun `恰好一行的缓冲区返回一行`() {
        assertEquals(1, ImageBufferGeometry.safeHeight(rowStride, rowStride, height))
    }

    @Test
    fun `极大尺寸不会因整型溢出误判为完整`() {
        // rowStride * height 用 Int 相乘会溢出成负数，从而把不完整帧当成完整帧。
        val bigRow = 65536
        val bigHeight = 32768 // 65536 * 32768 已超过 Int.MAX_VALUE
        val remaining = Int.MAX_VALUE
        assertEquals(remaining / bigRow, ImageBufferGeometry.safeHeight(remaining, bigRow, bigHeight))
        assertFalse(ImageBufferGeometry.isFullFrame(remaining, bigRow, bigHeight))
    }
}
