package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `scroll_element` 兜底时「滑哪块区域」的选择（fix158）。
 *
 * 这些用例守的是一件事：**模型指了哪个节点，就滑哪个节点的区域**。
 * 以前兜底会重新挑「面积最大的可滚动节点」，界面上有两个列表时就会指东滑西，
 * 而且返回 `moved=true` —— 模型以为滑对了，用户看到的是另一个列表在动。
 */
class ScrollRegionPolicyTest {
    @Test
    fun `the node the caller pointed at wins over a bigger one`() {
        val inner = ScrollRegionPolicy.Box(left = 0, top = 600, right = 720, bottom = 1200)
        val outer = ScrollRegionPolicy.Box(left = 0, top = 0, right = 720, bottom = 1920)
        val pick = ScrollRegionPolicy.pick(preferred = inner, candidates = listOf(inner, outer))
        assertEquals(ScrollRegionPolicy.SOURCE_PREFERRED, pick?.source)
        assertEquals(inner, pick?.bounds)
    }

    @Test
    fun `no preferred region falls back to the largest scrollable one`() {
        val small = ScrollRegionPolicy.Box(0, 0, 100, 100)
        val large = ScrollRegionPolicy.Box(0, 0, 720, 1600)
        val pick = ScrollRegionPolicy.pick(preferred = null, candidates = listOf(small, large))
        assertEquals(ScrollRegionPolicy.SOURCE_LARGEST, pick?.source)
        assertEquals(large, pick?.bounds)
    }

    @Test
    fun `a zero-sized preferred region is ignored instead of producing an invalid gesture`() {
        // 0 宽 / 0 高的区域会让 gestureWithin 直接报 INVALID_NODE_BOUNDS；
        // 这里提前筛掉，别把一次本来能成的滑动变成错误。
        val degenerate = ScrollRegionPolicy.Box(0, 0, 0, 0)
        val real = ScrollRegionPolicy.Box(0, 0, 720, 800)
        val pick = ScrollRegionPolicy.pick(preferred = degenerate, candidates = listOf(real))
        assertEquals(ScrollRegionPolicy.SOURCE_LARGEST, pick?.source)
        assertEquals(real, pick?.bounds)
    }

    @Test
    fun `nothing usable means no target at all`() {
        assertNull(ScrollRegionPolicy.pick(preferred = null, candidates = emptyList()))
        assertNull(
            ScrollRegionPolicy.pick(
                preferred = null,
                candidates = listOf(ScrollRegionPolicy.Box(10, 10, 10, 40)),
            ),
        )
    }

    @Test
    fun `area is used to break ties, not absolute position`() {
        // 面积相同取先出现者（maxByOrNull 语义）；这里锁住「不会因为位置更靠上就被选中」。
        val top = ScrollRegionPolicy.Box(0, 0, 400, 400)
        val bottom = ScrollRegionPolicy.Box(0, 800, 400, 1200)
        val pick = ScrollRegionPolicy.pick(preferred = null, candidates = listOf(top, bottom))
        assertEquals(top, pick?.bounds)
    }
}
