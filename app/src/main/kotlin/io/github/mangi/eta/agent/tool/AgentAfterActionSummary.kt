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
     *
     * 5,000 是实测值：给 7,000 时，最坏情况（30 个节点全部变化、每节点文本满 200 字）
     * 总体积 11,726，离 12,000 只剩 274 字—— `changes` 里最多还有 8×2 个节点副本，
     * 稍长一点就会越过预算。降到 5,000 后余量约 2,300 字。
     */
    private const val NODES_BUDGET_CHARS = 5_000

    fun build(
        before: ElementObservation?,
        after: ElementObservation,
        displayId: Int? = null,
        crossDisplay: Boolean = false,
    ): JSONObject {
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
        // 0 与 null 都表示主屏：resolveDisplayId 会把 0 归一成 null，但调用方也可能直接传 0。
        val mainScreen = displayId == null || displayId == 0
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            // 反馈属于哪块屏必须写出来（fix165）：主屏与虚拟屏可以同时在看，
            // 只说 package 与节点列表，模型无从核对这份反馈是不是自己要操作的那块屏。
            .put(
                "display",
                JSONObject()
                    .put("scope", if (mainScreen) "main" else "virtual")
                    .put("id", displayId ?: 0),
            )
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
                when {
                    crossDisplay ->
                        "这份反馈来自${if (mainScreen) "主屏" else "display $displayId"}，" +
                            "与动作前观察的不是同一块屏：这次没有做前后比较（跨屏差异没有意义）；" +
                            "要刚才那块屏请重新 observe_screen"
                    changed && nodes.shown > 0 ->
                        "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
                    // 页面跳转途中常见：界面确实变了（screen_changed=true），但这一帧一个节点都没取到。
                    // 原来照样说「可直接继续」，模型拿着空节点列表只能盲试或反复报错。
                    changed ->
                        "界面已变化，但这一帧没有取到节点（页面还在加载或正在切窗口）：" +
                            "先用该 observation_id 调用 observe_screen 再操作，不要沿用动作前的节点"
                    else -> "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复"
                },
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
        node.text, node.desc, node.className, node.viewId, boundsKey(node),
        node.clickable, node.longClickable, node.scrollable, node.focused,
        node.editable, node.password, node.enabled, node.checked, node.selected, node.hint,
    ).joinToString("|")

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${boundsKey(it)}" }

    /**
     * 位置键：与 `Rect.toShortString()` 输出相同，但只用字段自行拼接。
     * 原因有两层：一是纯 JVM 单测里 `Rect` 方法未 mock 会直接抛，
     * 二是变化判定本来只依赖几何值，没有必要调 Android 方法。
     */
    private fun boundsKey(node: UiNode): String =
        "[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]"
}
