package io.github.mangi.eta.agent.model

/**
 * 旧工具结果的回收：把「已经用完的」工具输出换成一句摘要。
 *
 * 为什么需要它 —— 真机 trace（`run-f64201f5`，deepseek-v4.1-flash）：
 *
 * ```
 * r1: msgs=1031  in=410,751  out=1,708
 * r2: msgs=1034  in=411,546  out=564   cache=410,624
 * r3: msgs=1036  in=411,984  out=189   cache=411,520
 * ```
 *
 * 每轮都要把 **41 万 token** 的上下文推一遍，而输出只有一二百 token。
 * 缓存命中率 99.8%，所以不是缓存问题 —— 是上下文本身太大：1031 条消息，
 * 其中绝大多数是历史工具结果（单条 `res_chars` 常在 700~1,100）。
 *
 * `AgentToolSpill` 只处理「单条结果过大」（超 12,000 字符就落盘），
 * 从不回收「已经不再需要的旧结果」。而绝大多数旧工具结果只对**当时那一轮**有用。
 *
 * 三条约束：
 *
 * 1. **只动末尾之前的结果**。`keepTail` 条之内视作「本轮正在用」，一律不碰 ——
 *    模型刚看到的输出如果下一轮就被抹掉，只会逼它重跑一次。
 * 2. **只替换内容，不删消息**。删消息会让 tool_call 与 tool_result 配不上对，
 *    端点会直接报错；这里保留消息与角色，只把正文换成占位。
 * 3. **必须幂等且单调**：已经瘦身过的消息再跑一次结果不变，否则每轮都在改写历史，
 *    前缀缓存也就白谈了（OpenAI 兼容端点只做逐字节前缀匹配）。
 */
internal object AgentToolResultPruner {

    /** 瘦身后留下的占位；短到几乎不占 token，又足以让模型知道"这里曾经有个结果"。 */
    const val PRUNED_PLACEHOLDER = "[早先的工具结果已省略以节省上下文]"

    /**
     * 少于这个长度的结果不瘦身。
     *
     * 占位本身也要几十个字符，比它还短的结果换掉没有任何收益，反而多一次改动。
     */
    private const val MIN_PRUNABLE_CHARS = 240

    /**
     * @param messages 会话消息（就地修改）
     * @param keepTail 末尾保留多少条消息不做处理
     * @param keepChars 单个结果超过它才考虑瘦身
     * @return 实际瘦身的条数
     */
    fun prune(
        messages: org.json.JSONArray,
        keepTail: Int,
        keepChars: Int = MIN_PRUNABLE_CHARS,
    ): Int {
        val limit = messages.length() - keepTail.coerceAtLeast(0)
        if (limit <= 0) return 0
        var pruned = 0
        for (index in 0 until limit) {
            val message = messages.optJSONObject(index) ?: continue
            if (pruneMessage(message, keepChars)) pruned++
        }
        return pruned
    }

    /**
     * 处理单条消息里的工具结果。返回是否真的改动了。
     *
     * 两种形状都要认：
     * - `{"role":"tool","content":"..."}`（OpenAI chat completions）
     * - `{"role":"user","content":[{"type":"tool_result","content":...}]}`（Anthropic）
     */
    private fun pruneMessage(message: org.json.JSONObject, keepChars: Int): Boolean {
        var changed = false
        val content = message.opt("content")
        if (content is String && content.length > keepChars && !isPruned(content)) {
            message.put("content", PRUNED_PLACEHOLDER)
            changed = true
        }
        val array = content as? org.json.JSONArray ?: return changed
        for (index in 0 until array.length()) {
            val part = array.optJSONObject(index) ?: continue
            val type = part.optString("type")
            if (type != "tool_result" && type != "text") continue
            val inner = part.opt("content")
            when (inner) {
                // Anthropic 的 tool_result.content 可以是字符串，也可以是 block 数组。
                is String -> if (inner.length > keepChars && !isPruned(inner)) {
                    part.put("content", PRUNED_PLACEHOLDER)
                    changed = true
                }
                is org.json.JSONArray -> for (innerIndex in 0 until inner.length()) {
                    val block = inner.optJSONObject(innerIndex) ?: continue
                    val text = block.optString("text")
                    if (text.length > keepChars && !isPruned(text)) {
                        block.put("text", PRUNED_PLACEHOLDER)
                        changed = true
                    }
                }
            }
            // block 自身的 text 字段（type=text 的形状）
            val text = part.optString("text")
            if (type == "text" && text.length > keepChars && !isPruned(text) && inner == null) {
                part.put("text", PRUNED_PLACEHOLDER)
                changed = true
            }
        }
        return changed
    }

    private fun isPruned(text: String): Boolean = text == PRUNED_PLACEHOLDER
}
