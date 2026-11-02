package io.github.mangi.eta.agent.trace

/**
 * 轨迹投影：把一次运行的扁平 JSONL 记录整理成「轮次 → 时间片」。
 *
 * 运行详情原来是一条条平铺的卡片，能看到「发生了什么」，但看不出
 * 「每一轮花了多久、时间花在哪一步、哪一轮最慢」。这里只做投影，
 * 不读文件、不改记录，输出可直接用于时间总览与按轮筛选。
 *
 * 时间口径：
 * - 工具记录写入的是完成时刻（`at`），耗时在 `elapsedMs`，所以区间是 `at - elapsedMs .. at`；
 * - 其余记录写入的是发生时刻，区间取到下一条记录的 `at`；
 * - 时间片最小宽度 1ms，避免零宽段在总览里消失。
 */
internal object AgentRunTrajectory {

    /** 时间条上的一段：一次可观测动作。 */
    data class Segment(
        val kind: String,
        val label: String,
        val startMs: Long,
        val endMs: Long,
        val failed: Boolean = false,
        val turnIndex: Int = 0,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    }

    data class EntryRef(
        val index: Int,
        val entry: AgentRunTraceStore.Entry,
        val offsetMs: Long,
        val durationMs: Long,
    )

    data class Turn(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val messageCount: Int?,
        val entries: List<EntryRef>,
        val segments: List<Segment>,
        val toolCalls: Int,
        val toolFailures: Int,
        val retries: Int,
        val tokens: Int,
        /** 本轮输出 token（只有 output 计入速度，输入不算）。 */
        val outputTokens: Int = 0,
        /** 本轮模型生成耗时；口径见 [AgentRunTraceStore.Trace.modelMs]。 */
        val modelMs: Long = 0,
        /** 本轮纯解码耗时：首字 → 生成结束（口径见 [AgentRunTraceStore.speedOf]）。 */
        val decodeMs: Long = 0,
        /** 与 [decodeMs] 配对的输出 token。 */
        val decodeTokens: Int = 0,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

        /** 本轮输出速度（纯解码，不含 TTFT）；数据不足时为 null，界面显示「—」。 */
        val tokensPerSecond: Double?
            get() = if (decodeTokens > 0 && decodeMs > 0) {
                decodeTokens * 1000.0 / decodeMs
            } else {
                null
            }
    }

    data class Trajectory(
        val turns: List<Turn>,
        val segments: List<Segment>,
        val totalMs: Long,
    ) {
        val hasTurns: Boolean get() = turns.isNotEmpty()
    }

    fun build(trace: AgentRunTraceStore.Trace): Trajectory {
        val entries = trace.entries
        if (entries.isEmpty()) return Trajectory(emptyList(), emptyList(), 0)
        val origin = trace.startedAt.takeIf { it > 0 } ?: entries.first().at
        val endOfRun = trace.finishedAt ?: entries.last().at

        // 每条记录的时间区间（绝对时间戳）。
        val spans = entries.mapIndexed { index, entry ->
            val spanStart: Long
            val spanEnd: Long
            if (entry.kind == AgentRunTraceStore.KIND_TOOL && entry.elapsedMs != null) {
                spanEnd = entry.at
                spanStart = entry.at - entry.elapsedMs.coerceAtLeast(0)
            } else {
                spanStart = entry.at
                val nextAt = entries.getOrNull(index + 1)?.at ?: endOfRun
                spanEnd = maxOf(nextAt, entry.at)
            }
            maxOf(spanStart, origin) to maxOf(spanEnd, spanStart + 1)
        }

        // 以 round 记录切轮；没有 round 记录时整段算一轮（保持旧数据可读）。
        val turnStarts = entries.indices.filter { entries[it].kind == AgentRunTraceStore.KIND_ROUND }
        // 第一轮从索引 0 开始：round 之前的 start / 系统提示属于这一轮的准备阶段，
        // 不单独算一轮（否则「第 1 轮」会变成「准备 + 第 1 轮」两段）。
        val bounds = if (turnStarts.isEmpty()) listOf(0) else turnStarts

        val turns = bounds.mapIndexed { turnIndex, beginIndex ->
            val endIndex = bounds.getOrNull(turnIndex + 1) ?: entries.size
            val slice = (beginIndex until endIndex).toList()
            val refs = slice.map { index ->
                val (start, end) = spans[index]
                EntryRef(
                    index = index,
                    entry = entries[index],
                    offsetMs = (start - origin).coerceAtLeast(0),
                    durationMs = (end - start).coerceAtLeast(0),
                )
            }
            val startMs = refs.minOfOrNull { it.offsetMs } ?: 0
            val endMs = refs.maxOfOrNull { it.offsetMs + it.durationMs } ?: startMs
            // 总览与单轮共用同一套口径：解码窗口按请求配对，工具与首字等待都不进分母。
            val speed = AgentRunTraceStore.speedOf(entries, slice)
            Turn(
                index = turnIndex,
                startMs = startMs,
                endMs = maxOf(endMs, startMs + 1),
                messageCount = entries.subList(beginIndex, endIndex)
                    .firstOrNull { it.kind == AgentRunTraceStore.KIND_ROUND }
                    ?.messageCount,
                entries = refs,
                segments = refs.map { ref ->
                    Segment(
                        kind = ref.entry.kind,
                        label = labelOf(ref.entry),
                        startMs = ref.offsetMs,
                        endMs = ref.offsetMs + ref.durationMs,
                        failed = ref.entry.ok == false,
                        turnIndex = turnIndex,
                    )
                },
                toolCalls = refs.count { it.entry.kind == AgentRunTraceStore.KIND_TOOL },
                toolFailures = refs.count {
                    it.entry.kind == AgentRunTraceStore.KIND_TOOL && it.entry.ok == false
                },
                retries = refs.count { it.entry.kind == AgentRunTraceStore.KIND_RETRY },
                tokens = refs.sumOf { ref ->
                    if (ref.entry.kind == AgentRunTraceStore.KIND_USAGE) {
                        // 与 Trace.totalTokens 同一口径：优先服务商总输入，旧记录退回输入。
                        (ref.entry.promptTokens ?: ref.entry.inputTokens ?: 0) +
                            (ref.entry.outputTokens ?: 0)
                    } else {
                        0
                    }
                },
                outputTokens = refs.sumOf { ref ->
                    if (ref.entry.kind == AgentRunTraceStore.KIND_USAGE) {
                        ref.entry.outputTokens ?: 0
                    } else {
                        0
                    }
                },
                modelMs = modelMsOf(entries, slice, trace.finishedAt ?: entries.last().at),
                decodeMs = speed.decodeMs,
                decodeTokens = speed.tokens,
            )
        }

        val totalMs = maxOf(turns.maxOfOrNull { it.endMs } ?: 0, 1)
        return Trajectory(
            turns = turns,
            segments = turns.flatMap { it.segments },
            totalMs = totalMs,
        )
    }

    /**
     * 一组记录里"模型在生成"的耗时合计。
     *
     * 口径与 [AgentRunTraceStore.Trace.modelMs] 完全一致：每个请求到
     * 「本轮产物结束」为止；工具/下一次请求/运行结束三者的最早者作为边界。
     * 用同一套规则算总览与单轮，避免两个地方给出互相矛盾的速度。
     */
    private fun modelMsOf(
        entries: List<AgentRunTraceStore.Entry>,
        indices: List<Int>,
        endOfRun: Long,
    ): Long {
        return AgentRunTraceStore.modelMsOf(entries, indices, endOfRun)
    }

    /** 时间条与卡片共用的一套标签，避免两处口径漂移。 */
    fun labelOf(entry: AgentRunTraceStore.Entry): String = when (entry.kind) {
        AgentRunTraceStore.KIND_ROUND -> "第 ${entry.round ?: 0} 轮"
        AgentRunTraceStore.KIND_START -> "开始"
        AgentRunTraceStore.KIND_REQUEST -> "模型请求"
        AgentRunTraceStore.KIND_RESPONSE -> "响应"
        AgentRunTraceStore.KIND_THINKING -> "思考"
        AgentRunTraceStore.KIND_TEXT -> "正文"
        AgentRunTraceStore.KIND_USAGE -> "用量"
        AgentRunTraceStore.KIND_RETRY -> "重试"
        AgentRunTraceStore.KIND_COMPACT -> "压缩"
        AgentRunTraceStore.KIND_TOOL -> entry.name ?: "工具"
        AgentRunTraceStore.KIND_DONE -> "结束"
        else -> entry.kind.ifBlank { "记录" }
    }

    /** 选某一轮时只取该轮的时间片；不选则取全程。 */
    fun segmentsOf(trajectory: Trajectory, turnIndex: Int?): List<Segment> =
        if (turnIndex == null) trajectory.segments else trajectory.segments.filter { it.turnIndex == turnIndex }
}
