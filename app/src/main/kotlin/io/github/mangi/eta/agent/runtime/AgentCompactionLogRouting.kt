package io.github.mangi.eta.agent.runtime

/**
 * 压缩进度日志的分流规则。
 *
 * 为什么单独抽出来：这段规则原先写死在 `AgentRuntimeRunExecutor` 的一个 lambda 里，
 * 只有「判定行」和「摘要请求行」会走 `infoPersisted`，其余落到普通 `info`——
 * 而普通 info 会被后续 DEBUG 事件顶出 logcat 环形缓冲，真机复盘时读不到。
 *
 * 后果是实测踩过的：真机上压缩确实跑了（`agent_context_summary` 有记录），
 * 却完全查不到「压了几批、每批多大、慢在哪」，因为分批行全丢了。
 * 更糟的是原注释明写着「两类行必须落盘」，实现只兑现了一类，没有任何测试守着，
 * 所以一直没被发现。
 *
 * 放在这里之后，分流规则可以在纯 JVM 单测里固定住，不用起 Android 运行时。
 */
internal object AgentCompactionLogRouting {

    /** 需要落盘（`infoPersisted`）的行该用什么前缀。`null` 表示按普通 info 处理。 */
    enum class Sink {
        /** 判定行：为何压 / 为何没压。原样写，外部按前缀检索。 */
        DECISION,

        /** 单次摘要请求的耗时。补 `run=` 前缀，便于与判定行对上同一轮。 */
        SUMMARY,

        /** 分批进度、合并、跳过合并。同理补 `run=` 前缀。 */
        BATCH,
    }

    /** 落盘时统一加的前缀，便于按 run 归组检索。 */
    const val SUMMARY_PREFIX = "agent_context_summary"
    const val BATCH_PREFIX = "agent_context_batch"

    /**
     * 这类行该落到哪。
     *
     * 分批相关的行有三种写法（「分批」「合并」「跳过合并」），以前逐个列举会漏，
     * 所以这里按共同前缀 `上下文摘要` 一次覆盖，并显式排除已单独处理的「摘要请求」。
     */
    fun sinkFor(line: String): Sink? = when {
        line.startsWith("agent_context_decision") -> Sink.DECISION
        line.startsWith("摘要请求") -> Sink.SUMMARY
        // 注意顺序：必须在「摘要请求」之后判断，两者都以中文开头。
        line.startsWith("上下文摘要") -> Sink.BATCH
        else -> null
    }

    /**
     * 生成最终要落盘的那一行；返回 `null` 表示不该落盘（调用方走普通 info）。
     *
     * @param runId 当前 run，用于把同轮的多条进度串起来
     */
    fun format(line: String, runId: String): String? = when (sinkFor(line)) {
        // 判定行原样保留：外部脚本按 `agent_context_decision` 前缀检索。
        Sink.DECISION -> line
        Sink.SUMMARY -> "$SUMMARY_PREFIX run=$runId $line"
        Sink.BATCH -> "$BATCH_PREFIX run=$runId $line"
        null -> null
    }
}
