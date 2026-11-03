package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话级任务台账工具：`tasks`。
 *
 * 它只读 —— 台账由子代理执行器在生命周期里写，模型不能手改别人的执行记录。这样做的原因：
 * 一份可以被模型改写的"执行状态"既不能用来判断"还有谁在跑"，也会和子代理会话的真实状态
 * 互相矛盾；需要改状态的是 `subagent`（真去跑）和 `goal`（改目标），不是这里。
 */
internal object AgentTasksToolCatalog {

    const val NAME = "tasks"

    /** 默认只给最近 10 条：台账的用途是"现在都有谁"，不是把整份历史摊进上下文。 */
    const val DEFAULT_LIMIT = 10

    val names: Set<String> = setOf(NAME)

    /** 一次查询：状态过滤（null = 全部）+ 最多返回几条。 */
    data class Query(val status: AgentTaskStatus?, val limit: Int)

    fun parse(args: JSONObject): Query {
        val allowed = setOf("status", "limit")
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) throw IllegalArgumentException("tasks 不接受未知字段 `$key`")
        }
        val rawStatus = args.optString("status").trim().lowercase()
        val status = when {
            rawStatus.isEmpty() || rawStatus == "all" -> null
            else -> AgentTaskStatus.fromWire(rawStatus)
                ?: throw IllegalArgumentException(
                    "status 只能是 running / completed / failed / cancelled / all",
                )
        }
        val limit = if (args.has("limit")) args.optInt("limit", 0) else DEFAULT_LIMIT
        if (limit <= 0) throw IllegalArgumentException("limit 必须是正整数")
        return Query(status = status, limit = limit.coerceAtMost(AgentTaskLedger.MAX_ENTRIES))
    }

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description = "Read this conversation's task ledger: which subagents were dispatched, " +
                    "their ids, status, a one-line summary, and the latest progress of running entries. " +
                    "It survives across turns, so a subagent " +
                    "started in an earlier turn can still be found and resumed with " +
                    "`subagent(resume=\"<id>\", ...)` instead of redoing its work. " +
                    "Read-only: the ledger is written by the runtime when a subagent starts and ends; " +
                    "this tool cannot change it. Defaults to the newest $DEFAULT_LIMIT entries " +
                    "(newest first); `status=\"running\"` answers \"what is still running\".",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "status",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("running")
                                            .put("completed")
                                            .put("failed")
                                            .put("cancelled")
                                            .put("all"),
                                    )
                                    .put("description", "Optional filter; omit or `all` for every entry."),
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", AgentTaskLedger.MAX_ENTRIES)
                                    .put("description", "How many entries to return, newest first. Default $DEFAULT_LIMIT."),
                            ),
                    ),
            ),
        )
    }
}
