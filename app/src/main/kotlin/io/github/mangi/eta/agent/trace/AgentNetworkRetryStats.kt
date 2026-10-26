package io.github.mangi.eta.agent.trace

/**
 * 模型网络重试统计：从运行追踪里聚合 `KIND_RETRY` 记录。
 *
 * 回答的是「最近这些 run 里模型请求重试了多少次、主要是什么原因、重试后有没有恢复」。
 * 只读投影，不参与决策，也不额外写盘；数据本来就在追踪文件里。
 */
internal object AgentNetworkRetryStats {

    data class Reason(
        val code: String,
        val count: Int,
        val totalDelayMs: Long,
    ) {
        val avgDelayMs: Long get() = if (count <= 0) 0 else totalDelayMs / count
    }

    data class Stats(
        val runs: Int,
        val retryRuns: Int,
        val retries: Int,
        val recoveredRuns: Int,
        val failedRetryRuns: Int,
        val unknownRuns: Int,
        val totalDelayMs: Long,
        val gatewayRetries: Int,
        val reasons: List<Reason>,
    ) {
        val hasData: Boolean get() = retries > 0
        val avgDelayMs: Long get() = if (retries <= 0) 0 else totalDelayMs / retries

        /** 有结论的重试运行里，最终成功的比例；未知结果（取消/中断）不计入分母。 */
        val recoveryRate: Float
            get() {
                val decided = recoveredRuns + failedRetryRuns
                return if (decided <= 0) 0f else recoveredRuns.toFloat() / decided
            }
    }

    private val gatewayCodes = setOf(
        "HTTP_520", "HTTP_521", "HTTP_522", "HTTP_523", "HTTP_524", "HTTP_530",
    )

    fun aggregate(traces: List<AgentRunTraceStore.Trace>): Stats {
        var retryRuns = 0
        var retries = 0
        var recoveredRuns = 0
        var failedRetryRuns = 0
        var unknownRuns = 0
        var totalDelayMs = 0L
        var gatewayRetries = 0
        val byCode = LinkedHashMap<String, MutableReason>()

        traces.forEach { trace ->
            val retryEntries = trace.entries.filter { it.kind == AgentRunTraceStore.KIND_RETRY }
            if (retryEntries.isEmpty()) return@forEach
            retryRuns += 1
            when (trace.ok) {
                true -> recoveredRuns += 1
                false -> failedRetryRuns += 1
                null -> unknownRuns += 1
            }
            retryEntries.forEach { entry ->
                retries += 1
                val delay = (entry.delayMs ?: 0L).coerceAtLeast(0L)
                totalDelayMs += delay
                val code = entry.code?.takeIf(String::isNotBlank) ?: "(未知原因)"
                if (code in gatewayCodes) gatewayRetries += 1
                val reason = byCode.getOrPut(code) { MutableReason() }
                reason.count += 1
                reason.totalDelayMs += delay
            }
        }

        val reasons = byCode.map { (code, value) ->
            Reason(code = code, count = value.count, totalDelayMs = value.totalDelayMs)
        }.sortedWith(
            compareByDescending<Reason> { it.count }
                .thenByDescending { it.totalDelayMs }
                .thenBy { it.code },
        )

        return Stats(
            runs = traces.size,
            retryRuns = retryRuns,
            retries = retries,
            recoveredRuns = recoveredRuns,
            failedRetryRuns = failedRetryRuns,
            unknownRuns = unknownRuns,
            totalDelayMs = totalDelayMs,
            gatewayRetries = gatewayRetries,
            reasons = reasons,
        )
    }

    private class MutableReason {
        var count: Int = 0
        var totalDelayMs: Long = 0
    }
}
