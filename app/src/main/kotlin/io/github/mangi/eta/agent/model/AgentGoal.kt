package io.github.mangi.eta.agent.model

import java.util.UUID
import org.json.JSONObject

/** 目标的四种状态；与 `goal` 工具 schema 的 state 枚举保持一致。 */
internal enum class AgentGoalPhase(val wire: String, val label: String) {
    ACTIVE("active", "进行中"),
    PAUSED("paused", "已暂停"),
    BLOCKED("blocked", "受阻"),
    COMPLETED("completed", "已完成"),
    ;

    companion object {
        fun fromWire(value: String): AgentGoalPhase? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一个会话的长期目标：跨轮保留「要做到什么、现在什么状态」。
 *
 * 与 [AgentTodoPlan] 的分工：todo 是这一步做什么（步骤清单，整表替换）；
 * goal 是为什么做、做到什么算完成（单个目标，带 revision 做乐观并发）。
 * 移动端不自动续接轮次，goal 只负责「记住」和「可被查询/更新」。
 */
internal data class AgentGoal(
    val id: String,
    val revision: Int,
    val objective: String,
    val phase: AgentGoalPhase,
    val blockedReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("revision", revision)
        .put("objective", objective)
        .put("phase", phase.wire)
        .put("blocked_reason", blockedReason ?: JSONObject.NULL)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)

    /** 卡片/提示共用的一行：目标 + 状态。 */
    fun renderLine(): String = buildString {
        append(objective)
        append("（").append(phase.label).append("）")
        if (phase == AgentGoalPhase.BLOCKED && !blockedReason.isNullOrBlank()) {
            append("：").append(blockedReason)
        }
    }

    /**
     * 系统提示里的目标段。只注入未完成目标：
     * 已完成的目标留在文件里供查询，但不再影响新的一轮。
     */
    fun promptSection(): String = buildString {
        appendLine("<goal>")
        appendLine("这是本会话跨轮保留的长期目标（不是当前这一步的任务清单）：")
        appendLine(renderLine())
        append("继续推进这个目标；目标变化用 goal update 修改，完成后用 goal update state=complete。")
        appendLine()
        append("</goal>")
    }

    companion object {
        const val MAX_OBJECTIVE_CHARS = 500
        const val MAX_BLOCKED_REASON_CHARS = 200

        fun create(objective: String, now: Long = System.currentTimeMillis()): AgentGoal {
            val text = objective.trim()
            require(text.isNotEmpty()) { "目标不能为空" }
            require(text.length <= MAX_OBJECTIVE_CHARS) { "目标超过 $MAX_OBJECTIVE_CHARS 字" }
            return AgentGoal(
                id = UUID.randomUUID().toString(),
                revision = 1,
                objective = text,
                phase = AgentGoalPhase.ACTIVE,
                blockedReason = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun fromStoredJson(text: String?): AgentGoal? {
            if (text.isNullOrBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val objective = json.optString("objective").trim()
            if (objective.isEmpty() || objective.length > MAX_OBJECTIVE_CHARS) return null
            val phase = AgentGoalPhase.fromWire(json.optString("phase")) ?: return null
            val revision = json.optInt("revision", 0)
            if (revision <= 0) return null
            val id = json.optString("id").trim()
            if (id.isEmpty()) return null
            val blocked = json.optString("blocked_reason").takeIf(String::isNotBlank)
            return AgentGoal(
                id = id,
                revision = revision,
                objective = objective,
                phase = phase,
                blockedReason = blocked,
                createdAt = json.optLong("created_at", 0),
                updatedAt = json.optLong("updated_at", 0),
            )
        }
    }
}
