package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.Observation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.device.UiNodeWire
import io.github.mangi.eta.agent.device.ScreenObservationMetadata
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

    /**
     * 动作后默认只带这么多个节点。
     *
     * 以前每次都把整屏（最多 30 个、约 5,000 字）重发一遍：一次会话里 `tap_element`
     * 调 38 次就是 19 万字符的重复上下文，而模型真正需要的往往只是「刚点过的那个东西
     * 变没变」与「新增了什么」。
     *
     * 这里保留少量节点不是为了“好看”：动作后常见下一步就是点相邻项，
     * 给 0 个会逼模型立刻 observe_screen 一次，反而更贵。
     */
    private const val DEFAULT_NODES_AFTER_ACTION = 8

    /**
     * 观察失败或没有节点时的降级摘要。
     *
     * 上游把「动作后的反馈」表达为一次 [RootShellDeviceController.Observation]，
     * 其中 `elementObservation` 可能为 null（页面正在加载、切换窗口、查询失败）。
     * 这种情况下不能沿用上面那份实现：它要求节点非空，且会把 `screen_changed`
     * 说成 true —— 而这一帧根本没有节点可比，说「界面已变化」是编造。
     *
     * 所以这里如实给出 `screen_changed = null` 与 `ui_tree_status`，
     * 让模型知道「不知道变没变，先重新观察」，而不是盲试。
     */
    fun build(before: ElementObservation?, observation: RootShellDeviceController.Observation): JSONObject {
        val metadata = runCatching { JSONObject(observation.content) }.getOrDefault(JSONObject())
        val after = observation.elementObservation
        if (after != null) {
            val base = build(before = before, after = after)
            // 上游的元信息（focus / code / observation_source）有则补上，没有就保持原样。
            metadata.optJSONObject("focus")?.let { base.put("focus", it) }
            metadata.optString("code").takeIf(String::isNotBlank)?.let { base.put("code", it) }
            metadata.optString("observation_source").takeIf(String::isNotBlank)
                ?.let { base.put("observation_source", it) }
            return base
        }
        val status = metadata.optString("ui_tree_status").ifBlank { "query_failed" }
        return JSONObject()
            .put("observation_id", JSONObject.NULL)
            .put("package", metadata.optJSONObject("focus")?.optString("package").orEmpty())
            // 不知道变没变：null 而不是 false —— false 会被读成「界面没动」。
            .put("screen_changed", JSONObject.NULL)
            .put("ui_tree_status", status)
            .put("ui_tree_truncated", false)
            .put("ui_nodes", JSONArray())
            .put("ui_nodes_shown", 0)
            .put(
                "note",
                metadata.optString("ui_tree_note").ifBlank {
                    "动作已执行，但这一帧没有取到节点（页面还在加载或正在切窗口）：" +
                        "先用该 observation_id 调用 observe_screen 再操作，不要沿用动作前的节点"
                },
            )
            .apply {
                metadata.optJSONObject("focus")?.let { put("focus", it) }
                metadata.optString("code").takeIf(String::isNotBlank)?.let { put("code", it) }
            }
    }

    fun build(
        before: ElementObservation?,
        after: ElementObservation,
        displayId: Int? = null,
        crossDisplay: Boolean = false,
    ): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        val diff = before?.let { diff(it.nodes, after.nodes) }
        // 只给「值得给」的节点：整屏重发是上下文里最大的一块重复。
        // 跨屏或界面大改时给多一点（模型此时确实需要看清新界面），否则只给变化涉及的。
        val nodeBudget = when {
            crossDisplay -> DEFAULT_NODES_AFTER_ACTION
            changed && diff != null && diff.added.size + diff.changed.size > DEFAULT_NODES_AFTER_ACTION ->
                DEFAULT_NODES_AFTER_ACTION
            changed -> DEFAULT_NODES_AFTER_ACTION
            // 界面没变：这次动作没造成任何可见变化，全量重发毫无信息量。
            else -> 0
        }
        val candidateNodes = pickNodesToReport(after.nodes, diff, changed, crossDisplay)
        val nodes = UiNodeWire.toArray(
            nodes = candidateNodes.take(nodeBudget),
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
                        "以上是动作后与本次操作相关的节点（不是整屏）：可直接用该 observation_id 继续操作；" +
                            "要完整节点或截图时再调用 observe_screen"
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

    /**
     * 选出「这次动作后真值得给模型看」的节点。
     *
     * 优先级：新增的（界面刚出来的）→ 内容变化的（刚点过的东西状态变了）→ 剩余节点里
     * 可点击的（下一步可能要点）。`removed` 不参与：已经不在界上了，给了也没法操作。
     *
     * 界面没变时返回空：那说明动作没有造成任何可见后果，重发整屏只会白占上下文。
     */
    private fun pickNodesToReport(
        afterNodes: List<UiNode>,
        diff: Diff?,
        changed: Boolean,
        crossDisplay: Boolean,
    ): List<UiNode> {
        if (!changed) return emptyList()
        // 跨屏或没有前一张快照：模型对当前界面一无所知，按界面顺序给前面的节点。
        if (crossDisplay || diff == null) return afterNodes

        // 按节点 index 去重：UiNode 是 data class，它的 hashCode 会落到
        // android.graphics.Rect（纯 JVM 单测里没 mock 会直接抛），而 index 在本快照内唯一。
        val picked = LinkedHashSet<Int>()
        val ordered = LinkedHashMap<Int, UiNode>()
        fun add(node: UiNode): Boolean {
            if (picked.size >= DEFAULT_NODES_AFTER_ACTION) return false
            if (!picked.add(node.index)) return false
            ordered[node.index] = node
            return true
        }
        diff.added.forEach(::add)
        diff.changed.forEach { (_, new) -> add(new) }
        // 再把剩余可点的补上（按界面顺序，保证 index 可用）。
        afterNodes.filter { it.clickable }.forEach(::add)
        afterNodes.forEach(::add)
        return ordered.values.toList()
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
