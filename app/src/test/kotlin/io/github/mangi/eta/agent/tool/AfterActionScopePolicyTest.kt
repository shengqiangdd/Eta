package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix165：动作后的观察必须跟着「这次动作的屏」，跨屏时不能拿旧快照做比较。
 */
class AfterActionScopePolicyTest {

    @Test
    fun `主屏动作主屏快照 - 沿用旧快照比较`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = null, snapshotDisplayId = null)
        assertNull(plan.observeDisplayId)
        assertTrue(plan.compareWithPrevious)
        assertTrue(plan.keepScreenshotSpace)
        assertNull(plan.snapshotDisplayId)
    }

    @Test
    fun `虚拟屏动作虚拟屏快照 - 同屏按旧行为比较`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = 148, snapshotDisplayId = 148)
        assertEquals(148, plan.observeDisplayId)
        assertTrue(plan.compareWithPrevious)
        assertTrue(plan.keepScreenshotSpace)
        assertEquals(148, plan.snapshotDisplayId)
    }

    /** 真机复现的那条：快照在虚拟屏、动作显式指向主屏。 */
    @Test
    fun `快照在虚拟屏而动作在主屏 - 观察主屏并丢弃旧快照`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = null, snapshotDisplayId = 148)
        assertNull(plan.observeDisplayId)
        assertFalse(plan.compareWithPrevious)
        assertFalse(plan.keepScreenshotSpace)
        assertNull(plan.snapshotDisplayId)
    }

    /** 反过来：先看主屏、再在虚拟屏上动作。 */
    @Test
    fun `快照在主屏而动作在虚拟屏 - 观察虚拟屏并丢弃旧快照`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = 148, snapshotDisplayId = null)
        assertEquals(148, plan.observeDisplayId)
        assertFalse(plan.compareWithPrevious)
        assertFalse(plan.keepScreenshotSpace)
        assertEquals(148, plan.snapshotDisplayId)
    }

    /** 两块虚拟屏之间切换也算跨屏。 */
    @Test
    fun `两块虚拟屏之间 - 跨屏不给 diff`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = 149, snapshotDisplayId = 148)
        assertEquals(149, plan.observeDisplayId)
        assertFalse(plan.compareWithPrevious)
        assertFalse(plan.keepScreenshotSpace)
        assertEquals(149, plan.snapshotDisplayId)
    }

    /** 主屏的 null 与 0 必须等价，否则「显式写 display_id=0」会被误判成跨屏。 */
    @Test
    fun `主屏 null 与 0 等价 - 不算跨屏`() {
        val plan = AfterActionScopePolicy.plan(actionDisplayId = 0, snapshotDisplayId = null)
        assertTrue(plan.compareWithPrevious)
        assertTrue(plan.keepScreenshotSpace)
    }
}
