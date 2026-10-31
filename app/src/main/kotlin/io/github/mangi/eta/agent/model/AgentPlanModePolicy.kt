package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * 计划模式的工具裁剪：只留下「看」的工具。
 *
 * 这一层的边界要说清楚——它是**行为约束**，不是沙箱：
 * 白名单里保留了 `terminal`，因为调研经常只能靠 shell 读（`ls`、`cat`、`dumpsys`），
 * 而工具层无法判断一条命令是不是只读。真正的约束来自系统提示里的「不得修改」。
 * 如果以后要收紧，正确方向是给命令做静态只读判定，而不是把整个 shell 拿掉。
 *
 * 不在白名单里的工具（含全部 MCP 工具）一律不暴露：外部工具的能力无法在本地判定，
 * 宁可让模型在计划里写明「需要某个 MCP 工具」，也不要在计划阶段就让它动手。
 */
internal object AgentPlanModePolicy {

    /** 名字前缀即语义的只读工具。 */
    private val READ_ONLY_PREFIXES = listOf("get_", "search_", "list_", "read_", "recent_", "top_")

    /** 没有统一前缀、但只看不改的工具；internal 供测试校验名字没有拼错。 */
    internal val readOnlyNames: Set<String> = setOf(
        "observe_screen",
        "device_status",
        "network_info",
        "app_usage_summary",
        "inspect_app",
        "stat_file",
        "glob_files",
        "grep_files",
        "code_outline",
        "project_info",
        "web_search",
        "fetch_url",
        "browser_use",
        "memory_get",
        "skills_list",
        "skills_read",
        "skills_read_resource",
        "skills_list_curated",
        "skills_inspect_github",
        "wait",
        "wait_for_text",
        "wait_for_package",
        // 只写本机任务清单与长期目标，不改设备状态；计划模式用得上。
        "todo_write",
        "goal",
        // 提问不改任何状态。
        "ask_user",
        // 见类注释：调研必需，靠提示约束只读。
        "terminal",
    )

    fun isReadOnly(toolName: String): Boolean =
        toolName in readOnlyNames || READ_ONLY_PREFIXES.any { prefix -> toolName.startsWith(prefix) }

    /** 过滤工具目录；保留原顺序，未知或写类工具直接不暴露。 */
    fun retainReadOnly(tools: JSONArray): JSONArray {
        val kept = JSONArray()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isNotBlank() && isReadOnly(name)) kept.put(tool)
        }
        return kept
    }
}
