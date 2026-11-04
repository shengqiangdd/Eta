package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 台账条目的状态；wire 值与 `tasks` 工具的过滤枚举一致。 */
internal enum class AgentTaskStatus(val wire: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),

    /**
     * 停滞看门狗自动收尾（长时间无进展 / 超单次上限）。
     *
     * 为什么不归入 [CANCELLED]：两者对模型的含义完全相反 —— 「用户叫停」不该重试，
     * 「卡住自动放弃」应该拆小重派。归在一起的话，`tasks(status="cancelled")` 会把
     * 一次可恢复的失败读成用户主动结束，模型于是不再尝试。
     */
    STALLED("stalled"),
    ;

    companion object {
        fun fromWire(value: String): AgentTaskStatus? = entries.firstOrNull { it.wire == value }

        /**
         * 读落盘台账时用：认不出的状态**降级**而不是丢整条。
         *
         * 为什么不能直接丢弃：台账文件是跨版本共享的。新版写下的 `stalled` 在旧版眼里不认识，
         * 旧版每次启动、每次 `tasks` 都会把那一条静默漏掉 —— 用户看到的是“我明明派过，记录没了”。
         * 降到 [CANCELLED]：旧版至少能诚实地说「已停止」，而不是凭空删一行执行痕迹。
         */
        fun fromStoredWire(value: String): AgentTaskStatus =
            fromWire(value) ?: CANCELLED
    }
}

/**
 * 会话级任务台账的一条记录。
 *
 * 与 `todo_write` 的区别：todo 记的是「打算做什么」的清单，这里是「已经派出去什么、跑到哪」的
 * 执行痕迹；与 `goal` 的区别：goal 是一个长期目标，台账是它的执行记录（一个目标可以有多条）。
 *
 * 只存展示与追溯需要的最小字段：完整结果仍在子代理会话里（`SubagentSessionStore`），
 * 台账只留一段有界摘要，避免把大段输出复制成第二份。
 */
internal data class AgentTaskRecord(
    val id: String,
    val kind: String,
    val title: String,
    val status: AgentTaskStatus,
    val ref: String?,
    val summary: String?,
    val startedAt: Long,
    val endedAt: Long?,
    /** 运行中最近一步在做什么；只用于让用户和模型判断「还在动」还是「卡住了」。 */
    val progress: String? = null,
    /** 最后一次进度更新的 wall clock；结束态为收尾时间。 */
    val updatedAt: Long? = null,
    /**
     * 这条记录所属的子代理上下文已经不存在（进程重启），**不能再 resume**。
     *
     * 为什么记在条目上而不是只看文件级 owner：写盘会把整个文件标成新进程，只看 owner 的话，
     * 「重启前派过、重启后又派了一个新的」这条旧记录会被当成可以续接，模型照着 resume
     * 只会拿到一个失败。
     *
     * 一旦置位就保留到被 [AgentTaskLedger.MAX_ENTRIES] 挤掉：子代理历史只活在内存里，
     * 重启后不会恢复，这一点不会随时间改变。
     */
    val sessionExpired: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("title", title)
        .put("status", status.wire)
        .put("ref", ref ?: JSONObject.NULL)
        .put("summary", summary ?: JSONObject.NULL)
        .put("started_at", startedAt)
        .put("ended_at", endedAt ?: JSONObject.NULL)
        .put("progress", progress ?: JSONObject.NULL)
        .put("updated_at", updatedAt ?: JSONObject.NULL)
        .put("session_expired", sessionExpired)

    /** 给模型看的一行；状态在前、id 紧跟标题，摘要另起不拼进首行。 */
    fun renderLine(): String = buildString {
        append("[").append(status.wire).append("] ")
        append(title)
        if (kind.isNotBlank() && kind != KIND_SUBAGENT) append("（").append(kind).append("）")
        append(" · ").append(id)
        if (sessionExpired) append("（上下文已失效，不能 resume）")
        summary?.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.let { first ->
            append(" — ").append(first.take(80))
        }
        if (status == AgentTaskStatus.RUNNING) {
            progress?.takeIf { it.isNotBlank() }?.let { line ->
                append(" · ").append(line.lineSequence().firstOrNull()?.take(80).orEmpty())
            }
        }
    }

    companion object {
        const val MAX_TITLE_CHARS = 120
        const val MAX_SUMMARY_CHARS = 600
        const val MAX_PROGRESS_CHARS = 200
        /** 子代理 id 是 `sub-` + 十六进制；64 足够，且不让坏文件把 id 撑成一段正文。 */
        const val MAX_ID_CHARS = 64
        /** 目前只有这一种任务；`kind` 留着是因为台账不能只为 subagent 定制。 */
        const val KIND_SUBAGENT = "subagent"

        fun fromJson(raw: JSONObject): AgentTaskRecord? {
            val id = raw.optString("id").trim()
            val kind = raw.optString("kind").trim()
            val title = raw.optString("title").trim()
            val status = AgentTaskStatus.fromStoredWire(raw.optString("status").trim())
            if (id.isEmpty() || kind.isEmpty() || title.isEmpty()) return null
            val startedAt = raw.optLong("started_at", 0L)
            if (startedAt <= 0L) return null
            val endedAt = if (raw.isNull("ended_at")) null else raw.optLong("ended_at").takeIf { it > 0L }
            val updatedAt = if (raw.isNull("updated_at")) null else raw.optLong("updated_at").takeIf { it > 0L }
            return AgentTaskRecord(
                id = id.take(MAX_ID_CHARS),
                kind = kind.take(32),
                title = title.take(MAX_TITLE_CHARS),
                status = status,
                ref = raw.optString("ref").trim().takeIf { it.isNotEmpty() },
                summary = raw.optString("summary").trim().takeIf { it.isNotEmpty() }?.take(MAX_SUMMARY_CHARS),
                startedAt = startedAt,
                endedAt = endedAt,
                progress = raw.optString("progress").trim().takeIf { it.isNotEmpty() }?.take(MAX_PROGRESS_CHARS),
                updatedAt = updatedAt,
                sessionExpired = raw.optBoolean("session_expired", false),
            )
        }
    }
}
