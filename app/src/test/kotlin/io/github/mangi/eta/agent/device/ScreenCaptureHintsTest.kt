package io.github.mangi.eta.agent.device

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图失败码的说明。
 *
 * 回归点：低版本 Android（< 14）在有浮层需要排除时不给截图，
 * `observe_screen` 的 screenshot 段只有一个 `WINDOW_SCREENSHOT_UNSUPPORTED`，
 * 没有一句解释。模型只能猜，猜错就会反复重试或误以为「这台机器不能截图」。
 */
class ScreenCaptureHintsTest {

    @Test
    fun `android below 14 window screenshot unsupported explains why and what to do`() {
        val hint = ScreenCaptureHints.forCode("WINDOW_SCREENSHOT_UNSUPPORTED")
        assertTrue("要说清是系统版本限制", hint!!.contains("Android 14"))
        assertTrue("要说清还有活路：节点仍可用", hint.contains("节点"))
        // 最关键的一条：不能让人以为整个会话都完了。
        assertTrue("要给出可执行替代", hint.contains("收起浮层") || hint.contains("手动截屏"))
    }

    @Test
    fun `unavailable keeps ui nodes usable`() {
        val hint = ScreenCaptureHints.forCode("SCREENSHOT_UNAVAILABLE")
        assertTrue(hint!!.contains("节点"))
    }

    @Test
    fun `virtual frame not ready suggests triggering a change`() {
        val hint = ScreenCaptureHints.forCode("VIRTUAL_FRAME_NOT_READY")
        assertTrue(hint!!.contains("界面变化") || hint.contains("点击"))
    }

    /** 未知/缺失的码不编故事：返回 null，让调用方原样输出 null。 */
    @Test
    fun `unknown code has no invented hint`() {
        assertNull(ScreenCaptureHints.forCode(null))
        assertNull(ScreenCaptureHints.forCode(""))
        assertNull(ScreenCaptureHints.forCode("SOMETHING_ELSE"))
    }
}
