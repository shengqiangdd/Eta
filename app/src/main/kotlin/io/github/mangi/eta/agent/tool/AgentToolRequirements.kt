package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.context.PersonalSearchTools

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION, CALENDAR_READ, CALENDAR_WRITE }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
    val gui: Boolean = false,
    val freshObservation: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "get_current_context", "search_apps", "launch_app", "present_to_user", "open_uri", "browser_use", "web_search", "fetch_url",
            "observe_screen", "release_gui", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "inspect_app", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write", "todo_write", "goal",
            "tasks",
            "ask_user",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "terminal_job", "terminal_daemon", "working_directory", "read_file",
            "write_file", "list_directory", "read_image", "edit_file", "edit_files", "list_file_backups", "restore_file",
            "stat_file", "glob_files", "grep_files", "code_outline", "project_info",
            "virtual_display_list", "plugin_list", "plugin_enable", "plugin_reload",
            // 自审：没有 root 时仍能给出堆压力与权限缺口，崩溃域会明确标为 unavailable。
            "eta_self_audit",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_system_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
            // 虚拟屏：create/launch 走 `input -d` 与 `am start --display`，必须有 root；
            // release 要销毁自己创建的 display，同样需要 root。
            "virtual_display_create", "virtual_display_launch", "virtual_display_release",
            // ACP：代理进程经 `su -c` 在 Linux 环境里拉起，必须有 root。
            "acp_agent_list", "acp_delegate", "acp_session_status", "acp_session_close",
            // 插件：安装要解包 zip 并写进应用私有目录，按写文件同类处理。
            "plugin_install", "plugin_uninstall",
        )
        PersonalSearchTools.names.forEach { put(it, LocalToolRequirement(RootRequirement.REQUIRED, colorOs = true)) }
        AgentPhoneToolCatalog.entries.forEach { entry ->
            put(entry.name, LocalToolRequirement(entry.root, colorOs = entry.colorOs,
                systemAccess = if (entry.name.contains("calendar")) {
                    if (entry.write) ToolSystemAccess.CALENDAR_WRITE else ToolSystemAccess.CALENDAR_READ
                } else ToolSystemAccess.NONE))
        }
        put("search_calendar_events", LocalToolRequirement(RootRequirement.PARTIAL, systemAccess = ToolSystemAccess.CALENDAR_READ))
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        listOf(
            "observe_screen", "launch_app", "present_to_user", "open_uri", "open_system_panel",
            "wait", "wait_for_text", "wait_for_package", "set_clipboard", "get_clipboard",
        ).forEach { name -> put(name, getValue(name).copy(gui = true)) }
        listOf(
            "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
            "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text",
            "paste_text", "press_key",
        ).forEach { name -> put(name, getValue(name).copy(gui = true, freshObservation = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_system_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_system_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[PersonalSearchTools.canonical(name)]

    fun rootRequirement(name: String): RootRequirement =
        requireNotNull(find(name)) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun requiresGuiControl(name: String?): Boolean = name?.let(::find)?.gui == true

    fun requiresFreshObservation(name: String): Boolean = find(name)?.freshObservation == true

    fun hasForegroundIntentFallback(name: String?): Boolean = name == "set_alarm" || name == "set_timer"

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name) == RootRequirement.REQUIRED) return true
        // 虚拟屏上的动作必须走 `input -d <id>`，那是 shell 命令，没有 root 发不出去。
        if (arguments.has("display_id") && arguments.optLong("display_id", 0L) > 0L) return true
        return when (name) {
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            // 未登记的工具直接剔除，而不是抛异常：漏登记一处不该让**整轮目录构建**失败
            // （155 轮的 plugin_reload 就是这样让每个新会话都起不来的）。真正的把关交给
            // AgentToolCatalogRequirementTest 与 tools/preinstall-gate.sh。
            val requirement = find(name) ?: continue
            if (!rootAvailable && requirement.rootRequirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        if (function.getString("name") in AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) {
            properties?.optJSONObject("identity")?.put("enum", JSONArray().put("user"))
                ?.put("description", "显式选择宿主身份；当前可用 user（Eta App UID）。省略时沿用本次运行的默认身份。")
        }
        when (function.getString("name")) {
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；输入文本请使用 type_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
            // 该工具在无 root 时保留（PARTIAL：堆压力与权限缺口仍可查），但默认源码路径是 root 侧概念，
            // 不该出现在无 root 的 schema 里。§32.48：这条契约原本就在测试里守着，只是没人跑。
            "eta_self_audit" -> properties?.getJSONObject("source_root")?.put(
                "description",
                "可选。Eta 源码目录，用于 code 域；省略时自动探测。" +
                    "真机发布包通常没有源码，此时 code 域会记为 unavailable。",
            )
        }
    }
}
