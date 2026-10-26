package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayFallbackPolicyTest {
    @Test
    fun `virtual display gesture rejection falls back when root is available`() {
        assertTrue(
            VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                errorCode = VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED,
                displayId = 13,
                rootAvailable = true,
            ),
        )
    }

    @Test
    fun `without root there is no input -d path, so no fallback`() {
        assertFalse(
            VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                errorCode = VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED,
                displayId = 13,
                rootAvailable = false,
            ),
        )
    }

    @Test
    fun `default display never uses the virtual-display fallback`() {
        // displayId=0 是默认屏；它有自己的无障碍手势路径，走到这里说明是别的错误。
        assertFalse(
            VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                errorCode = VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED,
                displayId = 0,
                rootAvailable = true,
            ),
        )
        assertFalse(
            VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                errorCode = VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED,
                displayId = null,
                rootAvailable = true,
            ),
        )
    }

    @Test
    fun `other failures are not silently retried as coordinate taps`() {
        // 节点过期 / 无障碍断开 / 结果未知：重放会点错东西或动到用户主屏，必须让模型自己决定。
        listOf("STALE_ACTION_TARGET", "ACCESSIBILITY_UNAVAILABLE", "ACTION_OUTCOME_UNKNOWN", "ACTION_FAILED")
            .forEach { code ->
                assertFalse(
                    "不该兜底的错误码：$code",
                    VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                        errorCode = code,
                        displayId = 13,
                        rootAvailable = true,
                    ),
                )
            }
    }
}
