package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话级任务台账：一次运行里派出去的子任务，连状态一起留在这里。
 *
 * 为什么需要它：`subagent` 的结果只活在一轮对话里 —— 同一次运行里模型还能看见工具结果，
 * 到了下一轮（或进程重启后），它就只剩一句"我好像派过一个子代理"，既不知道 id、也没法续接。
 * 台账把这层记忆挪到磁盘上，于是 `tasks` 能回答"派出去过什么、跑到哪、哪个还能 resume"。
 *
 * 三条边界，写在这里以免以后被顺手改坏：
 * 1. **同一 id 只留最新一条**：台账是"当前状态"，不是审计流水；历史在子代理会话里。
 * 2. **只留摘要**：完整输出在 `SubagentSessionStore`，台账复制一份大文本会让两个地方的说法
 *    随时间漂移。
 * 3. **有界**：最多 [MAX_ENTRIES] 条，超出丢最旧的；运行中的条目都在最前面，不会被丢。
 */
internal data class AgentTaskLedger(val entries: List<AgentTaskRecord> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * 写入一条记录：同 id 覆盖，其余按开始时间倒序（最近的在最前）。
     *
     * 用 `startedAt` 排序而不是"谁最后更新谁在前"：完成一条旧任务不该把它顶到新任务前面，
     * 否则列表顺序会随收尾顺序跳来跳去。
     */
    fun upsert(record: AgentTaskRecord): AgentTaskLedger {
        val merged = entries.filterNot { it.id == record.id } + record
        return AgentTaskLedger(merged.sortedByDescending { it.startedAt }.take(MAX_ENTRIES))
    }

    /** 结束一条记录；台账里没有这个 id（比如启动时写盘失败）返回 null，由调用方决定怎么处理。 */
    fun finish(
        id: String,
        status: AgentTaskStatus,
        summary: String?,
        endedAt: Long,
    ): AgentTaskLedger? {
        if (entries.none { it.id == id }) return null
        val bounded = summary?.trim()?.takeIf { it.isNotEmpty() }?.take(AgentTaskRecord.MAX_SUMMARY_CHARS)
        return AgentTaskLedger(
            entries.map { entry ->
                if (entry.id != id) {
                    entry
                } else {
                    entry.copy(status = status, summary = bounded, endedAt = endedAt)
                }
            },
        )
    }

    /** 按状态过滤；`null` 表示全部。 */
    fun withStatus(status: AgentTaskStatus?): List<AgentTaskRecord> =
        if (status == null) entries else entries.filter { it.status == status }

    fun counts(): Map<AgentTaskStatus, Int> =
        AgentTaskStatus.entries.associateWith { status -> entries.count { it.status == status } }

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put(
            "tasks",
            JSONArray().also { array -> entries.forEach { entry -> array.put(entry.toJson()) } },
        )

    /** 给 `tasks` 工具的一行汇总；空台账也要能回答"没有派过"。 */
    fun renderSummary(): String {
        if (entries.isEmpty()) return "本会话还没有派发过子任务"
        return counts()
            .filterValues { it > 0 }
            .entries
            .joinToString(" · ") { (status, count) -> "${statusLabel(status)} $count" }
    }

    companion object {
        /** 32 条 × 一条几百字节的摘要 ≈ 十几 KB，远小于文件上限；再多也不是"当前状态"了。 */
        const val MAX_ENTRIES = 32
        const val VERSION = 1

        fun statusLabel(status: AgentTaskStatus): String = when (status) {
            AgentTaskStatus.RUNNING -> "运行中"
            AgentTaskStatus.COMPLETED -> "已完成"
            AgentTaskStatus.FAILED -> "失败"
            AgentTaskStatus.CANCELLED -> "已停止"
        }

        /**
         * 读回落盘内容。整体坏掉返回 null（当作没有台账），单条坏掉只丢那一条：
         * 一条字段超限不该让整份台账消失。
         */
        fun fromStoredJson(text: String): AgentTaskLedger? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val array = root.optJSONArray("tasks") ?: return null
            val seen = HashSet<String>()
            val parsed = ArrayList<AgentTaskRecord>(array.length())
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index) ?: continue
                val record = AgentTaskRecord.fromJson(raw) ?: continue
                if (!seen.add(record.id)) continue
                parsed += record
                if (parsed.size >= MAX_ENTRIES) break
            }
            return AgentTaskLedger(parsed.sortedByDescending { it.startedAt })
        }
    }
}
