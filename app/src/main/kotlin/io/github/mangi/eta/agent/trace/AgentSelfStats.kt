package io.github.mangi.eta.agent.trace

/**
 * Agent 对**自己**的度量：把运行追踪收敛成「我最近做得怎么样」。
 *
 * 与同目录另外两个投影的分工：
 * - [AgentToolUsageStats] 回答「哪个工具慢/常失败」，是**展示**用的聚合；
 * - [AgentNetworkRetryStats] 回答「网络重试多不多、有没有恢复」；
 * - 本文件回答「**我自己的行为哪里可以改**」，并且只保留**能落到具体动作**的指标。
 *
 * 为什么必须单独一份：上面两份是按工具/按原因的扁平统计，
 * 它们看不出「edit_file 的失败里有 15 次是 READ_REQUIRED、15 次是 FILE_CHANGED」——
 * 而这两个指向完全不同的改法（先读文件 vs 重新读再改）。把失败**按错误码分组**
 * 才可能给出可行动的建议，而不是「你这个工具失败率偏高，注意一下」。
 *
 * 纯函数、无 Android 依赖、无 IO：输入是已经读好的 trace 列表，因此可以在普通 JVM 单测里
 * 把每一种阈值边界都喂一遍。
 */
internal object AgentSelfStats {

    /** 一个工具在窗口内的表现。 */
    internal data class ToolBehavior(
        val name: String,
        val calls: Int,
        val failures: Int,
        /** 失败码 → 次数；只统计能从结果 JSON 里解析出 code 的失败。 */
        val failureCodes: Map<String, Int>,
    ) {
        val failureRate: Float get() = if (calls <= 0) 0f else failures.toFloat() / calls
    }

    /**
     * 一次「空转」的证据。
     *
     * 空转的定义是**可观测**的：没有任何工具调用、却消耗了多轮模型往返。
     * 不按「回答长不长」判断 —— 那会把正常的长解释也算成空转。
     */
    internal data class IdleRun(
        val runId: String,
        val rounds: Int,
        val toolCalls: Int,
    )

    internal data class Stats(
        val runs: Int,
        /** 至少调用过一次工具的 run 数。判定失败率时分母用这个，不含纯对话。 */
        val runsWithTools: Int,
        val toolCalls: Int,
        val toolFailures: Int,
        val retries: Int,
        val retryRuns: Int,
        val compactions: Int,
        val compactionRuns: Int,
        val failedRuns: Int,
        val idleRuns: List<IdleRun>,
        val tools: List<ToolBehavior>,
        /** 头部说「有失败」但明细没读成的运行数；>0 时 per-tool 归因是不完整的。 */
        val unreadFailureRuns: Int = 0,
    ) {
        val hasData: Boolean get() = toolCalls > 0 || retries > 0 || runs > 0

        val toolFailureRate: Float
            get() = if (toolCalls <= 0) 0f else toolFailures.toFloat() / toolCalls
    }

    /**
     * 从结果文本里抠出错误码。
     *
     * 工具失败结果是 JSON（`{"ok":false,"code":"READ_REQUIRED",...}`），
     * 但**不是所有失败都有 code**（有的只有 message、有的干脆是空串）。
     * 没有 code 时返回 null 并单独计为「未分类」，绝不编一个出来。
     */
    internal fun failureCodeOf(result: String?): String? {
        val text = result?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 只认紧跟在 "code" 之后的字符串字面量，避免把 message 里的词当成 code。
        val key = "\"code\""
        val at = text.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        while (i < text.length && (text[i] == ':' || text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] != '"') return null
        i++
        val end = text.indexOf('"', i)
        if (end <= i) return null
        return text.substring(i, end).takeIf { it.isNotBlank() }
    }

    fun aggregate(
        traces: List<AgentRunTraceStore.Trace>,
        summaries: List<AgentRunTraceStore.Summary> = emptyList(),
        topTools: Int = 6,
    ): Stats {
        var runsWithTools = 0
        var toolCalls = 0
        var toolFailures = 0
        var retries = 0
        var retryRuns = 0
        var compactions = 0
        var compactionRuns = 0
        var failedRuns = 0
        var unreadFailureRuns = 0
        val idle = mutableListOf<IdleRun>()
        val byTool = LinkedHashMap<String, MutableBehavior>()

        // 总数优先用头部 meta（不读全文就有）；没有 summary 时（单测、全量读场景）
        // 再从 traces 现算。两边都有时以 summaries 为准，避免只读了子集就低估分母。
        if (summaries.isNotEmpty()) {
            summaries.forEach { summary ->
                if (summary.toolCalls > 0) runsWithTools += 1
                toolCalls += summary.toolCalls
                toolFailures += summary.toolFailures
                retries += summary.retries
                if (summary.retries > 0) retryRuns += 1
                if (summary.ok == false) failedRuns += 1
            }
        }

        val detailedRunIds = traces.mapTo(HashSet()) { it.runId }
        traces.forEach { trace ->
            val calls = trace.entries.count { it.kind == AgentRunTraceStore.KIND_TOOL }
            if (summaries.isEmpty()) {
                if (calls > 0) runsWithTools += 1
                if (trace.ok == false) failedRuns += 1
                val traceRetries = trace.entries.count { it.kind == AgentRunTraceStore.KIND_RETRY }
                retries += traceRetries
                if (traceRetries > 0) retryRuns += 1
            }
            val traceCompactions = trace.entries.count { it.kind == AgentRunTraceStore.KIND_COMPACT }
            compactions += traceCompactions
            if (traceCompactions > 0) compactionRuns += 1

            // 空转：多轮往返、却一次工具都没调。只看「≥2 轮」的，
            // 单轮纯问答是正常使用，不是缺陷。
            val rounds = trace.entries.count { it.kind == AgentRunTraceStore.KIND_ROUND }
            if (calls == 0 && rounds >= 2) {
                idle += IdleRun(runId = trace.runId, rounds = rounds, toolCalls = 0)
            }

            trace.entries.forEach { entry ->
                if (entry.kind != AgentRunTraceStore.KIND_TOOL) return@forEach
                val name = entry.name?.takeIf(String::isNotBlank) ?: "(未命名工具)"
                if (summaries.isEmpty()) toolCalls += 1
                val behavior = byTool.getOrPut(name) { MutableBehavior() }
                behavior.calls += 1
                if (entry.ok == false) {
                    if (summaries.isEmpty()) toolFailures += 1
                    behavior.failures += 1
                    val code = failureCodeOf(entry.result)
                    if (code != null) behavior.codes[code] = (behavior.codes[code] ?: 0) + 1
                }
            }
        }

        // 头部有失败、但本次没读成全文的 run：它是真存在的失败，不能因为「没展开」就从分母里消失。
        // 这些失败没有失败码（要读全文才知道具体是什么），因此单独计数，由调用方写进 notes。
        if (summaries.isNotEmpty()) {
            unreadFailureRuns = summaries.count { it.toolFailures > 0 && it.runId !in detailedRunIds }
        }

        val tools = byTool.map { (name, b) ->
            ToolBehavior(
                name = name,
                calls = b.calls,
                failures = b.failures,
                failureCodes = b.codes.toList()
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                    .toMap(),
            )
        }.sortedWith(
            compareByDescending<ToolBehavior> { it.failures }
                .thenByDescending { it.calls }
                .thenBy { it.name },
        ).take(topTools.coerceAtLeast(1))

        return Stats(
            runs = if (summaries.isNotEmpty()) summaries.size else traces.size,
            runsWithTools = runsWithTools,
            toolCalls = toolCalls,
            toolFailures = toolFailures,
            retries = retries,
            retryRuns = retryRuns,
            compactions = compactions,
            compactionRuns = compactionRuns,
            failedRuns = failedRuns,
            idleRuns = idle.sortedWith(compareByDescending<IdleRun> { it.rounds }.thenBy { it.runId }),
            tools = tools,
            unreadFailureRuns = unreadFailureRuns,
        )
    }

    /**
     * 在预算内挑出要展开明细的运行。
     *
     * 为什么要单独抽出来：这是「看哪些」的唯一口径，而它很容易写错 ——
     * 按体积降序会永远盯着几份超长会话，按时间才能反映当前状态。抽成纯函数才能测。
     *
     * @param summaries 窗口内的全部运行（按 [AgentRunTraceStore.Summary.startedAt] 取新）。
     * @param maxBytes 明细总字节预算。
     * @param maxTraces 明细份数上限。
     * @return 应展开的运行，**按时间从新到旧**。
     */
    fun pickForDetail(
        summaries: List<AgentRunTraceStore.Summary>,
        maxBytes: Long,
        maxTraces: Int,
    ): List<AgentRunTraceStore.Summary> {
        if (maxBytes <= 0 || maxTraces <= 0) return emptyList()
        val candidates = summaries
            // 干净的运行不用展开：没有失败码可分组，也不可能是空转（有工具调用）。
            .filter { it.toolFailures > 0 || it.toolCalls == 0 }
            .sortedByDescending { it.startedAt }
        var budget = maxBytes
        val picked = mutableListOf<AgentRunTraceStore.Summary>()
        for (summary in candidates) {
            if (summary.sizeBytes > budget) continue
            picked += summary
            budget -= summary.sizeBytes
            if (picked.size >= maxTraces) break
        }
        return picked
    }

    private class MutableBehavior {
        var calls: Int = 0
        var failures: Int = 0
        val codes = LinkedHashMap<String, Int>()
    }
}
