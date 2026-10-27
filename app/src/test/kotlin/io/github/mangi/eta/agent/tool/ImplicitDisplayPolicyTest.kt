package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 不传 display_id 时该作用在哪块屏（fix176）。
 *
 * 守的是真机那条：刚 observe 过主屏（displayId=0），接着不带 display_id 写文本，
 * 被当成「指定了屏 0」走进虚拟屏通路 —— 文本落地了，工具却报 ACTION_OUTCOME_UNKNOWN。
 */
class ImplicitDisplayPolicyTest {
    @Test
    fun `main screen published as zero stays main screen`() {
        // 真机现场：观察主屏后 displayId 就是 0，不能被当成「指定了虚拟屏 0」。
        assertNull(ImplicitDisplayPolicy.inherit(0))
    }

    @Test
    fun `no observation yet means main screen`() {
        assertNull(ImplicitDisplayPolicy.inherit(null))
    }

    @Test
    fun `a virtual display is inherited as the target`() {
        assertEquals(124, ImplicitDisplayPolicy.inherit(124))
    }

    // 只有 0 会被归一成主屏。像 -1（缺值）这种「不是任何一块屏」的 id 故意原样传下去，
    // 由下游的「虚拟屏已释放或不存在」直接拦住 —— 在那里失败，好过静默回落到用户主屏。
    @Test
    fun `an invalid id is passed through so downstream can refuse it`() {
        assertEquals(-1, ImplicitDisplayPolicy.inherit(-1))
    }
}
