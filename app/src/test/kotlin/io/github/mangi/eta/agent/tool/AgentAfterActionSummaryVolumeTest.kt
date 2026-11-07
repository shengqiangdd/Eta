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

    /**
     * 真机数据：一次会话里 `tap_element` 调 38 次，每次回整屏（最多 30 节点、约 5,000 字），
     * 单这一项就是约 19 万字符的重复上下文，而模型需要的只是「刚点的那个东西变没变」。
     */
    @Test
    fun unchangedScreenSendsNoNodesAtAll() {
        val nodes = (0 until 20).map { node(it, "条目 $it") }
        val summary = AgentAfterActionSummary.build(observation("o1", nodes), observation("o2", nodes))

        assertTrue("界面没变就不该重发整屏", !summary.getBoolean("screen_changed"))
        assertTrue(
            "界面没变时不该带节点（实际 ${summary.getInt("ui_nodes_shown")} 个）",
            summary.getInt("ui_nodes_shown") == 0,
        )
    }

    @Test
    fun changedScreenSendsOnlyAFewNodes() {
        val before = observation("o1", (0 until 20).map { node(it, "条目 $it") })
        val after = observation("o2", (0 until 20).map { node(it, "条目 $it") } + node(20, "新增一行"))
        val summary = AgentAfterActionSummary.build(before, after)

        val shown = summary.getInt("ui_nodes_shown")
        assertTrue("动作后不该重发整屏（实际 $shown 个）", shown <= 8)
        assertTrue("至少要给一个可操作的节点", shown >= 1)
        val texts = summary.getJSONArray("ui_nodes").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).optString("text") }
        }
        assertTrue("新增的那一项要包含在回传节点里，实际=$texts", texts.any { it.contains("新增一行") })
    }

    @Test
    fun firstActionWithoutSnapshotStillReturnsOperableNodes() {
        val after = observation("o1", (0 until 20).map { node(it, "条目 $it") })
        val summary = AgentAfterActionSummary.build(null, after)
        assertTrue(
            "没有前一张快照时也要能继续操作（实际 ${summary.getInt("ui_nodes_shown")} 个）",
            summary.getInt("ui_nodes_shown") in 1..8,
        )
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
