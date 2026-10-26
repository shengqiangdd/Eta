package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scroll` 的「先判定再动手」判据。
 *
 * 这些用例守的是三件在真机上踩过的事：
 * 1. 没有可滚动节点的页面上，整屏盲滑会触发翻页 / 下拉刷新（取消掉，改让模型用 swipe）；
 * 2. 树还没画完时「没看到可滚动节点」不等于「页面不可滚动」（要求先 observe）；
 * 3. 两种取消都必须告诉模型「原样重试没用」，否则它会照原参数再来一次。
 */
class ScrollTargetPolicyTest {
    @Test
    fun `a scrollable node always wins`() {
        assertEquals(
            ScrollTargetPolicy.Decision.PROCEED,
            ScrollTargetPolicy.decide(nodeCount = 20, scrollableCount = 1),
        )
        // 树很小但确实读到可滚动节点：那是可信的正面证据，照常滚。
        assertEquals(
            ScrollTargetPolicy.Decision.PROCEED,
            ScrollTargetPolicy.decide(nodeCount = 2, scrollableCount = 1),
        )
    }

    @Test
    fun `a tiny tree without scrollable nodes is unreadable not empty`() {
        // 真机教训（fix155）：应用刚启动时无障碍只回 2 个容器节点。
        assertEquals(
            ScrollTargetPolicy.Decision.TREE_UNREADABLE,
            ScrollTargetPolicy.decide(nodeCount = 2, scrollableCount = 0),
        )
        assertEquals(
            ScrollTargetPolicy.Decision.TREE_UNREADABLE,
            ScrollTargetPolicy.decide(nodeCount = 0, scrollableCount = 0),
        )
    }

    @Test
    fun `a full tree without scrollable nodes is a definite no target`() {
        assertEquals(
            ScrollTargetPolicy.Decision.NO_TARGET,
            ScrollTargetPolicy.decide(nodeCount = ScrollTargetPolicy.MIN_TRUSTED_NODES, scrollableCount = 0),
        )
        assertEquals(
            ScrollTargetPolicy.Decision.NO_TARGET,
            ScrollTargetPolicy.decide(nodeCount = 40, scrollableCount = 0),
        )
    }

    @Test
    fun `codes tell the model what actually happened`() {
        assertEquals(
            ScrollTargetPolicy.NO_TARGET_CODE,
            ScrollTargetPolicy.codeFor(ScrollTargetPolicy.Decision.NO_TARGET),
        )
        assertEquals(
            ScrollTargetPolicy.TREE_UNREADABLE_CODE,
            ScrollTargetPolicy.codeFor(ScrollTargetPolicy.Decision.TREE_UNREADABLE),
        )
        // 两个码不能相同：模型（与文档）靠它们区分「页面确实不能滚」与「还没看清」。
        assertTrue(
            ScrollTargetPolicy.NO_TARGET_CODE != ScrollTargetPolicy.TREE_UNREADABLE_CODE,
        )
    }

    @Test
    fun `messages say why and what to do instead`() {
        val noTarget = ScrollTargetPolicy.messageFor(
            ScrollTargetPolicy.Decision.NO_TARGET,
            nodeCount = 40,
            scrollableCount = 0,
        )
        assertTrue(noTarget.contains("40"))
        assertTrue(noTarget.contains("swipe"))
        assertTrue(noTarget.contains("不要原样重试"))

        val unreadable = ScrollTargetPolicy.messageFor(
            ScrollTargetPolicy.Decision.TREE_UNREADABLE,
            nodeCount = 2,
            scrollableCount = 0,
        )
        assertTrue(unreadable.contains("observe_screen"))
        assertTrue(unreadable.contains("不要原样重试"))
    }

    @Test
    fun `proceed message never claims a failure`() {
        val ok = ScrollTargetPolicy.messageFor(
            ScrollTargetPolicy.Decision.PROCEED,
            nodeCount = 30,
            scrollableCount = 3,
        )
        assertFalse(ok.contains("取消"))
    }
}
