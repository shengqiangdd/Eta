package io.github.mangi.eta.agent.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta 插件 manifest 模型与校验（纯逻辑，可单测）。
 *
 * 插件是什么：一个 **zip 包**，里面有一份 `eta-plugin.json`，描述它给 Eta 增加什么能力：
 * - `tools`：追加到 Agent 工具目录的额外工具（目前只承载"说明性工具"，
 *   即告诉模型"这个能力已安装、该怎么用"；真正执行由 `mcp` 或 `acp` 段落地）
 * - `mcp`：要注册的 MCP 服务器（stdio 或 http），与内置 MCP 管理器共用
 * - `acp`：要注册的 ACP 编码代理（可执行路径 + 凭据环境变量名）
 * - `prompt`：追加进系统提示的说明文字
 *
 * 为什么不让插件直接跑任意 Kotlin 代码：Eta 是单 APK，没有 dex 插件加载器。
 * 用 manifest 描述 + 复用现有 MCP/ACP 通道，既能"安装即增强"，
 * 又不引入动态代码执行这种无法审计的攻击面。
 */
internal object PluginManifest {

    const val FILE_NAME = "eta-plugin.json"
    const val MAX_MANIFEST_BYTES = 256 * 1024

    const val ID_PATTERN = "^[a-z][a-z0-9_-]{2,63}$"
    const val VERSION_PATTERN = "^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$"
    const val MAX_ENV_ENTRIES = 64

    data class McpServer(
        val name: String,
        val transport: String,
        val command: String,
        val args: List<String>,
        val url: String,
        val env: Map<String, String>,
    )

    data class AcpAgent(
        val id: String,
        val title: String,
        val executable: String,
        val credentialEnvKeys: List<String>,
    )

    data class ToolSpec(
        val name: String,
        val description: String,
    )

    data class Plugin(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val prompt: String,
        val tools: List<ToolSpec>,
        val mcp: List<McpServer>,
        val acp: List<AcpAgent>,
    )

    sealed interface ParseResult {
        data class Success(val plugin: Plugin) : ParseResult
        data class Failure(val code: String, val message: String) : ParseResult
    }

    fun parse(raw: String): ParseResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            return ParseResult.Failure("MANIFEST_TOO_LARGE", "manifest 超过 $MAX_MANIFEST_BYTES 字节")
        }
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return ParseResult.Failure("MANIFEST_INVALID_JSON", "manifest 不是合法 JSON")
        }
        val id = json.optString("id").trim()
        if (!Regex(ID_PATTERN).matches(id)) {
            return ParseResult.Failure("MANIFEST_BAD_ID", "id 必须匹配 $ID_PATTERN")
        }
        val version = json.optString("version").trim()
        if (!Regex(VERSION_PATTERN).matches(version)) {
            return ParseResult.Failure("MANIFEST_BAD_VERSION", "version 必须是语义化版本，如 1.0.0")
        }
        val name = json.optString("name").trim().ifEmpty { id }
        val description = json.optString("description").trim().take(400)
        val author = json.optString("author").trim().take(120)
        val prompt = json.optString("prompt").trim().take(4_000)

        val tools = mutableListOf<ToolSpec>()
        val toolsJson = json.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until toolsJson.length()) {
            val entry = toolsJson.optJSONObject(i) ?: continue
            val toolName = entry.optString("name").trim()
            if (!Regex("^[a-z][a-z0-9_]{2,63}$").matches(toolName)) {
                return ParseResult.Failure("MANIFEST_BAD_TOOL", "工具名不合法：$toolName")
            }
            tools += ToolSpec(
                name = toolName,
                description = entry.optString("description").trim().take(600),
            )
            if (tools.size > 64) {
                return ParseResult.Failure("MANIFEST_TOO_MANY_TOOLS", "tools 最多 64 个")
            }
        }

        val mcp = mutableListOf<McpServer>()
        val mcpJson = json.optJSONArray("mcp") ?: JSONArray()
        for (i in 0 until mcpJson.length()) {
            val entry = mcpJson.optJSONObject(i) ?: continue
            val serverName = entry.optString("name").trim()
            if (!Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$").matches(serverName)) {
                return ParseResult.Failure("MANIFEST_BAD_MCP_NAME", "MCP 服务器名不合法：$serverName")
            }
            val transport = entry.optString("transport").trim().ifEmpty { "stdio" }
            // 目前只落地 http：Eta 的 MCP 客户端是 HTTP(S) 传输（McpHttpClient），
            // 没有 stdio 子进程通道。允许 stdio 会注册一个永远连不上的服务器，
            // 表现为"装好了但没有任何工具"——那是假承诺，不如在安装时就拒掉。
            if (transport != "http") {
                return ParseResult.Failure(
                    "MANIFEST_MCP_HTTP_ONLY",
                    "插件 MCP 暂只支持 transport=http（当前为 $transport）；stdio 通道未实现",
                )
            }
            val command = entry.optString("command").trim()
            val url = entry.optString("url").trim()
            if (transport == "stdio" && command.isEmpty()) {
                return ParseResult.Failure("MANIFEST_BAD_MCP_COMMAND", "stdio 传输必须给出 command")
            }
            if (transport == "http" && !url.startsWith("http://") && !url.startsWith("https://")) {
                return ParseResult.Failure("MANIFEST_BAD_MCP_URL", "http 传输必须给出 http(s) url")
            }
            val args = mutableListOf<String>()
            val argsJson = entry.optJSONArray("args") ?: JSONArray()
            for (j in 0 until argsJson.length()) {
                args += argsJson.optString(j)
                if (args.size > 64) break
            }
            val env = mutableMapOf<String, String>()
            val envJson = entry.optJSONObject("env") ?: JSONObject()
            if (envJson.length() > MAX_ENV_ENTRIES) {
                return ParseResult.Failure("MANIFEST_TOO_MANY_ENV", "env 最多 $MAX_ENV_ENTRIES 项")
            }
            envJson.keys().forEach { key -> env[key] = envJson.optString(key) }
            mcp += McpServer(serverName, transport, command, args, url, env)
            if (mcp.size > 16) {
                return ParseResult.Failure("MANIFEST_TOO_MANY_MCP", "mcp 最多 16 个")
            }
        }

        val acp = mutableListOf<AcpAgent>()
        val acpJson = json.optJSONArray("acp") ?: JSONArray()
        for (i in 0 until acpJson.length()) {
            val entry = acpJson.optJSONObject(i) ?: continue
            val agentId = entry.optString("id").trim()
            if (!Regex("^[a-z][a-z0-9_-]{0,63}$").matches(agentId)) {
                return ParseResult.Failure("MANIFEST_BAD_ACP_ID", "ACP 代理 id 不合法：$agentId")
            }
            val executable = entry.optString("executable").trim()
            if (executable.isEmpty() || !executable.startsWith("/")) {
                return ParseResult.Failure("MANIFEST_BAD_ACP_EXECUTABLE", "ACP executable 必须是绝对路径")
            }
            val keys = mutableListOf<String>()
            val keysJson = entry.optJSONArray("credential_env_keys") ?: JSONArray()
            for (j in 0 until keysJson.length()) {
                val key = keysJson.optString(j).trim()
                if (Regex("^[A-Z][A-Z0-9_]{0,63}$").matches(key)) keys += key
            }
            acp += AcpAgent(
                id = agentId,
                title = entry.optString("title").trim().ifEmpty { agentId },
                executable = executable,
                credentialEnvKeys = keys,
            )
            if (acp.size > 8) {
                return ParseResult.Failure("MANIFEST_TOO_MANY_ACP", "acp 最多 8 个")
            }
        }

        if (tools.isEmpty() && mcp.isEmpty() && acp.isEmpty() && prompt.isEmpty()) {
            return ParseResult.Failure("MANIFEST_EMPTY", "manifest 至少声明 tools、mcp、acp 或 prompt 之一")
        }
        return ParseResult.Success(
            Plugin(id, name, version, description, author, prompt, tools, mcp, acp),
        )
    }

    /** 插件能力摘要，供模型与界面一眼看清"装了什么"。 */
    fun summarize(plugin: Plugin): String = buildString {
        append(plugin.name).append(" v").append(plugin.version)
        if (plugin.description.isNotEmpty()) append(" — ").append(plugin.description)
        val parts = mutableListOf<String>()
        if (plugin.tools.isNotEmpty()) parts += "${plugin.tools.size} 个说明性工具"
        if (plugin.mcp.isNotEmpty()) parts += "${plugin.mcp.size} 个 MCP 服务器"
        if (plugin.acp.isNotEmpty()) parts += "${plugin.acp.size} 个 ACP 代理"
        if (plugin.prompt.isNotEmpty()) parts += "提示词扩展"
        if (parts.isNotEmpty()) append("（").append(parts.joinToString("、")).append('）')
    }

    /**
     * 插件声明的 ACP 代理 → 全局代理 id。
     *
     * 用 `<pluginId>:<agentId>` 命名空间，原因：
     * 1. 两个插件可以各自声明 `codex` 而不撞名；
     * 2. 代理归属哪个插件在 id 里就看得出来，日志与排错都省事；
     * 3. 卸载插件时按前缀清理，不会误伤内置代理。
     */
    /** 插件声明的 MCP 服务器在全局 MCP 列表中的 id；与 ACP 一致使用命名空间，避免与用户手加的冲突。 */
    fun mcpServerId(pluginId: String, serverName: String): String = "plugin:$pluginId:$serverName"

    fun acpAgentId(pluginId: String, agentId: String): String = "$pluginId:$agentId"

    /** 反向解析；不是命名空间形式时返回 null。 */
    fun parseAcpAgentId(globalId: String): Pair<String, String>? {
        val separator = globalId.indexOf(':')
        if (separator <= 0 || separator == globalId.length - 1) return null
        if (globalId.indexOf(':', separator + 1) >= 0) return null
        val pluginId = globalId.substring(0, separator)
        val agentId = globalId.substring(separator + 1)
        if (!Regex(ID_PATTERN).matches(pluginId)) return null
        if (!Regex("^[a-z][a-z0-9_-]{0,63}$").matches(agentId)) return null
        return pluginId to agentId
    }
}
