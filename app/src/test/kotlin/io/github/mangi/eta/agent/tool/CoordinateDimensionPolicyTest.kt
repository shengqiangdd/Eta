package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「用哪块屏的尺寸换算坐标」（fix161）。
 *
 * 守的是：主屏观察过之后去虚拟屏按屏幕坐标点击，不能拿主屏尺寸（1216x2640）
 * 去校验虚拟屏坐标（720x1280）——真机就是这么把越界坐标放行的。
 */
class CoordinateDimensionPolicyTest {
    private val main = 1216 to 2640
    private val virtual = 720 to 1280

    @Test
    fun `the target display wins over whatever was observed last`() {
        assertEquals(
            virtual,
            CoordinateDimensionPolicy.pick(
                requested = virtual,
                published = main,
                publishedDisplayId = null,
                requestedDisplayId = 112,
            ),
        )
    }

    @Test
    fun `a stale observation from another display is not used`() {
        // 虚拟屏尺寸取不到（例如会话还没登记），而最近一次观察在主屏：
        // 宁可交给调用方回退，也不要拿主屏尺寸冒充虚拟屏。
        assertNull(
            CoordinateDimensionPolicy.pick(
                requested = null,
                published = main,
                publishedDisplayId = null,
                requestedDisplayId = 112,
            ),
        )
    }

    @Test
    fun `an observation on the same display is still usable`() {
        assertEquals(
            virtual,
            CoordinateDimensionPolicy.pick(
                requested = null,
                published = virtual,
                publishedDisplayId = 112,
                requestedDisplayId = 112,
            ),
        )
    }

    @Test
    fun `main screen treats null and zero as the same display`() {
        assertEquals(
            main,
            CoordinateDimensionPolicy.pick(
                requested = null,
                published = main,
                publishedDisplayId = 0,
                requestedDisplayId = null,
            ),
        )
    }

    @Test
    fun `zero sized entries are ignored`() {
        assertNull(
            CoordinateDimensionPolicy.pick(
                requested = 0 to 0,
                published = null,
                publishedDisplayId = null,
                requestedDisplayId = null,
            ),
        )
        assertEquals(
            virtual,
            CoordinateDimensionPolicy.pick(
                requested = 0 to 0,
                published = virtual,
                publishedDisplayId = 112,
                requestedDisplayId = 112,
            ),
        )
    }

    // ---- 截图坐标系（fix162）----

    @Test
    fun `screenshot coordinates are usable on the display they were taken from`() {
        assertEquals(
            CoordinateDimensionPolicy.ScreenshotSpaceUse.USABLE,
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = true,
                publishedDisplayId = 116,
                requestedDisplayId = 116,
            ),
        )
    }

    @Test
    fun `a screenshot from the main screen cannot place a point on a virtual display`() {
        // 真机就是这条路：主屏 1216x2640 观察后，用 screenshot 坐标去点 720x1280 的虚拟屏，
        // 越界坐标被放行，到控制器才报成 TOOL_ERROR（看着像内部错误）。
        assertEquals(
            CoordinateDimensionPolicy.ScreenshotSpaceUse.OTHER_DISPLAY,
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = true,
                publishedDisplayId = null,
                requestedDisplayId = 116,
            ),
        )
    }

    @Test
    fun `a virtual screenshot cannot place a point on the main screen`() {
        // 反方向不报错，而是静默点偏：数值合法、落点在屏内，但不是模型想点的地方。
        assertEquals(
            CoordinateDimensionPolicy.ScreenshotSpaceUse.OTHER_DISPLAY,
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = true,
                publishedDisplayId = 116,
                requestedDisplayId = null,
            ),
        )
    }

    @Test
    fun `main screen null and zero display ids still count as the same screenshot source`() {
        assertEquals(
            CoordinateDimensionPolicy.ScreenshotSpaceUse.USABLE,
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = true,
                publishedDisplayId = 0,
                requestedDisplayId = null,
            ),
        )
    }

    @Test
    fun `no screenshot means no screenshot space, even on the same display`() {
        // 未附图、或附图被 region 裁剪放大（坐标系已置空）都走这条。
        assertEquals(
            CoordinateDimensionPolicy.ScreenshotSpaceUse.NO_SCREENSHOT,
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = false,
                publishedDisplayId = 116,
                requestedDisplayId = 116,
            ),
        )
    }
}
