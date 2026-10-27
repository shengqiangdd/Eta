package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * fix166：显式指定的屏必须决定按键的注入通道，而不是交给「焦点漂移」。
 */
class KeyPressRoutePolicyTest {

    @Test
    fun `虚拟屏按键 - 必须按 display 注入`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ROOT_ON_DISPLAY,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = 148,
                routedDisplayId = 148,
                accessibilityAvailable = true,
                rootAvailable = true,
            ),
        )
    }

    /** 真机复现的那条：显式写 0（主屏），有 Root 就不该再走全局动作。 */
    @Test
    fun `显式主屏且有 Root - 走 input -d 0`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ROOT_DEFAULT_DISPLAY,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = 0,
                routedDisplayId = null,
                accessibilityAvailable = true,
                rootAvailable = true,
            ),
        )
    }

    @Test
    fun `显式主屏但无 Root - 退化为无障碍全局`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ACCESSIBILITY_GLOBAL,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = 0,
                routedDisplayId = null,
                accessibilityAvailable = true,
                rootAvailable = false,
            ),
        )
    }

    @Test
    fun `未写 display_id 且无障碍可用 - 沿用旧的全局动作`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ACCESSIBILITY_GLOBAL,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = null,
                routedDisplayId = null,
                accessibilityAvailable = true,
                rootAvailable = true,
            ),
        )
    }

    @Test
    fun `未写 display_id 且无无障碍 - 走默认屏注入`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ROOT_DEFAULT_DISPLAY,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = null,
                routedDisplayId = null,
                accessibilityAvailable = false,
                rootAvailable = true,
            ),
        )
    }

    @Test
    fun `什么都没有 - 交给调用方报无障碍不可用`() {
        assertEquals(
            KeyPressRoutePolicy.Route.ACCESSIBILITY_GLOBAL,
            KeyPressRoutePolicy.decide(
                explicitDisplayId = null,
                routedDisplayId = null,
                accessibilityAvailable = false,
                rootAvailable = false,
            ),
        )
    }
}
