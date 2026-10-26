package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ui_nodes` 的线格式：`observe_screen` 与「动作后观察」共用同一口径。
 *
 * 为什么要有这一层（2026-10-26 真机实测）：原来的 `UiNode.toJson()` 每节点输出
 * `class` 全限定名、`package`、完整 `view_id`、`node_id`、`bounds` 与 `center` 两个位置字段，
 * 以及 8 个布尔（多数为 false）。按默认 `max_nodes=60` 实测约 13~15KB、`max_nodes=120`
 * 约 27~30KB，全部越过 [io.github.mangi.eta.agent.model.AgentToolSpill] 的 12,000 预算
 * → 结果被判为「大输出」落盘，模型上下文里只剩 4,000 字预览 + 文件路径，
 * 于是每次观察都要多一次 `read_file` 才看得到节点。步数就是这么翻倍的。
 *
 * 三条取舍，都是「模型真的会用到」与「纯冗余」的分界：
 *
 * 1. 键名不缩写（`index`/`text`/`bounds` 保持不变），只去掉冗余值与重复字段：
 *    `package` 与顶层前台包相同时省略；`view_id` 只留 `/` 后一段（`tap_element` 用 `index`，
 *    完整资源名对模型没有增量信息）；`node_id` 默认不输出（没有任何工具接受它，
 *    无障碍的稳定 ID 只在应用内部的 [io.github.mangi.eta.agent.tool.NodeActionRetryPolicy] 里用）。
 * 2. 布尔只输出「非默认值」：`clickable` 等默认 false 的只在 true 时出现，
 *    `enabled=false` 与 `checked=false` 这类「反直觉状态」反而必须保留 —— 省 token 不能省到说谎。
 * 3. 长文本截断而不是丢弃：`text`/`desc` 200 字、`hint` 120 字，截断处补 `…`，
 *    避免个别「整页日志」节点把预算吃光、把其余节点挤出上下文。
 *
 * 实测收益：同一个设置页 60 节点，约 13.6KB → 5.9KB；120 节点约 28KB → 11.4KB。
 */
internal object UiNodeWire {
    /** 单节点文本上限：正常控件文案远小于此，超长时截断，避免一个节点吃掉整棵树的预算。 */
    const val MAX_TEXT_CHARS = 200
    const val MAX_HINT_CHARS = 120

    /**
     * `observe_screen` 给 `ui_nodes` 留的字符预算。
     *
     * 总预算（12,000）里还要放 `focus`、`coordinate_contract`、`capabilities`、
     * `ui_diagnostics`、`screenshot` 等字段，实测合计约 2.5KB，这里留 9,000 有安全余量。
     */
    const val OBSERVE_NODES_BUDGET_CHARS = 9_000

    fun toArray(
        nodes: List<UiNode>,
        foregroundPackage: String,
        includeNodeId: Boolean,
        includeBounds: Boolean,
        budgetChars: Int = Int.MAX_VALUE,
    ): Projection {
        val array = JSONArray()
        var used = 2 // "[]"
        var shown = 0
        for (node in nodes) {
            val projected = node.toWireJson(foregroundPackage, includeNodeId, includeBounds)
            val cost = projected.toString().length + 1
            if (used + cost > budgetChars) break
            used += cost
            array.put(projected)
            shown++
        }
        return Projection(array, shown)
    }

    class Projection(val nodes: JSONArray, val shown: Int)

    fun UiNode.toWireJson(
        foregroundPackage: String,
        includeNodeId: Boolean,
        includeBounds: Boolean,
    ): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (includeNodeId && nodeId.isNotBlank()) put("node_id", nodeId)
            truncate(text, MAX_TEXT_CHARS)?.let { put("text", it) }
            truncate(desc, MAX_TEXT_CHARS)?.let { put("desc", it) }
            if (className.isNotBlank()) put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            // 多窗口 / 系统弹窗里会出现同屏他包节点，那种情况才值得多写 20 个字符。
            if (packageName.isNotBlank() && packageName != foregroundPackage) put("package", packageName)
            // 位置两个字段各司其职：bounds 给区域类任务（region 裁剪、swipe 端点），
            // center 给点击。自己拼串而不调 Rect.toShortString()：纯 JVM 单测里
            // android.graphics.Rect 的方法未 mock 会直接抛，连测试都跑不起来。
            if (includeBounds) {
                put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            }
            // center 用字段自行计算（不调 Rect.centerX()）：原因同上，纯 JVM 单测里那层调用会抛。
            put("center", JSONArray().put((bounds.left + bounds.right) / 2).put((bounds.top + bounds.bottom) / 2))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (focused) put("focused", true)
            if (editable) put("editable", true)
            if (password) put("password", true)
            // 默认值是 true，只有「不可用」才需要说；省略会让模型以为能点。
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            truncate(hint, MAX_HINT_CHARS)?.let { put("hint", it) }
        }

    /**
     * 空白返回 null（不输出空字段）；超长按码点截断并补省略号。
     *
     * 截断点不能落在 UTF-16 代理对中间，否则序列化出孤立代理，模型看到乱码。
     */
    private fun truncate(raw: String, limit: Int): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length <= limit) return text
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end) + "…"
    }
}
