package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 守「一次动作会被分到哪块屏的 scope」。
 *
 * 协调器现在按 scope 分发操作权（不同屏并行、同屏排队），但前提是这里真的能分出不同的 scope。
 * 一旦它退化成永远返回主屏，跨屏并行就悄无声息地失效了 —— 而那种失效不会报任何错，
 * 只会重新变成「点一下卡很久」。所以路由本身值得单独有测试。
 */
class GuiScopeRoutingTest {
    @Test
    fun explicitDisplayIdWins() {
        assertEquals("display:16", resolveGuiScope(explicitDisplayId = 16, publishedDisplayId = null))
    }

    @Test
    fun differentDisplaysGetDifferentScopes() {
        assertNotEquals(
            resolveGuiScope(explicitDisplayId = 16, publishedDisplayId = null),
            resolveGuiScope(explicitDisplayId = 17, publishedDisplayId = null),
        )
    }

    @Test
    fun explicitDisplayOverridesInheritedOne() {
        assertEquals("display:16", resolveGuiScope(explicitDisplayId = 16, publishedDisplayId = 7))
    }

    @Test
    fun publishedVirtualDisplayIsInherited() {
        assertEquals("display:7", resolveGuiScope(explicitDisplayId = 0, publishedDisplayId = 7))
    }

    @Test
    fun unpublishedObservationFallsBackToMain() {
        assertEquals(GuiControlCoordinator.MainScope, resolveGuiScope(0, null))
    }

    @Test
    fun mainDisplayHintStillMeansMain() {
        assertEquals(GuiControlCoordinator.MainScope, resolveGuiScope(0, 0))
    }
}
