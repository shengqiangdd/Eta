package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix165：主屏采集不能被虚拟屏上的活跃窗口劫持。
 */
class AccessibleWindowSelectionTest {

    @Test
    fun `活跃窗口就在主屏 - 不做干预`() {
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = 2059,
            mainDisplayWindowIds = listOf(2059, 2058),
            mainDisplayApplicationWindowIds = listOf(2059),
        )
        assertEquals(2059, choice.windowId)
        assertFalse(choice.replacedActive)
    }

    /** 真机复现：活跃窗口是虚拟屏的 2848，主屏上还有桌面窗口。 */
    @Test
    fun `活跃窗口属于虚拟屏 - 改取主屏应用窗口`() {
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = 2848,
            mainDisplayWindowIds = listOf(2059, 2058),
            mainDisplayApplicationWindowIds = listOf(2059),
        )
        assertEquals(2059, choice.windowId)
        assertTrue(choice.replacedActive)
    }

    @Test
    fun `主屏只剩系统窗口 - 用第一个主屏窗口而不是别的屏`() {
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = 2848,
            mainDisplayWindowIds = listOf(2058),
            mainDisplayApplicationWindowIds = emptyList(),
        )
        assertEquals(2058, choice.windowId)
        assertTrue(choice.replacedActive)
    }

    @Test
    fun `拿不到活跃窗口 - 用主屏应用窗口`() {
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = -1,
            mainDisplayWindowIds = listOf(2059),
            mainDisplayApplicationWindowIds = listOf(2059),
        )
        assertEquals(2059, choice.windowId)
        assertTrue(choice.replacedActive)
    }

    /** 查不到主屏窗口（旧系统/权限不足）时保持旧行为，不能凭猜测换窗口。 */
    @Test
    fun `没有主屏窗口信息 - 沿用活跃窗口`() {
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = 2848,
            mainDisplayWindowIds = emptyList(),
            mainDisplayApplicationWindowIds = emptyList(),
        )
        assertEquals(2848, choice.windowId)
        assertFalse(choice.replacedActive)
    }
}
