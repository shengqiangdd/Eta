package io.github.mangi.eta.agent.device

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui_nodes` 线格式的门禁。
 *
 * 这里的用例都来自一次真机缺陷（2026-10-26）：`max_nodes=120` 的观察结果 27~30KB，
 * 越过溢写预算被落盘，模型只能看到 4,000 字预览，于是每次观察都多一步 `read_file`。
 * 所以「体积」本身是验收项，不只是字段形状。
 */
class UiNodeWireTest {

    @Test
    fun defaultBooleansAreOmittedButCounterintuitiveStateSurvives() {
        val plain = node(index = 0, text = "设置")
        val disabled = node(index = 1, text = "已停用").copy(enabled = false)
        val toggle = node(index = 2, text = "蓝牙").copy(checked = false)
        val array = project(listOf(plain, disabled, toggle), "app").nodes

        val first = array.getJSONObject(0)
        assertFalse("默认 false 的布尔不要输出", first.has("clickable"))
        assertFalse(first.has("long_clickable"))
        assertFalse(first.has("password"))
        // enabled 默认为 true，省略会让人以为控件能点；checked=false 同理。
        assertFalse(array.getJSONObject(1).getBoolean("enabled"))
        assertFalse(array.getJSONObject(2).getBoolean("checked"))
    }

    @Test
    fun classAndViewIdKeepOnlyTheMeaningfulTail() {
        val node = node(index = 0, text = "标题").copy(
            className = "android.widget.TextView",
            viewId = "io.github.mangi.eta.canary:id/title",
        )
        val json = project(listOf(node), "app").nodes.getJSONObject(0)
        assertEquals("TextView", json.getString("class"))
        assertEquals("title", json.getString("view_id"))
    }

    @Test
    fun packageIsOmittedOnlyWhenItMatchesTheForeground() {
        val same = node(index = 0, text = "A")
        val other = node(index = 1, text = "B").copy(packageName = "com.other")
        val array = project(listOf(same, other), "app").nodes
        assertFalse("与前台包相同的节点不必重复 3 个字段名+包名", array.getJSONObject(0).has("package"))
        assertEquals("com.other", array.getJSONObject(1).getString("package"))
    }

    @Test
    fun longTextIsTruncatedWithoutSplittingSurrogatePairs() {
        val emoji = "😀" // 代理对，截断点不能落在中间
        val longText = emoji.repeat(150) // 300 个 UTF-16 单元
        val json = project(listOf(node(index = 0, text = longText)), "app").nodes.getJSONObject(0)
        val text = json.getString("text")
        assertTrue(text.length <= UiNodeWire.MAX_TEXT_CHARS + 1)
        assertTrue(text.endsWith("…"))
        assertFalse(
            "截断后不能留孤立代理",
            Character.isHighSurrogate(text[text.length - 2]) && !Character.isLowSurrogate(text[text.length - 1]),
        )
    }

    @Test
    fun budgetTrimsTheTailAndKeepsOriginalIndexes() {
        val nodes = (0 until 120).map { node(index = it, text = "节点$it") }
        val projection = project(nodes, "app", budgetChars = 1_200)
        assertNotNull(projection)
        assertTrue("预算应当真的裁掉尾部节点", projection.shown < nodes.size)
        val array = projection.nodes
        assertEquals(projection.shown, array.length())
        // index 是快照里的下标，裁剪不能重编号，否则 tap_element 会点到别的控件。
        for (i in 0 until array.length()) {
            assertEquals(i, array.getJSONObject(i).getInt("index"))
        }
    }

    @Test
    fun defaultBudgetFitsTheDefaultNodeCountSoObservationStaysInContext() {
        // 默认 max_nodes=60；只要这棵树的投影不超过观察预算，就不会被溢写落盘。
        val nodes = (0 until 60).map {
            node(index = it, text = "设置项 $it").copy(
                desc = "第 $it 项的描述",
                viewId = "io.github.mangi.eta.canary:id/item_$it",
                className = "android.widget.LinearLayout",
                clickable = true,
            )
        }
        val projected = project(nodes, "app").nodes.toString().length
        val legacy = legacyArray(nodes).toString().length
        println("ui_nodes 体积：60 节点 旧=$legacy 新=$projected")
        assertTrue(
            "60 个节点的投影必须落在观察预算内（实测约 6KB），实际 $projected",
            projected <= UiNodeWire.OBSERVE_NODES_BUDGET_CHARS,
        )
        assertTrue(
            "旧格式同规模约 13~15KB，投影至少要省一半",
            projected < legacy / 2,
        )
    }

    private fun project(
        nodes: List<UiNode>,
        foreground: String,
        budgetChars: Int = UiNodeWire.OBSERVE_NODES_BUDGET_CHARS,
    ) = UiNodeWire.toArray(
        nodes = nodes,
        foregroundPackage = foreground,
        includeNodeId = false,
        includeBounds = true,
        budgetChars = budgetChars,
    )

    /** 旧实现（`UiNode.toJson`）的字段形状，只用于体积对照，不参与生产代码。 */
    private fun legacyArray(nodes: List<UiNode>): JSONArray = JSONArray().also { array ->
        for (node in nodes) {
            array.put(
                JSONObject()
                    .put("index", node.index)
                    .put("text", node.text)
                    .put("desc", node.desc)
                    .put("class", node.className)
                    .put("package", node.packageName)
                    .put("view_id", node.viewId)
                    .put("bounds", "[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
                    .put(
                        "center",
                        JSONObject()
                            .put("x", (node.bounds.left + node.bounds.right) / 2)
                            .put("y", (node.bounds.top + node.bounds.bottom) / 2),
                    )
                    .put("clickable", node.clickable)
                    .put("long_clickable", node.longClickable)
                    .put("scrollable", node.scrollable)
                    .put("focused", node.focused)
                    .put("editable", node.editable)
                    .put("password", node.password)
                    .put("enabled", node.enabled),
            )
        }
    }

    private fun node(index: Int, text: String) = UiNode(
        index = index,
        text = text,
        desc = "",
        className = "android.widget.TextView",
        packageName = "app",
        viewId = "",
        bounds = Rect(0, 0, 100, 50),
        clickable = false,
        longClickable = false,
        scrollable = false,
        focused = false,
        editable = false,
        password = false,
        enabled = true,
    )
}
