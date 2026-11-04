package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.trace.AgentSelfStats

/**
 * 把「我最近的工具使用表现」压成几行，注入系统提示。
 *
 * 为什么需要它：`AgentSelfStats` 只是**度量**，要被真正看见才有用。
 * 用户问「Eta 做得怎么样」时可以主动调 `eta_self_audit(scope="agent")`，
 * 但更多时候模型压根不会想到去查 —— 于是同一个错误码会一直重复。
 * 把最高频的几条**推**到系统提示里，「知道如何改进」才不依赖模型自觉。
 *
 * 三条硬约束：
 *
 * 1. **纯函数、无 IO**：输入已聚合好的 [AgentSelfStats.Stats]，输出一段文本。
 *    这样阈值边界、字数上限、空数据降级都能在普通 JVM 单测里喂一遍。
 *
 * 2. **只放「有专属改法」的条目**：没有专属改法的失败（未知码）只报次数、不给建议。
 *    给一句「注意一下」比不给更糟 —— 它占掉预算，还训练模型忽略这段。
 *
 * 3. **必须在会话内稳定**：这段文本会进系统提示，系统提示是前缀缓存的对象。
 *    每次请求都变（比如带上实时计数）会让缓存整段失效，代价远大于收益。
 *    稳定性由调用方（[ConversationMemoryPins]）保证：本文件只负责「给定输入就给定输出」。
 */
internal object AgentSelfBrief {

    /**
     * 注入正文的字数上限。
     *
     * 实测真实最坏情况（3 个工具 × 2 个错码 × 最长 fix 文本）约 620 字符，
     * 所以 700 对常规数据是「刚好不用截断」——而不是拍脑袋的松紧。
     *
     * 截断分支因此基本是**防御性的**：它保护的是「fix 文本以后变长」或
     * 「工具名异常长」这类未到来的情况，而不是每天都会走到。
     * 这也意味着不能拿真实数据去测截断（永远触发不了），
     * 得用人为超长的输入才能覆盖到那条路径。
     */
    const val MAX_CHARS = 700

    /** 最多列几个「问题最多的工具」。再多就成流水账了。 */
    private const val MAX_TOOLS = 3

    /** 单个工具的失败码最多列几个。 */
    private const val MAX_CODES_PER_TOOL = 2

    /** 少于这么多次调用不谈失败率：分母太小时百分比没有意义（1/3 = 33% 会吓人）。 */
    private const val MIN_CALLS_FOR_RATE = 8

    /**
     * 渲染注入块。
     *
     * 返回空串表示**不值得注入**（没数据、全部健康），调用方据此完全不输出该段，
     * 而不是输出「一切正常」——后者每轮都占预算，却从不带来新信息。
     */
    fun render(stats: AgentSelfStats.Stats?): String {
        if (stats == null) return ""
        if (!stats.hasData) return ""
        if (stats.toolCalls <= 0) return ""

        val problemTools = stats.tools
            .filter { it.failures > 0 }
            .sortedWith(
                compareByDescending<AgentSelfStats.ToolBehavior> { it.failures }
                    .thenByDescending { it.calls }
                    .thenBy { it.name },
            )
            .take(MAX_TOOLS)

        // 一个失败都没有：不值得占系统提示的位置。
        if (problemTools.isEmpty()) return ""

        val lines = mutableListOf<String>()
        lines += "以下是你最近 ${stats.runs} 次运行（${stats.runsWithTools} 次用过工具）里的工具失败统计。" +
            "总数：调用 ${stats.toolCalls} 次、失败 ${stats.toolFailures} 次" +
            "（${percent(stats.toolFailureRate)}）。"

        problemTools.forEach { tool ->
            val rateNote = if (tool.calls >= MIN_CALLS_FOR_RATE) {
                "，失败率 ${percent(tool.failureRate)}"
            } else {
                "" // 分母太小，不报百分比，避免把偶发说成系统性问题
            }
            lines += "- ${tool.name}：失败 ${tool.failures}/${tool.calls} 次$rateNote"
            tool.failureCodes
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_CODES_PER_TOOL)
                .forEach { (code, count) ->
                    val fix = AgentFailureHints.fixFor(tool.name, code)
                    if (AgentFailureHints.hasSpecificFix(code)) {
                        lines += "  · $code × $count → $fix"
                    } else {
                        // 没有专属改法就只报事实，不编建议。
                        lines += "  · $code × $count（无固定改法，按错误信息判断）"
                    }
                }
        }

        if (stats.unreadFailureRuns > 0) {
            lines += "另有 ${stats.unreadFailureRuns} 次运行报了失败但明细没读到，上面的归因不完整。"
        }

        val body = lines.joinToString("\n")
        return if (body.length <= MAX_CHARS) body else truncate(body)
    }

    /**
     * 超预算时按行截断。
     *
     * 不直接 `take(n)`：那会把最后一行切成半个错误码，读起来像乱码。
     * 宁可少一条，也不留残句。
     *
     * 两条边界必须处理，否则「限长」是假的：
     * 1. 第一行就放不下 —— 不能返回空串，那不满足声明的不变式；
     * 2. 连提示行都放不下 —— 必须硬切，因为「声明上限」比「句子完整」优先。
     */
    private fun truncate(body: String): String {
        val head = TRUNCATE_NOTICE
        // 容量先紧着提示行，剩下的再给正文。
        val room = MAX_CHARS - head.length - 1
        if (room <= 0) return TRUNCATE_NOTICE.take(MAX_CHARS)

        val kept = StringBuilder()
        for (line in body.lineSequence()) {
            val nextLen = if (kept.isEmpty()) line.length else kept.length + 1 + line.length
            if (nextLen > room) break
            if (kept.isNotEmpty()) kept.append('\n')
            kept.append(line)
        }
        if (kept.isEmpty()) {
            // 第一行就超了：这种情况通常是某个码的描述异常长。
            // 宁可硬切一条，也不能返回空 —— 空串会被调用方当成「没什么可说」。
            return (body.take(room) + "\n" + head).take(MAX_CHARS)
        }
        return "$kept\n$head"
    }

    private const val TRUNCATE_NOTICE = "（以下为节选，完整明细可调用 eta_self_audit(scope=\"agent\")）"

    private fun percent(rate: Float): String {
        val value = rate * 100f
        // 0.05% 这类不要显示成 0%：那会让「有失败」和「没失败」看起来一样。
        val rounded = kotlin.math.round(value * 10f) / 10f
        return if (rounded > 0f && rounded < 1f) "<1%" else "${rounded.toInt()}%"
    }
}
