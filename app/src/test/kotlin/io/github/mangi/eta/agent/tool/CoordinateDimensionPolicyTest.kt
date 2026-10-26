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
}
