package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 长期目标工具：`goal`。
 *
 * 一个工具四个动作（而不是 dsh 的三个独立工具）：移动端工具目录已经很长，
 * 一个 schema + action 枚举对模型更省 token，也更容易在系统提示里讲清「先 get 再 update」。
 */
internal object AgentGoalToolCatalog {
    const val NAME = "goal"

    val names: Set<String> = setOf(NAME)

    enum class UpdateState(val wire: String) {
        EDIT("edit"),
        PAUSE("pause"),
        RESUME("resume"),
        COMPLETE("complete"),
        BLOCKED("blocked"),
        ;

        companion object {
            fun fromWire(value: String): UpdateState? = entries.firstOrNull { it.wire == value }
        }
    }

    sealed interface Command {
        data object Get : Command
        data class Create(val objective: String) : Command
        data class Update(
            val revision: Int,
            val state: UpdateState?,
            val objective: String?,
            val blockedReason: String?,
        ) : Command
        data object Clear : Command
    }

    fun parse(args: JSONObject): Command {
        val allowed = setOf("action", "objective", "state", "blocked_reason", "revision")
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) throw IllegalArgumentException("goal 不接受未知字段 `$key`")
        }
        return when (val action = args.optString("action").trim()) {
            "get" -> Command.Get
            "clear" -> Command.Clear
            "create" -> {
                val objective = args.optString("objective").trim()
                if (objective.isEmpty()) throw IllegalArgumentException("create 需要 objective")
                if (objective.length > AgentGoal.MAX_OBJECTIVE_CHARS) {
                    throw IllegalArgumentException("objective 超过 ${AgentGoal.MAX_OBJECTIVE_CHARS} 字")
                }
                Command.Create(objective)
            }
            "update" -> {
                val revision = args.optInt("revision", 0)
                if (revision <= 0) throw IllegalArgumentException("update 需要 revision（先用 action=get 读取）")
                val stateWire = args.optString("state").trim()
                val state = if (stateWire.isEmpty()) {
                    null
                } else {
                    UpdateState.fromWire(stateWire)
                        ?: throw IllegalArgumentException("state 只能是 edit/pause/resume/complete/blocked")
                }
                val objective = args.optString("objective").trim().takeIf(String::isNotEmpty)
                if (state == UpdateState.EDIT && objective == null) {
                    throw IllegalArgumentException("state=edit 需要新的 objective")
                }
                if (objective != null && objective.length > AgentGoal.MAX_OBJECTIVE_CHARS) {
                    throw IllegalArgumentException("objective 超过 ${AgentGoal.MAX_OBJECTIVE_CHARS} 字")
                }
                val blockedReason = args.optString("blocked_reason").trim().takeIf(String::isNotEmpty)
                if (blockedReason != null && blockedReason.length > AgentGoal.MAX_BLOCKED_REASON_CHARS) {
                    throw IllegalArgumentException("blocked_reason 超过 ${AgentGoal.MAX_BLOCKED_REASON_CHARS} 字")
                }
                if (state != UpdateState.BLOCKED && blockedReason != null) {
                    throw IllegalArgumentException("blocked_reason 只能与 state=blocked 一起使用")
                }
                Command.Update(revision, state, objective, blockedReason)
            }
            else -> throw IllegalArgumentException("action 只能是 get/create/update/clear（收到 ${action.ifEmpty { "空值" }}）")
        }
    }

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description = "Maintain ONE long-running goal for this conversation across turns. " +
                    "Use `get` to read it (returns id and revision). " +
                    "Use `create` only when the user asks for a multi-turn objective — never for routine single-turn work; " +
                    "if an unfinished goal already exists, update or clear it instead. " +
                    "Use `update` with the exact revision from get to edit the objective, pause, resume, complete, or mark blocked. " +
                    "Use `clear` to remove it. The goal is shown to the user and injected into later turns.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("get").put("create").put("update").put("clear"))
                                    .put("description", "get | create | update | clear"),
                            )
                            .put(
                                "objective",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", AgentGoal.MAX_OBJECTIVE_CHARS)
                                    .put("description", "What must be true when this goal is done, in one concrete sentence."),
                            )
                            .put(
                                "state",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("edit").put("pause").put("resume").put("complete").put("blocked"))
                                    .put("description", "Optional for update: edit objective | pause | resume | complete | blocked."),
                            )
                            .put(
                                "blocked_reason",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", AgentGoal.MAX_BLOCKED_REASON_CHARS)
                                    .put("description", "Only with state=blocked: why progress cannot continue."),
                            )
                            .put(
                                "revision",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("description", "Required for update: the revision returned by get."),
                            ),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
