package io.github.mangi.eta.agent.plugin

import org.json.JSONArray
import org.json.JSONObject

/** 插件管理工具的 schema 与参数解析（纯逻辑，可单测）。 */
internal object AgentPluginTools {

    val names: Set<String> = setOf(
        "plugin_list",
        "plugin_install",
        "plugin_uninstall",
        "plugin_enable",
        "plugin_reload",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                tool(
                    name = "plugin_list",
                    description =
                        "列出已安装的 Eta 插件及其能力（工具 / MCP 服务器 / ACP 代理 / 提示词扩展）。" +
                            "用户问「装了哪些插件」或某个能力是否可用时先调用它。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
            .put(
                tool(
                    name = "plugin_install",
                    description =
                        "从本地路径安装 Eta 插件。可以是 zip，也可以是包含 eta-plugin.json 的目录" +
                            "（目录安装不需要打包工具，Android 上通常没有 zip 命令，自己造插件时用目录）。" +
                            "manifest 必须含 tools / mcp / acp / prompt 至少一项；MCP 目前只支持 transport=http。" +
                            "同名插件已安装时需 replace_existing=true 并带上之前返回的 id 与 sha256。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "插件 zip 或插件目录的绝对路径"),
                                )
                                .put(
                                    "replace_existing",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否允许覆盖同名插件；默认 false"),
                                )
                                .put(
                                    "expected_replacement_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "覆盖安装时必须与归档内的 id 一致"),
                                )
                                .put(
                                    "expected_archive_sha256",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "覆盖安装时必须与本次归档 SHA-256 一致"),
                                ),
                        )
                        .put("required", JSONArray().put("path")),
                )
            )
            .put(
                tool(
                    name = "plugin_uninstall",
                    description = "卸载一个已安装插件。id 必须来自 plugin_list。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "id",
                                    JSONObject().put("type", "string").put("description", "插件 id"),
                                ),
                        )
                        .put("required", JSONArray().put("id")),
                )
            )
            .put(
                tool(
                    name = "plugin_enable",
                    description = "启用或停用一个插件；停用后其工具与提示词不再注入，文件仍保留。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "id",
                                    JSONObject().put("type", "string").put("description", "插件 id"),
                                )
                                .put(
                                    "enabled",
                                    JSONObject().put("type", "boolean").put("description", "true 启用，false 停用"),
                                ),
                        )
                        .put("required", JSONArray().put("id").put("enabled")),
                )
            )
            .put(
                tool(
                    name = "plugin_reload",
                    description =
                        "重新扫描插件目录并重新注册其 ACP 代理与 MCP 服务器。" +
                            "用于手工改过插件文件、或「插件已启用但工具没出现」时；不卸载、不改启用状态。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
    }

    data class InstallRequest(
        val path: String,
        val replaceExisting: Boolean,
        val expectedReplacementId: String?,
        val expectedArchiveSha256: String?,
    )

    data class IdRequest(val id: String)

    data class EnableRequest(val id: String, val enabled: Boolean)

    private val PLUGIN_ID = Regex("^[a-z][a-z0-9_-]{2,63}$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parseInstall(args: JSONObject): InstallRequest? {
        val path = args.optString("path").trim()
        if (path.isEmpty() || !path.startsWith("/") || path.contains("..")) return null
        val expectedId = args.optString("expected_replacement_id").trim().ifEmpty { null }
        if (expectedId != null && !PLUGIN_ID.matches(expectedId)) return null
        val expectedSha = args.optString("expected_archive_sha256").trim().ifEmpty { null }
        if (expectedSha != null && !SHA256.matches(expectedSha)) return null
        return InstallRequest(
            path = path,
            replaceExisting = args.optBoolean("replace_existing", false),
            expectedReplacementId = expectedId,
            expectedArchiveSha256 = expectedSha,
        )
    }

    fun parseId(args: JSONObject): IdRequest? {
        val id = args.optString("id").trim()
        if (!PLUGIN_ID.matches(id)) return null
        return IdRequest(id)
    }

    fun parseEnable(args: JSONObject): EnableRequest? {
        val id = args.optString("id").trim()
        if (!PLUGIN_ID.matches(id)) return null
        if (!args.has("enabled")) return null
        return EnableRequest(id, args.optBoolean("enabled", true))
    }

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
