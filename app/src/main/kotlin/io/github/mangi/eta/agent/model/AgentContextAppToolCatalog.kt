package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。指定 display_id 时用 am start --display 启动到该虚拟屏，不会动主屏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选；虚拟屏的 displayId（来自 virtual_display_list）。给出时启动到该屏，需要 Root。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。该工具目前只作用于主屏；虚拟屏请用 launch_app(display_id=...) 或 virtual_display_launch。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "观察当前手机屏幕，默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id。结果里的 ui_nodes_shown 是真正带回的节点数：ui_nodes_truncated=true 表示已按上下文体积裁掉尾部（此时把 max_nodes 调到 120 也拿不到更多，应先 scroll 让目标进入视野再观察）；ui_tree_truncated=true 才是无障碍层的截断。不要仅因截断请求截图。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "是否附加当前屏幕原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60；实际带回数量见结果的 ui_nodes_shown，超出上下文体积预算时会裁剪尾部")
                                )
                                .put(
                                    "display_id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选；虚拟屏的 displayId（来自 virtual_display_list）。给出时观察该虚拟屏而不是主屏；虚拟屏会返回无障碍节点，拿不到节点时才回退截图。")
                                )
                                .put(
                                    "region",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "仅虚拟屏截图：裁剪并放大该区域，格式 \"x,y,w,h\"（归一化 0–999），例如 \"420,380,160,80\"。返回的坐标仍按整屏 normalized 换算，不是裁剪图坐标。")
                                )
                        )
                )
            )
    }
}
