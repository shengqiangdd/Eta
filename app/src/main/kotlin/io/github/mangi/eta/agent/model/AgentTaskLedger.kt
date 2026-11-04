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
                    entry.copy(
                        status = status,
                        summary = bounded,
                        endedAt = endedAt,
                        progress = null,
                        updatedAt = endedAt,
                    )
                }
            },
        )
    }

    /**
     * 只更新运行中条目的进度，不改状态。
     *
     * 为什么不复用 [upsert]：进度每两秒写一次，整条替换会把并发结束写入的终态覆盖回 RUNNING。
     * 这里只动 progress/updatedAt 两个字段，且非 RUNNING 条目直接跳过。
     */
    fun progress(id: String, text: String, at: Long): AgentTaskLedger? {
        val bounded = text.trim().takeIf { it.isNotEmpty() }?.take(AgentTaskRecord.MAX_PROGRESS_CHARS) ?: return null
        val target = entries.firstOrNull { it.id == id && it.status == AgentTaskStatus.RUNNING } ?: return null
        return AgentTaskLedger(
            entries.map { entry ->
                if (entry.id == target.id) entry.copy(progress = bounded, updatedAt = at) else entry
            },
        )
    }

    /** 按状态过滤；`null` 表示全部。 */
    fun withStatus(status: AgentTaskStatus?): List<AgentTaskRecord> =
        if (status == null) entries else entries.filter { it.status == status }

    fun counts(): Map<AgentTaskStatus, Int> =
        AgentTaskStatus.entries.associateWith { status -> entries.count { it.status == status } }

    fun toJson(owner: String = ""): JSONObject = JSONObject()
        .put("version", VERSION)
        .apply { if (owner.isNotEmpty()) put("owner", owner) }
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

    /**
     * 注入系统提示的段落；空台账返回空串，调用方据此不追加消息。
     *
     * 为什么进系统提示而不是每轮调 `tasks`：模型跨轮看不见上一轮的工具结果，只记得"好像派过"，
     * 于是要么漏接、要么重新派一个重复的子代理。把状态放在前缀里，它每轮都看得见。
     *
     * 只列最近 [maxLines] 条，运行中的排在前面：台账上限 32 条，整份注入会在长会话里白占前缀字节；
     * 条数超出时留一句指向 `tasks`，需要细节时模型自己去取。
     */
    fun promptSection(maxLines: Int = DEFAULT_PROMPT_LINES): String {
        if (entries.isEmpty() || maxLines <= 0) return ""
        val running = entries.filter { it.status == AgentTaskStatus.RUNNING }
        val settled = entries.filterNot { it.status == AgentTaskStatus.RUNNING }
        // entries 已按 startedAt 倒序，两组各自保序；运行中的先占名额，避免被一串刚收尾的任务挤出视野。
        val shown = (running + settled).take(maxLines)
        // 跨进程后子代理历史就没了：这一段必须按条目实际状态给提示，
        // 全部失效时还写「调用 resume 接着用」等于让模型去撞一次必然失败的工具调用。
        val resumeable = entries.any { !it.sessionExpired }
        return buildString {
            appendLine("<subagent_tasks>")
            appendLine("本会话已派出的子代理及其当前状态（跨轮保留；这是执行痕迹，不是待办清单）：")
            appendLine(renderSummary())
            shown.forEach { appendLine(it.renderLine()) }
            if (shown.size < entries.size) {
                appendLine("另有 ${entries.size - shown.size} 条更早的任务未列出，需要完整列表时调用 tasks。")
            }
            if (resumeable) {
                // 不能写「行尾的 id」：行格式是「[状态] 标题 · id（失效标记） — 摘要」，
                // id 后面还可能跟失效标记与摘要；让模型去行尾找只会让它拿到一段文字。
                append("需要接着用其中某个子代理的上下文时，把该行「· 」后面的 id 填进 subagent(resume=\"<id>\", ...)，不要从头再派一个；")
                appendLine("标了「上下文已失效」的条目不能 resume，那部分工作需要重新派发。")
            } else {
                appendLine("这些条目的上下文都已经失效（进程重启后子代理历史不会保留），不能 resume；需要继续时重新派发一个子代理。")
            }
            append("</subagent_tasks>")
        }
    }

    /** 还能被 resume 的条目数；0 表示整份台账的上下文都已失效。 */
    val resumableCount: Int get() = entries.count { !it.sessionExpired }

    /**
     * `tasks` 工具里的一句总说明，与 [promptSection] 同一口径。
     *
     * 两处必须一致：系统提示说「不能 resume」而工具输出不表态时，模型会以工具输出为准，
     * 然后又去撞一次失败；反过来也一样。
     */
    fun resumeNote(): String = when {
        entries.isEmpty() -> "本会话没有可续接的子代理。"
        resumableCount == 0 -> "这些子代理的上下文都已失效（进程重启后不会保留），不能 resume；需要继续时重新派发一个子代理。"
        resumableCount == entries.size -> "以上子代理都可以用 subagent(resume=\"<id>\", ...) 接着用它的上下文。"
        else -> "共 ${entries.size} 条，其中 ${resumableCount} 条可以 resume；“session_expired=true” 的条目上下文已失效，不能 resume。"
    }

    /**
     * 把「运行中」的条目判为已停止，返回新台账；没有运行中的条目时返回 null，调用方据此省一次写盘。
     *
     * 判据不是时间，而是**写者身份**：子代理只在本进程里执行，所以带着上一个进程 token 的
     * RUNNING 条目不可能还在跑 —— 进程被杀、被系统回收或升级重启都会留下这种孤儿。
     * 不修正的后果是 `tasks` 永远报「运行中」，用户等一个不会再更新的结果。
     *
     * 已有摘要不覆盖：那条摘要可能是上一轮真实写下的进度，只在没有摘要时补一句说明。
     *
     * [expireSessions] 为 true 时额外把**每一条**标成「上下文已失效」：子代理历史只活在内存里，
     * 进程一变就全没了，这跟「还在不在跑」是两件独立的事 —— 一条已完成的记录同样不能 resume。
     *
     * 返回 null 表示没有任何需要修正的地方，调用方据此省一次写盘。
     */
    fun abandonRunning(
        note: String = ORPHAN_NOTE,
        expireSessions: Boolean = false,
    ): AgentTaskLedger? {
        val needsFix = entries.any { it.status == AgentTaskStatus.RUNNING || (expireSessions && !it.sessionExpired) }
        if (!needsFix) return null
        return AgentTaskLedger(
            entries.map { entry ->
                val expired = entry.sessionExpired || expireSessions
                if (entry.status != AgentTaskStatus.RUNNING) {
                    entry.copy(sessionExpired = expired)
                } else {
                    entry.copy(
                        status = AgentTaskStatus.CANCELLED,
                        summary = entry.summary?.takeIf { it.isNotBlank() } ?: note,
                        sessionExpired = expired,
                    )
                }
            },
        )
    }

    companion object {
        /** 32 条 × 一条几百字节的摘要 ≈ 十几 KB，远小于文件上限；再多也不是"当前状态"了。 */
        const val MAX_ENTRIES = 32

        /**
         * 注入系统提示的条数上限。8 条 ≈ 1KB：够覆盖"这一轮正在跑的 + 刚收尾的"，
         * 更早的交给 `tasks` 按状态过滤，不必每轮都塞进前缀。
         */
        const val DEFAULT_PROMPT_LINES = 8

        /** 孤儿条目的说明文案；同时用于 `tasks` 输出与系统提示，改文案前先想清楚两者都会变。 */
        const val ORPHAN_NOTE = "进程重启前未收尾，实际结果未知"
        const val VERSION = 1

        fun statusLabel(status: AgentTaskStatus): String = when (status) {
            AgentTaskStatus.RUNNING -> "运行中"
            AgentTaskStatus.COMPLETED -> "已完成"
            AgentTaskStatus.FAILED -> "失败"
            AgentTaskStatus.CANCELLED -> "已停止"
            AgentTaskStatus.STALLED -> "已自动收尾"
        }

        /**
         * 读回落盘内容。整体坏掉返回 null（当作没有台账），单条坏掉只丢那一条：
         * 一条字段超限不该让整份台账消失。
         */
        fun fromStoredJson(text: String): AgentTaskLedger? =
            runCatching { JSONObject(text) }.getOrNull()?.let(::fromStoredJson)

        /** 已经解析过的根对象直接用，省一次解析（读路径每轮都跑）。 */
        fun fromStoredJson(root: JSONObject): AgentTaskLedger? {
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
