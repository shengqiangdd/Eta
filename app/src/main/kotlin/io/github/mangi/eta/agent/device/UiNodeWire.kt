package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.model.AgentToolSpill
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

    /**
     * 采集端（无障碍 / uiautomator 回退）用的文本上限，必须与 [MAX_TEXT_CHARS] 是同一个数。
     *
     * fix178：采集端原来各自写死 `take(120)`，比这里的 200 更小 —— 于是线格式永远看不到超长文本，
     * `text_clipped` 一次都不会出现。真机实测（2026-10-27）：243 字的消息在 `ui_nodes` 里只有
     * 120 字，整份报告里 `text_clipped` 出现 0 次，模型拿到「被砍过的片段」却以为是全文。
     * 现在两边共用一个上限，原始长度随 [UiNode.textFullLength] 带下来。
     */
    const val MAX_NODE_TEXT_CHARS = MAX_TEXT_CHARS
    const val MAX_HINT_CHARS = 120

    /**
     * `observe_screen` 给 `ui_nodes` 留的字符预算。
     *
     * 总预算（12,000）里还要放 `focus`、`coordinate_contract`、`capabilities`、
     * `ui_diagnostics`、`screenshot` 等字段（真机实测合计约 2~3KB，虚拟屏的 display.note 更长），
     * 所以节点部分只给 8,000，留 4,000 余额。
     * 这个数不是拍脑袋：`AgentAfterActionSummaryVolumeTest` 用最坏情况（每节点文本满 200 字）
     * 实测过剩余空间——第一版给 9,000 时余量只剩 274 字，一遇到长 note 就会溢出。
     */
    const val OBSERVE_NODES_BUDGET_CHARS = 8_000

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
            // 与溢写判定同口径：按「转义后真正进入上下文的字符数」算。
            // 用 JSON 长度估算会低估含大量引号/反斜杠的界面（代码、JSON 查看器），
            // 表现为「以为在预算内」但 spill 仍判超 → 白裁了节点还是被落盘。
            val cost = AgentToolSpill.contextCost(projected.toString()) + 1
            if (used + cost > budgetChars) break
            used += cost
            array.put(projected)
            shown++
        }
        return Projection(array, shown)
    }

    class Projection(val nodes: JSONArray, val shown: Int)

    /**
     * 「节点被上限截断」的唯一判定口径（fix180）。
     *
     * 采集端多收一个节点专门用来回答这个问题：**只有确实多出来**才算截断。
     * 原来 uiautomator 回退路径写的是 `rootNodes.size >= nodeLimit` —— 界面节点数恰好
     * 等于上限时也报 truncated=true，于是模型看到「还有更多节点，先 scroll」，
     * 而树其实已经全给了，白跑一趟动作还不一定找得到东西。
     */
    fun nodesExceededLimit(found: Int, limit: Int): Boolean = found > limit

    fun UiNode.toWireJson(
        foregroundPackage: String,
        includeNodeId: Boolean,
        includeBounds: Boolean,
    ): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (includeNodeId && nodeId.isNotBlank()) put("node_id", nodeId)
            // 截断必须显式标记：不说的话模型会以为那就是全部内容（聊天/日志/正文节点尤其危险）。
            putClippedText("text", text, textFullLength, MAX_TEXT_CHARS)
            putClippedText("desc", desc, descFullLength, MAX_TEXT_CHARS)
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
            putClippedText("hint", hint, hintFullLength, MAX_HINT_CHARS)
        }

    /**
     * 输出一个可能被截断的文本字段，并保证「被砍过」这件事一定写出来。
     *
     * 两种砍法都要标：
     * 1. 这一层砍的（超过 [limit]）→ 值末尾补 `…`，同时打 `<field>_clipped`；
     * 2. 采集端先砍过的（[fullLength] 大于手上这段）→ 显示文本里看不出来，
     *    只有原始长度能说明它被砍了，所以额外给 `<field>_len`，让模型知道「还有多少没看到」。
     */
    private fun JSONObject.putClippedText(
        field: String,
        raw: String,
        fullLength: Int,
        limit: Int,
    ): JSONObject {
        val clip = clipped(raw, limit) ?: return this
        put(field, clip.value)
        val original = maxOf(fullLength, clip.rawLength)
        if (clip.cut || original > clip.shownLength) {
            put("${field}_clipped", true)
            if (original > clip.shownLength) put("${field}_len", original)
        }
        return this
    }

    /**
     * 空白返回 null（不输出空字段）；超长按码点截断并补省略号，同时告诉调用方「被截了」。
     *
     * 截断点不能落在 UTF-16 代理对中间，否则序列化出孤立代理，模型看到乱码。
     */
    private fun clipped(raw: String, limit: Int): Clip? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length <= limit) return Clip(text, cut = false, rawLength = text.length)
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        val value = text.substring(0, end) + "…"
        return Clip(value, cut = true, rawLength = text.length)
    }

    /**
     * 一次截断的结果：显示值、是否被砍、没砍之前有多长，以及**显示出来多少字**。
     *
     * [shownLength] 要单独算：值末尾的 `…` 不是正文，不能被当成「看到了 201 个字」。
     * 它决定要不要补 `<field>_len` —— 只有「还没显示的字 > 0」时才需要告诉模型还差多少。
     */
    private data class Clip(val value: String, val cut: Boolean, val rawLength: Int) {
        val shownLength: Int get() = if (cut) value.length - 1 else value.length
    }
}
