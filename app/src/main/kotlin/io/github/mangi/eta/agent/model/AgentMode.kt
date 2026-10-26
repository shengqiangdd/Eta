package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * Agent 的工作模式。
 *
 * 为什么要有「模式」而不是一串开关：真正决定这一轮行为的是**组合**
 * （给哪些工具 + 先做什么 + 遵守哪些约束），单个开关说不清「这一轮会怎样」。
 * 三种模式各是一个完整工作方式，用户选一个，比勾五个开关更清楚。
 *
 * 模式是**行为约束**，不是沙箱：计划模式靠「只暴露只读工具」降低误改概率，
 * 但 `terminal` 仍能执行写命令（工具层判断不了一条 shell 命令是否只读），
 * 真正的边界来自系统提示里的明确禁止。这与 [AgentPlanModePolicy] 的注释一致。
 */
internal enum class AgentMode(
    val id: String,
    val title: String,
    val summary: String,
    /** 是否只暴露只读工具。 */
    val readOnlyTools: Boolean = false,
) {
    STANDARD(
        id = "standard",
        title = "标准",
        summary = "全部工具，按需执行",
    ),
    PLAN(
        id = "plan",
        title = "计划",
        summary = "只暴露只读工具，先出计划、等你确认再动手",
        readOnlyTools = true,
    ),
    CREATOR(
        id = "creator",
        title = "创造",
        summary = "为 Eta 造插件与 Skill：全部工具 + 扩展格式与流程",
    ),
    ;

    /** 按模式裁剪工具目录；保留原顺序，未知工具不受影响。 */
    fun retainTools(tools: JSONArray): JSONArray =
        if (readOnlyTools) AgentPlanModePolicy.retainReadOnly(tools) else tools

    companion object {
        /** 默认模式：升级前没有 `AGENT_MODE` 时用它。 */
        val DEFAULT: AgentMode = STANDARD

        /** 未知或空值一律回退到默认模式，避免旧配置把 Agent 卡在某个模式里。 */
        fun fromId(raw: String?): AgentMode {
            val id = raw?.trim().orEmpty()
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
