package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分叉重入守卫。
 *
 * 三个场景都是真实发生过的：连点三次建出三个会话、失败后必须能重试、
 * 成功后必须能再分叉。
 */
class BranchRequestGuardTest {

    @Test
    fun `第一次进入放行，重复点击被拒绝`() {
        val guard = BranchRequestGuard()
        assertTrue(guard.tryBegin())
        assertTrue(guard.isInFlight())
        // 后续点击必须被拒，否则每点一次多一个会话。
        assertFalse(guard.tryBegin())
        assertFalse(guard.tryBegin())
    }

    @Test
    fun `结束后可以再次分叉`() {
        val guard = BranchRequestGuard()
        assertTrue(guard.tryBegin())
        guard.finish()
        assertFalse(guard.isInFlight())
        // 成功后用户可能还想从别处再分一次，不能永久锁死。
        assertTrue(guard.tryBegin())
    }

    @Test
    fun `失败路径结束后仍可重试`() {
        val guard = BranchRequestGuard()
        assertTrue(guard.tryBegin())
        // 模拟 finally 中的复位：抛异常也必须走这一步，否则功能永久卡住。
        guard.finish()
        assertTrue(guard.tryBegin())
    }

    @Test
    fun `重复 finish 不会把状态弄乱`() {
        val guard = BranchRequestGuard()
        assertTrue(guard.tryBegin())
        guard.finish()
        guard.finish()
        assertFalse(guard.isInFlight())
        assertTrue(guard.tryBegin())
    }
}
