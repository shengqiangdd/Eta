package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.device.UiNodeWire
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（文本、描述、类名、view_id、位置），不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    private const val MAX_DIFF_NODES = 8

    /**
     * 动作后附带节点也要守体积预算：这份摘要与前面的工具结果进的是同一条上下文，
     * 超了同样会被溢写落盘（模型的下一步就只能去 read_file，而不是直接点）。
     * 7,000 给 `changes`（最多 8×3 个节点）与结论字段留了余量。
     */
    private const val NODES_BUDGET_CHARS = 7_000

    fun build(before: ElementObservation?, after: ElementObservation): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        val diff = before?.let { diff(it.nodes, after.nodes) }
        val nodes = UiNodeWire.toArray(
            nodes = after.nodes,
            foregroundPackage = after.packageName,
            includeNodeId = true,
            includeBounds = false,
            budgetChars = NODES_BUDGET_CHARS,
        )
        val nodesTruncated = nodes.shown < after.nodes.size
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("changes", diff?.toJson(after.packageName) ?: JSONObject().put("available", false))
            .put("ui_nodes", nodes.nodes)
            .put("ui_nodes_shown", nodes.shown)
            .put("ui_nodes_truncated", nodesTruncated)
            .put(
                "note",
                if (changed) "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
                else "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复",
            )
            .apply {
                if (nodesTruncated) {
                    put(
                        "ui_nodes_note",
                        "只附带了 ${nodes.shown}/${after.nodes.size} 个节点（按上下文体积裁剪尾部）；" +
                            "已带上的 index 可直接用于 tap_element。",
                    )
                }
            }
    }

    private data class Diff(
        val added: List<UiNode>,
        val removed: List<UiNode>,
        val changed: List<Pair<UiNode, UiNode>>,
    ) {
        fun toJson(foregroundPackage: String): JSONObject = JSONObject()
            .put("added_count", added.size)
            .put("removed_count", removed.size)
            .put("changed_count", changed.size)
            .put("added", added.take(MAX_DIFF_NODES).toJsonArray(foregroundPackage))
            .put("removed", removed.take(MAX_DIFF_NODES).toJsonArray(foregroundPackage))
            .put(
                "changed",
                JSONArray().also { array ->
                    changed.take(MAX_DIFF_NODES).forEach { (old, new) ->
                        array.put(
                            JSONObject()
                                .put("node_id", new.nodeId.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                                .put("before", old.toDiffJson(foregroundPackage))
                                .put("after", new.toDiffJson(foregroundPackage)),
                        )
                    }
                },
            )

        private fun List<UiNode>.toJsonArray(foregroundPackage: String): JSONArray =
            JSONArray().also { array -> forEach { array.put(it.toDiffJson(foregroundPackage)) } }

        /** 差异条目里的节点：与 `ui_nodes` 同一口径，只是不带 bounds（差异看 center 已够）。 */
        private fun UiNode.toDiffJson(foregroundPackage: String): JSONObject =
            UiNodeWire.toArray(
                nodes = listOf(this),
                foregroundPackage = foregroundPackage,
                includeNodeId = true,
                includeBounds = false,
            ).nodes.getJSONObject(0)
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
}
