package io.github.mangi.eta.agent.acp

import org.json.JSONArray
import org.json.JSONObject

/**
 * ACP（Agent Client Protocol）委派工具的 schema 与参数解析（纯逻辑，可单测）。
 *
 * 定位：让 Eta 把**编码类任务**委派给外部 ACP agent（例如 Codex），
 * 而不是自己一点点读文件改代码。ACP 是 JSON-RPC over stdio，
 * 本地 agent 由宿主（这里是 Eta 的 Linux 环境）拉起子进程。
 *
 * 为什么不做成「模型 Provider」：Provider 走的是「替代 Eta 自己的大脑」，
 * 而这里要的是「Eta 仍然是主脑，把执行委派出去」。
 * 后者不改变会话、记忆与工具链，只是多一条干活的路。
 */
internal object AgentAcpTools {

    val names: Set<String> = setOf(
        "acp_agent_list",
        "acp_delegate",
        "acp_session_status",
        "acp_session_close",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                tool(
                    name = "acp_agent_list",
                    description =
                        "列出已配置的 ACP 编码代理（Codex、Claude）。返回代理 id、可执行文件路径、" +
                            "是否具备凭据、当前存活的委派会话，以及 Eta 中可用的模型提供商。" +
                            "**委派前先调用它**，不要把任务发给一个没有凭据或没装的代理。" +
                            "注意 credential_present 只说明「凭据找得到」，不保证真能跑通：" +
                            "第三方供应商可能与某个代理的模型名不兼容，那时 acp_delegate 会失败。" +
                            "失败后换另一个代理重试一次是合理的；同样失败就停下来告诉用户，不要硬撞。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
            .put(
                tool(
                    name = "acp_delegate",
                    description =
                        "把一个**编码/工程任务**委派给 ACP 代理，并等待它完成。" +
                            "适合：跨文件重构、批量改动、跑测试并修失败、按规格写新模块。" +
                            "不适合：需要手机 GUI 操作或访问 Android 系统状态的任务（那些用 Eta 自己的工具）。" +
                            "任务会在代理的工作目录里执行，默认是当前项目目录；返回代理的最终答复与改动摘要。" +
                            "结果里的 text 与 tool_activity 有长度上限（超出会标 text_truncated）；完整原文落在" +
                            " transcript_path 指向的 Markdown 文件里，需要全文时读那个文件，不要重新委派一遍。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "agent",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "代理 id；来自 acp_agent_list。省略时用默认代理"),
                                )
                                .put(
                                    "task",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "任务描述，写清楚目标、约束与验收标准；越具体越快越准。最多 8000 字",
                                        ),
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录（Linux 环境内绝对路径），默认 /workspace"),
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，30..1800；默认 600"),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "指定代理用的模型（可选）。用户说「用 X 模型」时传 X；" +
                                                "留空则用提供商默认模型。可用值看上一次结果的 available_models。" +
                                                "名字必须在代理清单里，否则会直接失败（代理自己不校验）。",
                                        ),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "Eta 中配置的模型提供商 id 或名称（可选，codex 与 claude 都支持）。" +
                                                "用户说「用 lingzhan 跑」时传 lingzhan；留空则用 Eta 当前选中的提供商。" +
                                                "提供商必须已在 Eta 的模型提供商设置里配置好 API key。",
                                        ),
                                ),
                        )
                        .put("required", JSONArray().put("task")),
                )
            )
            .put(
                tool(
                    name = "acp_session_status",
                    description = "查看某个委派会话的状态、最近输出与已产生的文件改动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "acp_delegate 返回的会话 id"),
                                ),
                        )
                        .put("required", JSONArray().put("session_id")),
                )
            )
            .put(
                tool(
                    name = "acp_session_close",
                    description = "结束一个委派会话并回收进程。session_id=all 时结束全部。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "会话 id，或 all"),
                                ),
                        )
                        .put("required", JSONArray().put("session_id")),
                )
            )
    }

    data class DelegateRequest(
        val agent: String,
        val task: String,
        val cwd: String,
        val timeoutSeconds: Int,
        val model: String? = null,
        val provider: String? = null,
    )

    data class SessionRequest(val sessionId: String)

    fun parseDelegate(args: JSONObject): DelegateRequest? {
        val task = args.optString("task").trim()
        if (task.isEmpty() || task.length > MAX_TASK_CHARS) return null
        val cwd = args.optString("cwd").trim().ifEmpty { DEFAULT_CWD }
        // 只接受 Linux 环境内的绝对路径，避免把宿主路径或相对路径带进子进程。
        if (!cwd.startsWith("/") || cwd.contains("..")) return null
        val timeout = args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        if (timeout !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) return null
        val agent = args.optString("agent").trim()
        // 代理 id 可能是内置名（codex）或插件命名空间形式（<plugin>:<agent>）。
        if (agent.isNotEmpty() && !AGENT_ID.matches(agent)) return null
        val model = args.optString("model").trim().takeIf { it.isNotEmpty() }
        if (model != null && (model.length > AcpModels.MAX_MODEL_ID_CHARS || model.any { it.isISOControl() })) {
            return null
        }
        val provider = args.optString("provider").trim().takeIf { it.isNotEmpty() }
        if (provider != null && (provider.length > MAX_PROVIDER_CHARS || provider.any { it.isISOControl() })) {
            return null
        }
        return DelegateRequest(agent, task, cwd, timeout, model, provider)
    }

    fun parseSession(args: JSONObject): SessionRequest? {
        val id = args.optString("session_id").trim()
        if (id.isEmpty() || id.length > 120) return null
        // all 是保留字，用于一次性结束全部会话。归一成小写，
        // 否则调用方拿 "ALL" 与小写 "all" 比较会静默失配。
        if (id.equals("all", ignoreCase = true)) return SessionRequest("all")
        if (!SESSION_ID.matches(id)) return null
        return SessionRequest(id)
    }

    const val MAX_TASK_CHARS = 8_000
    const val MAX_PROVIDER_CHARS = 120
    const val DEFAULT_CWD = "/workspace"
    const val DEFAULT_TIMEOUT_SECONDS = 600
    const val MIN_TIMEOUT_SECONDS = 30
    const val MAX_TIMEOUT_SECONDS = 1_800

    /**
     * 代理 id：内置名（`codex`）或插件命名空间（`<plugin-id>:<agent-id>`）。
     * 冒号是分隔符，只允许出现一次，避免把任意字符串当成 id。
     */
    private val AGENT_ID = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}(:[A-Za-z][A-Za-z0-9_-]{0,31})?$")
    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,120}$")

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
