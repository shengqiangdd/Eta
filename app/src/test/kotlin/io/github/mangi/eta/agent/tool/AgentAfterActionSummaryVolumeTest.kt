package io.github.mangi.eta.agent.tool

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementSource
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.model.AgentToolSpill
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作后摘要的体积门禁（纯 JVM，本机 aarch64 可跑）。
 *
 * 为什么单独一个类：`AgentAfterActionSummaryTest` 是 Robolectric（本机不支持），
 * 而「摘要会不会把动作结果顶出上下文预算」这件事必须有一条真跑的用例——
 * 它一旦越界，`tap_element` 的结果就会被溢写落盘，模型看不到 after，
 * 反而要再 `observe_screen` 一次，正好和「减少步数」的目标相反。
 */
class AgentAfterActionSummaryVolumeTest {

    @Test
    fun extremeLongTextStillFitsOneContextBudget() {
        // 最坏情况：30 个节点全部变化、每节点文本都达到 200 字截断上限。
        val before = observation("o1", (0 until 30).map { node(it, "旧标题 $it") })
        val after = observation(
            "o2",
            (0 until 30).map { index -> node(index, "新标题 $index " + "很长的正文内容".repeat(40)) },
        )
        val summary = AgentAfterActionSummary.build(before, after)
        val cost = AgentToolSpill.contextCost(summary.toString())
        println("after 摘要最坏情况：节点=${summary.getInt("ui_nodes_shown")}/30 cost=$cost 预算=${AgentToolSpill.DEFAULT_CONTEXT_BUDGET}")
        assertNotNull(summary.getJSONArray("ui_nodes"))
        assertTrue(
            "动作后摘要越过 11,000（实际 $cost）：留的 1,000 缓冲是为了外层字段与后续小改动，" +
                "真到 12,000 就会被整段溢写落盘",
            cost <= 11_000,
        )
    }

    @Test
    fun longTextIsMarkedAsClippedInsteadOfSilentlyShortened() {
        val after = observation("o1", listOf(node(0, "字".repeat(500))))
        val node = AgentAfterActionSummary.build(null, after).getJSONArray("ui_nodes").getJSONObject(0)
        assertTrue("截断必须显式标记，否则模型会以为看到的是全文", node.getBoolean("text_clipped"))
        assertTrue(node.getString("text").endsWith("…"))
    }

    private fun observation(id: String, nodes: List<UiNode>) = ElementObservation(
        id = id,
        source = ElementSource.ACCESSIBILITY,
        packageName = "app",
        windowId = 1,
        nodes = nodes,
        maxNodes = 60,
        truncated = false,
    )

    private fun node(index: Int, text: String) = UiNode(
        index = index,
        text = text,
        desc = "",
        className = "android.widget.TextView",
        packageName = "app",
        viewId = "io.github.mangi.eta.canary:id/row_$index",
        bounds = Rect(0, index * 60, 1080, index * 60 + 55),
        clickable = true,
        longClickable = false,
        scrollable = false,
        focused = false,
        editable = false,
        password = false,
        enabled = true,
        nodeId = "node-$index",
    )
}
