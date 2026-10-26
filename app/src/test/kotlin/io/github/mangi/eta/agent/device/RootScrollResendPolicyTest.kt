package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootScrollResendPolicyTest {
    @Test
    fun `anchors present but nothing moved means the gesture never took effect`() {
        // 页面切换动画里打进去的那一下：锚点都在，位置一个没变 → 补发一次安全（不会滚两屏）。
        assertTrue(
            RootScrollResendPolicy.shouldResend(
                moved = false,
                anchors = 9,
                movingAnchors = 0,
                attempts = 1,
            ),
        )
    }

    @Test
    fun `no matched anchor means we cannot tell, so never resend`() {
        // 一个锚点都没配上：页面换了，我们并不知道自己滚没滚。此时补发可能变成真正的两次滚动。
        assertFalse(
            RootScrollResendPolicy.shouldResend(
                moved = false,
                anchors = 0,
                movingAnchors = 0,
                attempts = 1,
            ),
        )
    }

    @Test
    fun `single anchor is not enough evidence`() {
        assertFalse(
            RootScrollResendPolicy.shouldResend(
                moved = false,
                anchors = 1,
                movingAnchors = 0,
                attempts = 1,
            ),
        )
    }

    @Test
    fun `partial movement means the swipe did work`() {
        // 至少有一个锚点真的动了 → 手势生效了，不能再补发。
        assertFalse(
            RootScrollResendPolicy.shouldResend(
                moved = false,
                anchors = 5,
                movingAnchors = 1,
                attempts = 1,
            ),
        )
    }

    @Test
    fun `already moved or out of attempts stops resending`() {
        assertFalse(
            RootScrollResendPolicy.shouldResend(
                moved = true,
                anchors = 5,
                movingAnchors = 0,
                attempts = 1,
            ),
        )
        assertFalse(
            "补发过一次就停，避免在异常界面上反复滑动",
            RootScrollResendPolicy.shouldResend(
                moved = false,
                anchors = 5,
                movingAnchors = 0,
                attempts = RootScrollResendPolicy.MAX_ATTEMPTS,
            ),
        )
    }
}
