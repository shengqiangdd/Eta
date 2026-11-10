package io.github.mangi.eta.hook.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨应用放行判定的边界。
 *
 * 这层测试存在的理由：上游那版放行条件依赖「当前正处于 createVirtualDisplay 调用中」，
 * 而 `canPlaceEntityOnDisplay()` 在 startActivity 时才触发，标记早已复位 ——
 * 看起来严谨，实际永不命中。所以这里专门钉住「判定只看归属，不看调用时刻」。
 */
class VirtualDisplayCallerPolicyTest {

    private val ownDisplays = setOf(2, 7, 15)

    @Test
    fun `Eta 自己建过的屏 + Eta 调用方 才放行`() {
        assertTrue(VirtualDisplayCallerPolicy.allows(7, ownDisplays, callerIsEta = true))
    }

    @Test
    fun `不是 Eta 的屏一律不放行`() {
        // 0 是主屏、1 是系统副显、99 是别人建的
        assertFalse(VirtualDisplayCallerPolicy.allows(0, ownDisplays, callerIsEta = true))
        assertFalse(VirtualDisplayCallerPolicy.allows(1, ownDisplays, callerIsEta = true))
        assertFalse(VirtualDisplayCallerPolicy.allows(99, ownDisplays, callerIsEta = true))
    }

    @Test
    fun `Eta 的屏上，非 Eta 调用方不放行`() {
        // 关键：别的应用在 Eta 的虚拟屏上也不能借道提升能力。
        assertFalse(VirtualDisplayCallerPolicy.allows(7, ownDisplays, callerIsEta = false))
    }

    @Test
    fun `拿不到 displayId 时不放行`() {
        assertFalse(VirtualDisplayCallerPolicy.allows(null, ownDisplays, callerIsEta = true))
    }

    @Test
    fun `创建失败的返回值不会被当成 Eta 的屏`() {
        // createVirtualDisplay 失败可能返回 0 或负数，即使被误登记也不能放行。
        assertFalse(VirtualDisplayCallerPolicy.allows(0, setOf(0), callerIsEta = true))
        assertFalse(VirtualDisplayCallerPolicy.allows(-1, setOf(-1), callerIsEta = true))
        assertFalse(VirtualDisplayCallerPolicy.allows(-8, setOf(-8), callerIsEta = true))
    }

    @Test
    fun `空集合时什么都不放行`() {
        assertFalse(VirtualDisplayCallerPolicy.allows(2, emptySet(), callerIsEta = true))
    }
}
