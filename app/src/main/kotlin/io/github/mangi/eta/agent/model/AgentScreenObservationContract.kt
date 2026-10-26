package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 屏幕观察工具在模型 schema、执行器与运行轨迹之间共享的默认合同。 */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120

    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    data class Options(
        val includeScreenshot: Boolean,
        val includeUiTree: Boolean,
        val maxNodes: Int,
        /** 仅虚拟屏截图用：裁剪并放大该区域（归一化 0–999）；null 表示整屏。 */
        val region: Region? = null,
    )

    fun resolve(arguments: JSONObject): Options = Options(
        includeScreenshot = arguments.optBoolean(
            "include_screenshot",
            DEFAULT_INCLUDE_SCREENSHOT,
        ),
        includeUiTree = arguments.optBoolean(
            "include_ui_tree",
            DEFAULT_INCLUDE_UI_TREE,
        ),
        maxNodes = arguments.optInt("max_nodes", DEFAULT_MAX_NODES),
        region = arguments.optString("region")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::parseRegion),
    )

    private fun parseRegion(raw: String): Region? {
        val parts = raw.split(',').map { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        val x = parts[0] ?: return null
        val y = parts[1] ?: return null
        val width = parts[2] ?: return null
        val height = parts[3] ?: return null
        if (x !in 0..999 || y !in 0..999 || width !in 1..1000 || height !in 1..1000) return null
        if (x + width > 1000 || y + height > 1000) return null
        return Region(x, y, width, height)
    }
}
