package io.github.mangi.eta.agent.virtualdisplay

/**
 * ImageReader 取帧时的缓冲区几何计算。
 *
 * 抽成纯函数是因为两条取帧路径（`agent/device` 与 `agent/virtualdisplay` 各一份）
 * 都在这里踩过同一个坑：直接按 `paddedWidth * height` 建位图再 `copyPixelsFromBuffer`，
 * 而 `buffer.remaining()` 在 resize/重建、或首帧还没填满时会短于这个尺寸，导致读越界
 * （轻则整帧花屏，重则崩）。
 *
 * 这类错误的具体形态是算错**行数**，而正确的行数只能在真机上靠 ImageReader 的时序
 * 复现出来 —— 所以把算术独立出来，用单测钉住边界，剩下的只是调用它。
 */
internal object ImageBufferGeometry {

    /**
     * 一行像素占用的字节数（含行尾 padding）。
     *
     * `rowStride` 是生产者给的步长，`pixelStride * width` 才是有效像素的字节数，
     * 两者之差就是行尾 padding。pixelStride <= 0 是非法的（无像素信息），返回 null。
     */
    fun rowBytes(rowStride: Int, pixelStride: Int, width: Int): Int? {
        if (pixelStride <= 0 || width <= 0 || rowStride < pixelStride * width) return null
        return rowStride
    }

    /**
     * 按 `rowStride` 而不是「有效像素宽度」算出的整幅宽度（像素）。
     *
     * 建位图时必须用它：`copyPixelsFromBuffer` 是按 buffer 的连续布局填充的，
     * 用有效宽度建图会让每一行都错位。
     */
    fun paddedWidth(rowStride: Int, pixelStride: Int, width: Int): Int? {
        if (pixelStride <= 0 || width <= 0 || rowStride < pixelStride * width) return null
        return width + (rowStride - pixelStride * width) / pixelStride
    }

    /**
     * 当前缓冲区实际能填满多少行。
     *
     * @return 1..height 之间的行数；不足以构成一行时返回 null（调用方应把这一帧当作没拿到）。
     *
     * 关键点：只在**不够整幅**时才去算行数，够的话直接返回 height。
     * 反过来写（永远做整除）会在 remaining 恰好多出几个字节时得到比 height 还大的值，
     * 再拿去建图就又越界了 —— 所以必须有 `coerceAtMost(height)`。
     */
    fun safeHeight(remaining: Int, rowStride: Int, height: Int): Int? {
        if (rowStride <= 0 || height <= 0 || remaining <= 0) return null
        if (remaining >= rowStride.toLong() * height) return height
        val rows = remaining / rowStride
        return if (rows <= 0) null else rows.coerceAtMost(height)
    }

    /**
     * 字节长度到达上限后，缓冲区可能仍带 padding。这里给出「整幅是否可直接复用」的判定，
     * 避免调用方各自写一套比较条件。
     */
    fun isFullFrame(remaining: Int, rowStride: Int, height: Int): Boolean =
        rowStride > 0 && height > 0 && remaining >= rowStride.toLong() * height
}
