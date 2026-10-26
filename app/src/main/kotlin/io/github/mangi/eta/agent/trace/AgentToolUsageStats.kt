package io.github.mangi.eta.agent.trace

/**
 * 工具使用统计：把最近几次运行的追踪记录按工具名聚合。
 *
 * 回答的是「我的 Agent 到底在用什么工具、哪个工具最慢、哪个经常失败」——
 * 这些数据追踪文件里本来就有（`name` / `ok` / `elapsedMs`），
 * 但此前只在单次运行里可见，跨运行没有汇总。
 *
 * 只读投影：不做任何写入，也不参与决策。
 */
internal object AgentToolUsageStats {

    data class ToolUsage(
        val name: String,
        val calls: Int,
        val failures: Int,
        val totalMs: Long,
        val maxMs: Long,
    ) {
        val avgMs: Long get() = if (calls <= 0) 0 else totalMs / calls
        val failureRate: Float get() = if (calls <= 0) 0f else failures.toFloat() / calls
    }

    data class Stats(
        val runs: Int,
        val calls: Int,
        val failures: Int,
        val tools: List<ToolUsage>,
    ) {
        val hasData: Boolean get() = calls > 0
        val failureRate: Float get() = if (calls <= 0) 0f else failures.toFloat() / calls
        val avgMs: Long get() = if (calls <= 0) 0L else tools.sumOf { it.totalMs } / calls
    }

    fun aggregate(traces: List<AgentRunTraceStore.Trace>, topTools: Int = 8): Stats {
        val byName = LinkedHashMap<String, MutableUsage>()
        var calls = 0
        var failures = 0
        traces.forEach { trace ->
            trace.entries.forEach { entry ->
                if (entry.kind != AgentRunTraceStore.KIND_TOOL) return@forEach
                val name = entry.name?.takeIf(String::isNotBlank) ?: "(未命名工具)"
                calls += 1
                if (entry.ok == false) failures += 1
                val usage = byName.getOrPut(name) { MutableUsage() }
                usage.calls += 1
                if (entry.ok == false) usage.failures += 1
                val elapsed = (entry.elapsedMs ?: 0L).coerceAtLeast(0L)
                usage.totalMs += elapsed
                usage.maxMs = maxOf(usage.maxMs, elapsed)
            }
        }
        val tools = byName.map { (name, usage) ->
            ToolUsage(
                name = name,
                calls = usage.calls,
                failures = usage.failures,
                totalMs = usage.totalMs,
                maxMs = usage.maxMs,
            )
        }.sortedWith(
            compareByDescending<ToolUsage> { it.calls }
                .thenByDescending { it.totalMs }
                .thenBy { it.name },
        ).take(topTools.coerceAtLeast(1))
        return Stats(
            runs = traces.size,
            calls = calls,
            failures = failures,
            tools = tools,
        )
    }

    private class MutableUsage {
        var calls: Int = 0
        var failures: Int = 0
        var totalMs: Long = 0
        var maxMs: Long = 0
    }
}
