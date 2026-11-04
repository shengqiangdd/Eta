package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务可见性。
 *
 * 回归点：后台任务记录是一份全局文件，而 `list()` 以前完全不过滤 ——
 * 所有会话看到同一批任务。表现是「别人派的活出现在我的面板里，我以为是自己的」。
 */
class BackgroundTaskOwnershipTest {

    @Test
    fun `own task is visible`() {
        assertTrue(BackgroundTaskOwnership.visible("conv-a", "conv-a", includeOther = false))
    }

    @Test
    fun `other session task is hidden by default`() {
        assertFalse(BackgroundTaskOwnership.visible("conv-b", "conv-a", includeOther = false))
    }

    @Test
    fun `include other reveals everything`() {
        assertTrue(BackgroundTaskOwnership.visible("conv-b", "conv-a", includeOther = true))
    }

    /**
     * 没有归属的旧记录必须可见。
     *
     * 否则升级后，用户之前在后台跑的活会突然从面板消失、也 stop 不掉 ——
     * 而那条记录本身并没有任何能用来判断归属的信息。
     */
    @Test
    fun `unowned task stays visible`() {
        assertTrue(BackgroundTaskOwnership.visible(null, "conv-a", includeOther = false))
        assertTrue(BackgroundTaskOwnership.visible("", "conv-a", includeOther = false))
    }

    /** 调用方本身没有会话概念时不设限，否则语音一次性入口会看不到自己的任务。 */
    @Test
    fun `viewer without a scope is not restricted`() {
        assertTrue(BackgroundTaskOwnership.visible("conv-b", null, includeOther = false))
        assertTrue(BackgroundTaskOwnership.visible("conv-b", "  ", includeOther = false))
    }

    /** 空白 scope 不能被当成普通字符串比较 —— 否则 " " 会把所有任务挡掉。 */
    @Test
    fun `blank viewer scope does not accidentally hide everything`() {
        assertTrue(BackgroundTaskOwnership.visible("conv-a", " ", includeOther = false))
    }
}
