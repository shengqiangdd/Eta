package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * OpenAI 风格的工具声明把名字放在 `function.name`，而不是顶层 `name`。
     *
     * 必须读嵌套字段：读顶层会在**不报错**的前提下拿到空串，过滤器和测试全绿，
     * 功能却从未生效过（已经踩过一次）。
     */
    private const val FUNCTION_FIELD = "function"
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
            val raw = tools.opt(index)
            if (raw !is JSONObject) {
                // 非对象项不属于本次裁剪范围：原样保留，而不是静默丢掉。
                // 早先用 `optJSONObject(index) ?: continue` 把这类项直接删了 ——
                // 只要同时禁用了任意一个工具，它们就会被「顺手」清掉。
                kept.put(raw)
                continue
            }
            val name = raw.optJSONObject(FUNCTION_FIELD)?.optString(NAME_FIELD).orEmpty()
            if (name.isNotEmpty() && name in disabled) {
                dropped = true
                continue
            }
            kept.put(raw)
        }
        // 一个都没命中就别换对象：调用方可能持有同一个 JSONArray 做别的判断。
        return if (dropped) kept else tools
    }

    /**
     * 从工具目录里取出真实的工具名。
     *
     * 给测试与诊断用：名字藏在 `function.name`，直接对数组取 `name` 会全是空串，
     * 让「名字对不对得上」这类断言失去意义。
     */
    fun namesOf(tools: JSONArray): List<String> =
        (0 until tools.length()).mapNotNull { index ->
            tools.optJSONObject(index)
                ?.optJSONObject(FUNCTION_FIELD)
                ?.optString(NAME_FIELD)
                ?.takeIf { it.isNotEmpty() }
        }
}
