package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文本输入、等待与系统操作工具 schema。 */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "type_text",
                    description = "向输入框写入文本，结果会读回校验。默认 mode=replace 把整个输入框替换为 text，text 为空即清空；" +
                        "mode=append 在当前光标处插入。指定 index 时写入该 editable 节点，不必先点击；不指定时写入当前输入焦点。" +
                        "submit=true 写入后按输入法回车，用于搜索、发送；结果 submitted=false 时说明文本已写入但未提交，改为点击发送按钮，不要重复输入。" +
                        "中文、emoji、长文本都直接传入，无需借助剪贴板。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("maxLength", 4_000))
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("replace").put("append"))
                                        .put("description", "replace 整体替换（默认），append 在光标处插入。"),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选，editable 节点 index；仅 replace 可用，必须同时传入同一次观察的 observation_id。"),
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "指定 index 时必传，与 index 来自同一次最近的观察。"),
                                )
                                .put(
                                    "submit",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "写入后按输入法回车提交，默认 false。"),
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put(
                                            "description",
                                            "可选，虚拟屏的 displayId（来自 virtual_display_create）。" +
                                                "指定后文本经 input -d 注入到该屏，不会写到主屏；" +
                                                "虚拟屏统一走这条注入通路，节点 index 仍只用于定位。",
                                        ),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "把文本写入系统剪贴板。适合准备粘贴长文本、中文、emoji 或特殊字符。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "读取系统剪贴板文本。Android 版本或后台限制可能导致读取失败。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "按系统按键或全局动作。BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS 优先走无障碍全局动作；ENTER 优先走输入法回车。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put(
                                            "description",
                                            "可选，虚拟屏的 displayId；指定后按键只送进该屏，不会作用到主屏。",
                                        ),
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "等待一段时间，让动画、网络加载或页面跳转完成。不要用它代替 wait_for_text/wait_for_package 的可验证等待。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "等待时长，100 到 30000，默认 1000。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "等待当前屏幕（可用 display_id 指定虚拟屏）出现指定文本或描述，适合点击后确认页面已到达、列表加载完成、弹窗出现。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最长等待时间，500 到 60000，默认 10000。")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否匹配 content-desc，默认 true。")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "匹配方式，默认 contains。")
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put(
                                            "description",
                                            "可选，虚拟屏的 displayId（来自 virtual_display_list）。" +
                                                "指定后在该屏的无障碍树里等待；不传则沿用最近一次观察所在的屏。",
                                        ),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "等待指定 Android package 到前台，适合 launch_app/open_uri 后确认目标应用已打开。虚拟屏用 display_id 指定，按任务栈判断前台。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最长等待时间，500 到 60000，默认 10000。")
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选；虚拟屏的 displayId。指定后按该屏任务栈判断前台，而不是主屏无障碍焦点。")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "打开通知栏或快捷设置面板。仅支持主屏；虚拟屏上会明确拒绝，不会把面板开在用户主屏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
