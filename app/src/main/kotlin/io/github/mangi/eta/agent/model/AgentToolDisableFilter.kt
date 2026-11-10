package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * 按用户逐项开关裁剪模型可见的工具。
 *
 * 为什么单独一层而不是改 [AgentToolCatalog.build] 的布尔参数：
 * `build` 的开关是**按能力分类**的（终端、浏览器、设备直连……），一个分类里的工具
 * 是「要么全给要么全不给」；用户真正的需求常常更细 —— 比如保留 `read_file` 但禁掉
 * `terminal`，或保留浏览器搜索但禁掉 `browser_click`。分类布尔表达不了这种组合，
 * 硬塞进去只会让 `build` 的参数表继续膨胀。
 *
 * 这里只做声明层裁剪：被禁用的工具不再出现在 tools 里，模型看不到也就不可能调用。
 * 执行层的校验不受影响（工具被禁用比「允许」更严，不需要第二道闸）。
 */
internal object AgentToolDisableFilter {

    /** JSON schema 里工具名的字段。 */
    private const val NAME_FIELD = "name"

    /**
     * 解析 `agent_disabled_tools` 的原始值。
     *
     * 容错到「乱写也不会误禁用」：空白与重复项被归一化；空串与 null 得到空集合。
     * 工具名本身区分大小写，所以不改写大小写，只做 trim。
     */
    fun parse(raw: String?): Set<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()
        return text.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * @param tools 已构建好的工具数组（顺序保持不变）
     * @param disabled 被用户禁用的工具名集合；空集合表示不裁剪
     * @return 过滤后的新数组。没有命中时**返回原数组**，避免无谓的复制。
     */
    fun apply(tools: JSONArray, disabled: Set<String>): JSONArray {
        if (disabled.isEmpty() || tools.length() == 0) return tools
        val kept = JSONArray()
        var dropped = false
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optString(NAME_FIELD)
            if (name.isNotEmpty() && name in disabled) {
                dropped = true
                continue
            }
            kept.put(tool)
        }
        // 一个都没命中就别换对象：调用方可能持有同一个 JSONArray 做别的判断。
        return if (dropped) kept else tools
    }
}
