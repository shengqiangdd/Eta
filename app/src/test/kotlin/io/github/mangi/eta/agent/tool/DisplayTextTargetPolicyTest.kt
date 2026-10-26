package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「index + display_id 同时出现时必须拒绝」（fix164）。
 *
 * 守的是真机那条：`type_text(text, index=0, display_id=131)` 返回 `ok=true`，
 * 文本其实写进了 131 的焦点输入框 —— index 被静默忽略。
 */
class DisplayTextTargetPolicyTest {
    @Test
    fun `an index on a virtual display is rejected`() {
        assertEquals(
            DisplayTextTargetPolicy.Target.REJECTED_INDEX,
            DisplayTextTargetPolicy.resolve(hasIndex = true, displayId = 131),
        )
    }

    @Test
    fun `writing to a virtual display without an index stays on the focused field`() {
        assertEquals(
            DisplayTextTargetPolicy.Target.FOCUS_ONLY,
            DisplayTextTargetPolicy.resolve(hasIndex = false, displayId = 131),
        )
    }

    @Test
    fun `an index on the main screen keeps working`() {
        // 主屏走无障碍 SET_TEXT，index 是有意义的。
        assertEquals(
            DisplayTextTargetPolicy.Target.FOCUS_ONLY,
            DisplayTextTargetPolicy.resolve(hasIndex = true, displayId = null),
        )
    }

    @Test
    fun `focus-only writes on the main screen are unaffected`() {
        assertEquals(
            DisplayTextTargetPolicy.Target.FOCUS_ONLY,
            DisplayTextTargetPolicy.resolve(hasIndex = false, displayId = null),
        )
    }
}
