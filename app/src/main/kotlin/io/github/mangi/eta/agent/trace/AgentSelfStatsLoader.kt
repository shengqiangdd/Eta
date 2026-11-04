package io.github.mangi.eta.agent.trace

/**
 * 从运行追踪里取「最近表现」的唯一入口。
 *
 * 为什么单独一份而不是内联在调用方：自审（`eta_self_audit`）和系统提示注入
 * （[io.github.mangi.eta.agent.memory.AgentSelfBrief]）**必须用同一套取数口径**，
 * 否则会出现「自审说 edit_file 失败率 17%，系统提示却说一切正常」这种自相矛盾。
 *
 * 只读。所有异常都在内部吞掉并返回 null —— 取不到自身表现不是故障，
 * 静默降级（不注入该段）比让整轮对话失败合理得多。
 */
internal object AgentSelfStatsLoader {

    /** 最多看多少份 header。再多主要是在读陈年数据，对「最近表现」没帮助。 */
    private const val MAX_TRACES = 40

    /** 明细展开的字节预算：不设上限时读全文会占掉几十 MB。 */
    private const val MAX_DETAIL_BYTES = 2L * 1024 * 1024

    /** 最多展开几份明细。失败码只能从明细里拿到，头部没有。 */
    private const val MAX_DETAIL_TRACES = 12

    const val DEFAULT_HOURS = 48
    const val MAX_HOURS = 720

    /**
     * 聚合窗口内的表现。
     *
     * @param hours 回溯小时数，会被夹到 `[1, MAX_HOURS]`
     * @param onError 读取失败时的回调（用于记录日志）；传 null 表示完全静默
     * @return 没有数据或读取失败时返回 null
     */
    fun load(hours: Int = DEFAULT_HOURS, onError: ((Throwable) -> Unit)? = null): AgentSelfStats.Stats? =
        runCatching {
            val summaries = AgentRunTraceStore.list(limit = MAX_TRACES)
            if (summaries.isEmpty()) return@runCatching null
            val since = System.currentTimeMillis() - hours.coerceIn(1, MAX_HOURS) * 3_600_000L
            val inWindow = summaries.filter { it.startedAt >= since }
            // 展开哪些、展多少由纯函数决定，可单测。
            val picked = AgentSelfStats.pickForDetail(inWindow, MAX_DETAIL_BYTES, MAX_DETAIL_TRACES)
            val loaded = picked.mapNotNull { summary ->
                runCatching { AgentRunTraceStore.read(summary.runId) }.getOrNull()
            }
            // 总体指标仍用全部头部数据，所以「没展开」不会让分母变小。
            AgentSelfStats.aggregate(traces = loaded, summaries = inWindow)
        }.getOrElse { throwable ->
            onError?.invoke(throwable)
            null
        }
}
