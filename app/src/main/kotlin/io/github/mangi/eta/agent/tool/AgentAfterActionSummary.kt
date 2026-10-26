package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（文本、描述、类名、view_id、位置），不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    private const val MAX_DIFF_NODES = 8

    fun build(before: ElementObservation?, after: ElementObservation): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        val diff = before?.let { diff(it.nodes, after.nodes) }
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("changes", diff?.toJson() ?: JSONObject().put("available", false))
            .put("ui_nodes", JSONArray().also { array -> after.nodes.forEach { array.put(it.compactJson()) } })
            .put(
                "note",
                if (changed) "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
                else "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复",
            )
    }

    private data class Diff(
        val added: List<UiNode>,
        val removed: List<UiNode>,
        val changed: List<Pair<UiNode, UiNode>>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("added_count", added.size)
            .put("removed_count", removed.size)
            .put("changed_count", changed.size)
            .put("added", added.take(MAX_DIFF_NODES).toJsonArray())
            .put("removed", removed.take(MAX_DIFF_NODES).toJsonArray())
            .put(
                "changed",
                JSONArray().also { array ->
                    changed.take(MAX_DIFF_NODES).forEach { (old, new) ->
                        array.put(
                            JSONObject()
                                .put("node_id", new.nodeId.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                                .put("before", old.compactJson())
                                .put("after", new.compactJson()),
                        )
                    }
                },
            )

        private fun List<UiNode>.toJsonArray(): JSONArray =
            JSONArray().also { array -> forEach { array.put(it.compactJson()) } }
    }

    private fun diff(before: List<UiNode>, after: List<UiNode>): Diff {
        val remaining = before.toMutableList()
        val added = mutableListOf<UiNode>()
        val changed = mutableListOf<Pair<UiNode, UiNode>>()
        for (node in after) {
            val match = remaining.indexOfFirst { identity(it) == identity(node) }
            if (match < 0) {
                added += node
                continue
            }
            val old = remaining.removeAt(match)
            if (visual(old) != visual(node)) changed += old to node
        }
        return Diff(added = added, removed = remaining, changed = changed)
    }

    private fun identity(node: UiNode): String =
        node.nodeId.takeIf(String::isNotBlank)?.let { "id:$it" }
            ?: "sig:${node.className}|${node.viewId}|${node.text}|${node.desc}"

    private fun visual(node: UiNode): String = listOf(
        node.text, node.desc, node.className, node.viewId, node.bounds.toShortString(),
        node.clickable, node.longClickable, node.scrollable, node.focused,
        node.editable, node.password, node.enabled, node.checked, node.selected, node.hint,
    ).joinToString("|")

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" }

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (nodeId.isNotBlank()) put("node_id", nodeId)
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
