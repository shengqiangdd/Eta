package io.github.mangi.eta.agent.device

import android.annotation.SuppressLint
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import org.json.JSONObject

/**
 * 仅 debuggable 包：让测试版在没有模型会话时也能创建/释放虚拟屏。
 *
 * 为什么需要：虚拟屏只能由 Agent 工具调用创建，而测试版（canary）通常没有模型凭据，
 * 于是「面板 / 预览 / 输入链路」这些纯 UI 与设备行为无法在测试版验证，只能反复装正式包。
 * 这里给测试版一个 intent 入口（`--es eta.debug.virtual_display create|release`），
 * 正式包被 `FLAG_DEBUGGABLE` 挡掉，不会生效。
 */
// controller 以 applicationContext 构造，并在 release() 中清空；入口本身只对 debuggable 包开放。
@SuppressLint("StaticFieldLeak")
internal object VirtualDisplayDebugHost {
    private var controller: VirtualDisplayController? = null

    fun create(context: Context): String {
        controller?.let { existing ->
            return "already: " + existing.list().joinToString { "${it.id}@${it.displayId}" }
        }
        val created = VirtualDisplayController(
            context = context.applicationContext,
            logger = AndroidAgentLogger,
        )
        // 调试入口也要遵守「释放前先清任务」：否则测试完 release 可能把屏上应用迁回主屏。
        val tasks = RootShellDeviceController(
            logger = AndroidAgentLogger,
            virtualDisplays = created,
        )
        created.taskCleaner = { displayId -> tasks.closeTasksOnDisplay(displayId).ok }
        return runCatching {
            val session = created.create(1080, 1920, 420, "debug panel")
            controller = created
            "created id=${session.id} display=${session.displayId}"
        }.getOrElse { error ->
            created.close()
            "failed: ${error.message}"
        }
    }

    fun release(): String {
        val existing = controller ?: return "no controller"
        controller = null
        val count = existing.list().size
        existing.close()
        return "released $count"
    }

    /**
     * 仅测试版：对 debug host 创建的虚拟屏跑一次真实的 observe / tap_element / wait_for_text，
     * 把结果写到应用外部目录的 vd-probe.txt。用来验证「虚拟屏 UI 树 + 节点动作」闭环，
     * 不需要模型凭据，也不影响正式包。
     */
    fun probe(context: Context, displayId: Int, action: String? = null): String {
        val existing = controller ?: return "no controller"
        if (existing.findByDisplayId(displayId) == null) return "unknown display $displayId"
        // 工具调用在后台线程执行；探针也必须离开主线程，否则 dispatchGesture 无法同步等待。
        Thread({
            val device = RootShellDeviceController(
                logger = AndroidAgentLogger,
                virtualDisplays = existing,
            )
            val report = StringBuilder()
            report.appendLine("display=$displayId")
            if (action == "scroll") {
                report.appendLine("scroll=" + device.scroll("down", "small", displayId))
                val observation = device.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = 40,
                    displayId = displayId,
                )
                report.appendLine("observe=${observation.content}")
            } else {
                val observation = device.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = 60,
                    displayId = displayId,
                )
                report.appendLine("observe=${observation.content}")
                val snapshot = observation.elementObservation
                if (snapshot == null) {
                    report.appendLine("result=NO_SNAPSHOT")
                } else {
                    val target = snapshot.nodes.firstOrNull { it.text.contains("WLAN", ignoreCase = true) }
                        ?: snapshot.nodes.firstOrNull { it.clickable && it.bounds.height() > 80 }
                    report.appendLine("target=${target?.index}/${target?.text}/${target?.clickable}")
                    val needle = target?.text?.takeIf { it.isNotBlank() }
                        ?: snapshot.nodes.firstOrNull { it.text.isNotBlank() }?.text
                        ?: ""
                    if (target != null) {
                        report.appendLine("tap=${device.tapElement(snapshot, target.index)}")
                        report.appendLine(
                            "wait=" + device.waitForText(
                                text = needle,
                                timeoutMs = 3_000,
                                includeDesc = true,
                                matchMode = "contains",
                                displayId = displayId,
                            ),
                        )
                    }
                }
            }
            val text = report.toString()
            runCatching {
                File(context.getExternalFilesDir(null), "vd-probe.txt").writeText(text)
            }
        }, "eta-vd-probe").start()
        return "probe started display=$displayId action=${action ?: "default"}"
    }
    /**
     * 仅测试版：直接走真实的 AgentLocalTools 执行器，验证工具层 dispatch 的 display 作用域。
     * 与 [probe] 的区别：probe 调控制器，本方法调工具名与参数，能抓住「dispatch 漏传 display_id」。
     */
    fun toolProbe(context: Context): String {
        Thread({
            val report = StringBuilder()
            val tools = AgentLocalTools(
                context = context.applicationContext,
                logger = AndroidAgentLogger,
                browserToolsEnabled = { false },
                terminalToolsEnabled = { false },
                deviceDirectToolsEnabled = { true },
                deviceSensitiveReadToolsEnabled = { true },
                deviceSensitiveActionToolsEnabled = { true },
                memoryToolsEnabled = { false },
                memoryWritable = false,
                // 调试探针显式声明 Root，避免 canary 尚未完成 RootAccess 检查时被工具门禁拦下。
                rootAvailable = { true },
            )
            fun call(name: String, args: JSONObject): JSONObject {
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "probe-$name",
                        name = name,
                        argumentsJson = args.toString(),
                    ),
                )
                report.appendLine("$name=${result.content}")
                return runCatching { JSONObject(result.content) }.getOrDefault(JSONObject())
            }
            try {
                val create = call(
                    "virtual_display_create",
                    JSONObject()
                        .put("width", 720)
                        .put("height", 1280)
                        .put("density_dpi", 320)
                        .put("purpose", "tool dispatch probe"),
                )
                val displayId = create.optInt("display_id", -1)
                val id = create.optString("id")
                report.appendLine("display_id=$displayId id=$id")
                if (displayId <= 0) {
                    report.appendLine("result=CREATE_FAILED")
                } else {
                    call(
                        "virtual_display_launch",
                        JSONObject().put("id", id).put("package_name", "com.android.settings"),
                    )
                    Thread.sleep(1_500L)
                    val observation = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 30),
                    )
                    val observationId = observation.optString("observation_id")
                    val nodes = observation.optJSONArray("ui_nodes")
                    var clickableIndex = -1
                    if (nodes != null) {
                        for (index in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(index) ?: continue
                            if (node.optBoolean("clickable")) {
                                clickableIndex = node.optInt("index", -1)
                                break
                            }
                        }
                    }
                    if (observationId.isNotBlank() && clickableIndex >= 0) {
                        call(
                            "tap_element",
                            JSONObject()
                                .put("observation_id", observationId)
                                .put("index", clickableIndex),
                        )
                    }
                    call(
                        "wait_for_package",
                        JSONObject()
                            .put("display_id", displayId)
                            .put("package_name", "com.android.settings")
                            .put("timeout_ms", 3_000),
                    )
                    call(
                        "scroll",
                        JSONObject()
                            .put("display_id", displayId)
                            .put("direction", "down")
                            .put("amount", "small"),
                    )
                    call(
                        "open_uri",
                        JSONObject().put("display_id", displayId).put("uri", "https://example.com"),
                    )
                    call(
                        "open_system_panel",
                        JSONObject().put("display_id", displayId).put("panel", "notifications"),
                    )
                    call("virtual_display_release", JSONObject().put("id", id))
                }
            } catch (error: Throwable) {
                report.appendLine("EXCEPTION=${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runCatching { tools.close() }
            }
            runCatching {
                File(context.getExternalFilesDir(null), "tool-probe.txt").writeText(report.toString())
            }
        }, "eta-tool-probe").start()
        return "tool probe started"
    }

    /**
     * 仅测试版：不用模型，直接验证「会话工作区隔离」与 `working_directory`。
     *
     * 两个 scope 等价于两个 conversationId：分别读写同一个相对路径，
     * 检查它们落在不同目录、互不可见；再用同名 scope 重建执行器，
     * 验证 cwd 会跟着会话持久化。结果写到外部目录的 workspace-probe.txt。
     */
    fun workspaceProbe(context: Context): String {
        Thread({
            val report = StringBuilder()
            val app = context.applicationContext
            fun open(scope: String) = AgentLocalTools(
                context = app,
                logger = AndroidAgentLogger,
                browserToolsEnabled = { false },
                terminalToolsEnabled = { true },
                deviceDirectToolsEnabled = { false },
                deviceSensitiveReadToolsEnabled = { false },
                deviceSensitiveActionToolsEnabled = { false },
                memoryToolsEnabled = { false },
                memoryWritable = false,
                rootAvailable = { false },
                workspaceScopeId = scope,
            )
            fun call(tools: AgentLocalTools, label: String, name: String, args: JSONObject): JSONObject {
                val result = tools.execute(
                    AgentModelClient.ToolCall(id = "probe-$label-$name", name = name, argumentsJson = args.toString()),
                )
                report.appendLine("$label.$name=${result.content}")
                return runCatching { JSONObject(result.content) }.getOrDefault(JSONObject())
            }
            fun pwd(tools: AgentLocalTools, label: String): String = call(
                tools,
                label,
                "terminal",
                JSONObject()
                    .put("action", "exec")
                    .put("command", "pwd")
                    .put("identity", "user")
                    .put("environment", "android"),
            ).optString("stdout").trim()

            try {
                var cwdA = ""
                val a1 = open("probe-session-a")
                try {
                    cwdA = call(a1, "a1", "working_directory", JSONObject()).optString("cwd")
                    report.appendLine("a1.cwd=$cwdA dir=${File(cwdA).isDirectory}")
                    call(a1, "a1", "write_file", JSONObject().put("path", "probe-scope.txt").put("content", "from-a"))
                    report.appendLine("a1.pwd=${pwd(a1, "a1")}")
                    call(a1, "a1", "working_directory", JSONObject().put("cwd", "sub"))
                    report.appendLine("a1.pwd_sub=${pwd(a1, "a1")}")
                } finally {
                    a1.close()
                }
                val a2 = open("probe-session-a")
                try {
                    val resumed = call(a2, "a2", "working_directory", JSONObject()).optString("cwd")
                    report.appendLine("a2.resumed=$resumed endsWithSub=${resumed.endsWith("/sub")}")
                    call(a2, "a2", "read_file", JSONObject().put("path", "$cwdA/probe-scope.txt"))
                    call(a2, "a2", "working_directory", JSONObject().put("cwd", cwdA))
                } finally {
                    a2.close()
                }
                val b1 = open("probe-session-b")
                try {
                    val cwdB = call(b1, "b1", "working_directory", JSONObject()).optString("cwd")
                    report.appendLine("b1.cwd=$cwdB dir=${File(cwdB).isDirectory} sameAsA=${cwdB == cwdA}")
                    call(b1, "b1", "stat_file", JSONObject().put("path", "probe-scope.txt"))
                    report.appendLine("b1.pwd=${pwd(b1, "b1")}")
                } finally {
                    b1.close()
                }
                val l1 = open("probe-session-a")
                try {
                    call(l1, "l1", "working_directory", JSONObject().put("environment", "linux"))
                    call(l1, "l1", "working_directory", JSONObject().put("environment", "linux").put("cwd", "eta-v2"))
                    call(l1, "l1", "working_directory", JSONObject().put("environment", "linux").put("cwd", "/etc"))
                } finally {
                    l1.close()
                }
                val l2 = open("probe-session-a")
                try {
                    call(l2, "l2", "working_directory", JSONObject().put("environment", "linux"))
                    call(l2, "l2", "working_directory", JSONObject().put("environment", "linux").put("cwd", "/workspace"))
                } finally {
                    l2.close()
                }
            } catch (error: Throwable) {
                report.appendLine("EXCEPTION=${error.javaClass.simpleName}: ${error.message}")
            }
            runCatching {
                File(context.getExternalFilesDir(null), "workspace-probe.txt").writeText(report.toString())
            }
        }, "eta-workspace-probe").start()
        return "workspace probe started"
    }
}
