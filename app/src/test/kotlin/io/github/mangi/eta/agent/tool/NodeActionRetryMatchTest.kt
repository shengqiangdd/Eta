package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.tool.NodeActionRetryPolicy.NodeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 重试时「新快照里哪个节点才算原来那个」。
 *
 * 用 [NodeSignature] 而不是 `UiNode`：判定本身与无障碍实现无关，这样 6 个
 * 「点错就糟」的用例在任何机器上都真的会跑 —— 此前为了构造 `android.graphics.Rect`
 * 依赖 Robolectric，而本机（aarch64）加载不了它的 native runtime，这些用例从未真正执行过。
 */
class NodeActionRetryMatchTest {

    private fun node(
        index: Int,
        text: String,
        nodeId: String = "",
        className: String = "android.widget.TextView",
        top: Int = 0,
    ) = NodeSignature(
        index = index,
        nodeId = nodeId,
        className = className,
        text = text,
        desc = "",
        left = 0,
        top = top,
        right = 100,
        bottom = top + 50,
    )

    @Test fun stableNodeIdMatchesAcrossDifferentIndices() {
        val target = node(index = 2, text = "确定", nodeId = "id-42")
        val fresh = listOf(
            node(index = 0, text = "取消", nodeId = "id-41"),
            node(index = 7, text = "确定", nodeId = "id-42"),
        )
        // index 已经变了，靠 node_id 找回来 —— 这正是重试能成立的前提。
        assertEquals(7, NodeActionRetryPolicy.matchUniqueNode(fresh, target)!!.index)
    }

    @Test fun duplicateNodeIdsFallBackToExactTuple() {
        val target = node(index = 1, text = "确定", nodeId = "dup")
        val fresh = listOf(
            node(index = 3, text = "取消", nodeId = "dup"),
            node(index = 4, text = "确定", nodeId = "dup"),
        )
        // node_id 撞车时不能瞎选，退回四元组精确匹配。
        assertEquals(4, NodeActionRetryPolicy.matchUniqueNode(fresh, target)!!.index)
    }

    @Test fun emptyNodeIdUsesTuple() {
        val target = node(index = 1, text = "确定")
        val fresh = listOf(
            node(index = 3, text = "取消"),
            node(index = 5, text = "确定"),
        )
        assertEquals(5, NodeActionRetryPolicy.matchUniqueNode(fresh, target)!!.index)
    }

    @Test fun ambiguousTuplesAreRefused() {
        val target = node(index = 1, text = "确定")
        val fresh = listOf(
            node(index = 3, text = "确定"),
            node(index = 4, text = "确定"),
        )
        // 两个一模一样的候选：宁可不点，也不能点错。
        assertNull(NodeActionRetryPolicy.matchUniqueNode(fresh, target))
    }

    @Test fun vanishedNodeIsRefused() {
        val target = node(index = 1, text = "确定")
        val fresh = listOf(node(index = 3, text = "取消"))
        assertNull(NodeActionRetryPolicy.matchUniqueNode(fresh, target))
    }

    @Test fun sameTextDifferentBoundsIsNotAMatch() {
        val target = node(index = 1, text = "确定", top = 0)
        val fresh = listOf(node(index = 1, text = "确定", top = 60))
        // 文本相同但位置变了：可能已经滚走，不能当成同一个控件。
        assertNull(NodeActionRetryPolicy.matchUniqueNode(fresh, target))
    }
}
