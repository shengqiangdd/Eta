package io.github.mangi.eta.agent.tool

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementSource
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentAfterActionSummaryTest {
    @Test
    fun unchangedScreenIsReportedSoTheModelDoesNotRepeatTheSameAction() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val summary = AgentAfterActionSummary.build(before, observation("o2", "app.a", listOf(node(0, "搜索"))))
        assertFalse(summary.getBoolean("screen_changed"))
        assertFalse(summary.getBoolean("package_changed"))
        assertEquals("o2", summary.getString("observation_id"))
    }

    @Test
    fun changedScreenCarriesCompactNodesAndPackageSwitch() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val after = observation("o2", "app.b", listOf(node(0, "结果", clickable = true)))
        val summary = AgentAfterActionSummary.build(before, after)
        assertTrue(summary.getBoolean("screen_changed"))
        assertTrue(summary.getBoolean("package_changed"))
        val compact = summary.getJSONArray("ui_nodes").getJSONObject(0)
        assertEquals("结果", compact.getString("text"))
        assertEquals("TextView", compact.getString("class"))
        assertTrue(compact.getBoolean("clickable"))
        // 默认值为 false 的字段不输出，节省 token。
        assertFalse(compact.has("editable"))
        assertFalse(compact.has("password"))
    }

    @Test
    fun stableNodeIdsAndDiffAreReported() {
        val before = observation(
            "o1", "app", listOf(
                node(0, "A").copy(nodeId = "n1"),
                node(1, "B").copy(nodeId = "n2"),
            ),
        )
        val after = observation(
            "o2", "app", listOf(
                node(0, "A2").copy(nodeId = "n1"),
                node(1, "C").copy(nodeId = "n3"),
            ),
        )
        val summary = AgentAfterActionSummary.build(before, after)
        val changes = summary.getJSONObject("changes")
        assertEquals(1, changes.getInt("added_count"))
        assertEquals(1, changes.getInt("removed_count"))
        assertEquals(1, changes.getInt("changed_count"))
        assertEquals("n3", changes.getJSONArray("added").getJSONObject(0).getString("node_id"))
        assertEquals("n2", changes.getJSONArray("removed").getJSONObject(0).getString("node_id"))
        assertEquals("n1", changes.getJSONArray("changed").getJSONObject(0).getString("node_id"))
        assertEquals("n1", summary.getJSONArray("ui_nodes").getJSONObject(0).getString("node_id"))
    }

    @Test
    fun toggleStateAndHintAreVisibleToTheModel() {
        val toggle = node(0, "蓝牙").copy(checked = false)
        val field = node(1, "").copy(editable = true, hint = "搜索联系人")
        val nodes = AgentAfterActionSummary.build(null, observation("o1", "app", listOf(toggle, field)))
            .getJSONArray("ui_nodes")
        assertFalse(nodes.getJSONObject(0).getBoolean("checked"))
        assertEquals("搜索联系人", nodes.getJSONObject(1).getString("hint"))
        assertFalse(nodes.getJSONObject(1).has("checked"))
    }

    private fun observation(id: String, pkg: String, nodes: List<UiNode>) = ElementObservation(
        id = id, source = ElementSource.ACCESSIBILITY, packageName = pkg, windowId = 1,
        nodes = nodes, maxNodes = 30, truncated = false,
    )

    private fun node(index: Int, text: String, clickable: Boolean = false) = UiNode(
        index = index, text = text, desc = "", className = "android.widget.TextView", packageName = "app",
        viewId = "", bounds = Rect(0, 0, 100, 50), clickable = clickable, longClickable = false,
        scrollable = false, focused = false, editable = false, password = false, enabled = true,
    )
}
