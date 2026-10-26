package io.github.mangi.eta.agent.device

/**
 * 「这次滑动要落在哪块区域」的选择。
 *
 * 背景（fix158）：`scroll_element(index)` 在虚拟屏上会走 Root 兜底（节点只支持手势时），
 * 兜底原先直接调用整屏 `scroll()`，而 `scroll()` 自己挑「面积最大的可滚动节点」——
 * 于是**指东滑西**：界面上有两个列表（例如外层 ScrollView + 内层 RecyclerView，
 * 或左右两栏）时，被滑的很可能是模型根本没指的那个。模型看到 `moved=true` 还会以为
 * 自己滑对了地方。
 *
 * 现在把「用哪块区域」抽成纯函数：调用方指定的区域优先，只有在没指定时才回落到面积最大者，
 * 并把来源（`preferred_node` / `largest_scrollable`）带回结果里，事后能证明滑的是哪儿。
 *
 * 刻意不引用 android.graphics.Rect：本对象被纯 JVM 单测直接覆盖。
 */
internal object ScrollRegionPolicy {
    /** 区域来自调用方指定的节点。 */
    const val SOURCE_PREFERRED: String = "preferred_node"

    /** 区域来自「面积最大的可滚动节点」。 */
    const val SOURCE_LARGEST: String = "largest_scrollable"

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height.toLong()

        /** 面积为 0 的区域不能用来算手势（`gestureWithin` 会直接拒绝）。 */
        val usable: Boolean get() = width > 0 && height > 0
    }

    data class Pick(val bounds: Box, val source: String)

    /**
     * 选滑动区域。
     *
     * @param preferred 调用方指定的区域（通常来自 `scroll_element` 的节点 bounds）；null 表示没指定
     * @param candidates 当前界面里所有可滚动节点的区域
     * @return 选中的区域与来源；一个可用区域都没有时返回 null（调用方应报「没有可滚动目标」）
     */
    fun pick(preferred: Box?, candidates: List<Box>): Pick? {
        preferred?.takeIf { it.usable }?.let { return Pick(it, SOURCE_PREFERRED) }
        val largest = candidates.filter { it.usable }.maxByOrNull { it.area } ?: return null
        return Pick(largest, SOURCE_LARGEST)
    }
}
