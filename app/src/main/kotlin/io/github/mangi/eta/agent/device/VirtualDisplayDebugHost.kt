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

    /**
     * 同一时刻只允许一个探针在跑。
     *
     * 为什么需要：`am start` 带 extras 时，冷启动会先在 onCreate 消费一次、
     * 随后 onNewIntent 又投递一次同一个 intent —— 两个线程各建一块虚拟屏、
     * 抢着写同一个报告文件，最后看到的是「后一个覆盖前一个」的残缺结论。
     */
    private val probeRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 探针用来启动的目标应用：系统设置在任何设备上都存在，且首屏有足够多的可点节点。 */
    private const val PROBE_PACKAGE = "com.android.settings"

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
    fun probe(context: Context, displayId: Int, action: String? = null, maxNodes: Int = 60): String {
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
            report.appendLine("max_nodes=$maxNodes")
            if (action == "scroll") {
                report.appendLine("scroll=" + device.scroll("down", "small", displayId))
                val observation = device.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = maxNodes,
                    displayId = displayId,
                )
                report.appendLine("observe=${observation.content}")
            } else {
                val observation = device.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = maxNodes,
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
        if (!probeRunning.compareAndSet(false, true)) return "tool probe already running"
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
                runCatching {
                    File(context.getExternalFilesDir(null), "tool-probe.txt").writeText(report.toString())
                }
                // 报告落盘之后再放开，下一个探针不会写到一半被覆盖。
                probeRunning.set(false)
            }
        }, "eta-tool-probe").start()
        return "tool probe started"
    }

    /**
     * 仅测试版：验证「降低虚拟屏步数」的三处改动，结果写 `step-probe.txt`。
     *
     * 三件事都走真实工具名与参数（不是直接调控制器），所以能抓到「工具层没接上」这类缺陷：
     *
     * 1. `virtual_display_create` 带 `package_name`：一次调用完成建屏 + 启动 + 首屏观察，
     *    结果里应有 `launched=true` 与 `first_observation.ui_nodes`；
     * 2. `tap_element` 点一个**不可点**节点：应回 `method=ROOT_COORDINATE_FALLBACK`
     *    （工具层自己兜底，模型不必再换算坐标补一次）；
     * 3. `scroll_element` 滚滚动容器：节点只支持手势时应回 `method=ROOT_SCROLL_FALLBACK`。
     */
    fun stepProbe(context: Context): String {
        if (!probeRunning.compareAndSet(false, true)) return "step probe already running"
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
                rootAvailable = { true },
            )
            fun call(name: String, args: JSONObject): JSONObject {
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "stepprobe-$name",
                        name = name,
                        argumentsJson = args.toString(),
                    ),
                )
                val json = runCatching { JSONObject(result.content) }.getOrDefault(JSONObject())
                report.appendLine("$name=").appendLine(result.content.take(1_500))
                return json
            }
            try {
                val create = call(
                    "virtual_display_create",
                    JSONObject()
                        .put("width", 720)
                        .put("height", 1280)
                        .put("density_dpi", 320)
                        .put("purpose", "step probe")
                        .put("package_name", PROBE_PACKAGE),
                )
                val displayId = create.optInt("display_id", -1)
                val id = create.optString("id")
                val first = create.optJSONObject("first_observation")
                report.appendLine(
                    "STEP1 launched=${create.optBoolean("launched")} display_id=$displayId " +
                        "first_nodes=${first?.optInt("ui_nodes_shown", 0)}",
                )
                var nodes = first?.optJSONArray("ui_nodes")
                var observationId = first?.optString("observation_id").orEmpty()
                // 首屏时序（已知）：真机上首次建屏有时只回 2 个容器节点（界面还在画）。
                // 探针不能因此判「没有可点目标」就结束 —— 模型会再 observe 一次，这里也照做。
                if (displayId > 0 && (nodes?.length() ?: 0) < 4) {
                    val again = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    val againNodes = again.optJSONArray("ui_nodes")
                    if ((againNodes?.length() ?: 0) > (nodes?.length() ?: 0)) {
                        nodes = againNodes
                        observationId = again.optString("observation_id")
                        report.appendLine("STEP1 retry nodes=${againNodes?.length() ?: 0} observation=$observationId")
                    }
                }
                // 找一个「不可点」的节点：线格式只在 clickable=true 时输出该键，所以 !optBoolean 就是不可点。
                //
                // 安全背景：探针启动的是系统设置，首页那把开关（含飞行模式）就在屏幕中部。
                // 早先版本盲点根容器的中心，真把飞行模式拨开了、设备当场断网。所以目标必须满足：
                // 中心点没压着更小的节点（否则盲点其实点到别人）、区域内没有开关、也没有可点控件
                // （盲点才既不会拨开关，也不会跳进下级页面）。
                // 找不到「完全无副作用」的就退一步：只要求区域内没有开关。
                val boundsPattern = Regex("-?\\d+")
                fun boundsOf(node: JSONObject): IntArray? {
                    val raw = node.optString("bounds")
                    if (!raw.contains('[')) return null
                    val numbers = boundsPattern.findAll(raw).map { it.value.toInt() }.toList()
                    return if (numbers.size >= 4) {
                        intArrayOf(numbers[0], numbers[1], numbers[2], numbers[3])
                    } else {
                        null
                    }
                }
                fun centerOf(node: JSONObject): IntArray? {
                    val center = node.optJSONArray("center") ?: return null
                    if (center.length() < 2) return null
                    return intArrayOf(center.optInt(0, -1), center.optInt(1, -1))
                }
                // 中心点上还压着别的节点 → 盲点实际会命中那个控件。
                // 上一版只按「在屏幕上方 1/5」筛，结果根 ScrollView 的中心也进了候选，
                // 直接把设置首页的快捷开关（含飞行模式）拨开了。这里再算一层覆盖。
                //
                // 只算**比目标更小**的节点：全屏根容器覆盖一切，它本身不是被点到的东西，
                // 否则每个节点都会被判成"被覆盖"，STEP2 永远只能跳过。
                fun areaOf(box: IntArray): Long = (box[2] - box[0]).toLong() * (box[3] - box[1])
                fun centerCoveredByOther(node: JSONObject): Boolean {
                    val index = node.optInt("index", -1)
                    val center = centerOf(node) ?: return true
                    if (center[0] < 0 || center[1] < 0) return true
                    val own = boundsOf(node) ?: return true
                    val ownArea = areaOf(own)
                    val total = nodes?.length() ?: 0
                    for (i in 0 until total) {
                        val other = nodes?.optJSONObject(i) ?: continue
                        if (other.optInt("index", -2) == index) continue
                        val box = boundsOf(other) ?: continue
                        if (areaOf(box) >= ownArea) continue
                        val covers = center[0] >= box[0] && center[0] < box[2] &&
                            center[1] >= box[1] && center[1] < box[3]
                        if (covers) return true
                    }
                    return false
                }
                fun areaOfNode(node: JSONObject): Long {
                    val box = boundsOf(node) ?: return 0L
                    return areaOf(box)
                }
                /** other 的中心是否落在 target 的范围内。 */
                fun centerInside(target: JSONObject, other: JSONObject): Boolean {
                    val box = boundsOf(target) ?: return false
                    val center = centerOf(other) ?: return false
                    return center[0] >= box[0] && center[0] < box[2] &&
                        center[1] >= box[1] && center[1] < box[3]
                }
                /** 目标区域内有没有满足条件的节点；只算比目标更小的（祖先容器不算）。 */
                fun containsMatching(target: JSONObject, predicate: (JSONObject) -> Boolean): Boolean {
                    val ownArea = areaOfNode(target)
                    if (ownArea <= 0L) return true
                    val total = nodes?.length() ?: 0
                    for (i in 0 until total) {
                        val other = nodes?.optJSONObject(i) ?: continue
                        if (!predicate(other)) continue
                        if (areaOfNode(other) >= ownArea) continue
                        if (centerInside(target, other)) return true
                    }
                    return false
                }
                // 按面积降序挑：面积越大越可能是纯容器（没有 ACTION_CLICK），
                // 才会真正走到「坐标兜底」这条要验证的路径；小 TextView 往往能直接 click 成功。
                fun pickTarget(): Int? {
                    var best: Int? = null
                    var bestArea = 0L
                    val total = nodes?.length() ?: 0
                    for (i in 0 until total) {
                        val node = nodes?.optJSONObject(i) ?: continue
                        val index = node.optInt("index", -1)
                        if (index < 0) continue
                        if (node.optBoolean("clickable")) continue
                        if (!node.has("bounds")) continue
                        if (centerCoveredByOther(node)) continue
                        // 区域内有开关 → 盲点可能直接拨动它，绝不选。
                        if (containsMatching(node) { other -> other.has("checked") }) continue
                        val area = areaOfNode(node)
                        if (area > bestArea) {
                            best = index
                            bestArea = area
                        }
                    }
                    return best
                }
                val nonClickable = pickTarget()
                val readyNodes = nodes?.length() ?: 0
                var tapAfterPackage = ""
                if (readyNodes < 4) {
                    // 树太小说明界面还没画完：此时任何「中心点上没东西」都不作数，不乱点。
                    report.appendLine("STEP2 skipped（首屏只有 $readyNodes 个节点，未就绪）observation=$observationId")
                } else if (displayId > 0 && observationId.isNotBlank() && nonClickable != null) {
                    val tap = call(
                        "tap_element",
                        JSONObject().put("observation_id", observationId).put("index", nonClickable),
                    )
                    tapAfterPackage = tap.optJSONObject("after")?.optString("package").orEmpty()
                    report.appendLine(
                        "STEP2 index=$nonClickable ok=${tap.optBoolean("ok")} " +
                            "method=${tap.optString("method")} code=${tap.optString("code")} " +
                            "after_package=$tapAfterPackage",
                    )
                } else {
                    report.appendLine(
                        "STEP2 skipped（没有既不可点、区域内又无开关的节点）" +
                            "observation=$observationId index=$nonClickable",
                    )
                }
                if (displayId > 0 && tapAfterPackage.isNotEmpty() && tapAfterPackage != PROBE_PACKAGE) {
                    // 只在下级页面才退回来。无条件 BACK 会把设置整个退到后台 ——
                    // 那一步之后 STEP3 找不到可滚节点、STEP3b 直接 INVALID_ARGUMENT（真机踩过）。
                    call("press_key", JSONObject().put("button", "BACK").put("display_id", displayId))
                }
                if (displayId > 0) {
                    val observation = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    val observationId2 = observation.optString("observation_id")
                    val nodes2 = observation.optJSONArray("ui_nodes")
                    var scrollable = -1
                    if (nodes2 != null) {
                        for (i in 0 until nodes2.length()) {
                            val node = nodes2.optJSONObject(i) ?: continue
                            if (node.optBoolean("scrollable")) {
                                scrollable = node.optInt("index", -1)
                                break
                            }
                        }
                    }
                    if (observationId2.isNotBlank() && scrollable >= 0) {
                        val scrolled = call(
                            "scroll_element",
                            JSONObject()
                                .put("observation_id", observationId2)
                                .put("index", scrollable)
                                .put("direction", "down")
                                .put("amount", "small"),
                        )
                        report.appendLine(
                            "STEP3 index=$scrollable ok=${scrolled.optBoolean("ok")} " +
                                "method=${scrolled.optString("method")} code=${scrolled.optString("code")}",
                        )
                    } else {
                        report.appendLine("STEP3 skipped observation=$observationId2 scrollable=$scrollable")
                    }
                    // STEP3b：同状态下直接调 `scroll` 工具作对照。
                    // scroll_element 的兜底内部走的就是它；两边结果不一致时，问题一定在「拼接」而不是滑动本身。
                    val scrollTool = call(
                        "scroll",
                        JSONObject().put("display_id", displayId).put("direction", "down").put("amount", "small"),
                    )
                    report.appendLine(
                        "STEP3b scroll ok=${scrollTool.optBoolean("ok")} " +
                            "method=${scrollTool.optString("method")} code=${scrollTool.optString("code")}",
                    )
                    // STEP4：拦截后是否给出「可直接点」的替代目标（suggestion_index）。
                    // 只挑「中心落在**非开关**可交互节点上」的容器：即使判据哪天回归失效，
                    // 最多打开一个设置子页；绝不用会拨开关的目标（fix155 事故就是拨到了飞行模式）。
                    // 被拦下时不会发出任何手势，所以这一步本身不改设备状态。
                    var blockedIndex = -1
                    var expectedIndex = -1
                    if (nodes2 != null) {
                        outer@ for (i in 0 until nodes2.length()) {
                            val container = nodes2.optJSONObject(i) ?: continue
                            if (container.optBoolean("clickable")) continue
                            val containerBox = boundsOf(container) ?: continue
                            val containerArea = areaOf(containerBox)
                            if (containerArea <= 0L) continue
                            val center = centerOf(container) ?: continue
                            var hit: JSONObject? = null
                            var hitArea = Long.MAX_VALUE
                            for (j in 0 until nodes2.length()) {
                                val other = nodes2.optJSONObject(j) ?: continue
                                if (!other.optBoolean("clickable")) continue
                                // 开关一律跳过：拨动它就是改设备状态。
                                if (other.has("checked")) continue
                                val otherBox = boundsOf(other) ?: continue
                                val otherArea = areaOf(otherBox)
                                if (otherArea <= 0L || otherArea >= containerArea) continue
                                if (center[0] < otherBox[0] || center[0] >= otherBox[2]) continue
                                if (center[1] < otherBox[1] || center[1] >= otherBox[3]) continue
                                if (otherArea < hitArea) {
                                    hit = other
                                    hitArea = otherArea
                                }
                            }
                            val target = hit ?: continue
                            blockedIndex = container.optInt("index", -1)
                            expectedIndex = target.optInt("index", -1)
                            if (blockedIndex >= 0 && expectedIndex >= 0) break@outer
                        }
                    }
                    if (blockedIndex >= 0 && observationId2.isNotBlank()) {
                        val blockedTap = call(
                            "tap_element",
                            JSONObject().put("observation_id", observationId2).put("index", blockedIndex),
                        )
                        val code = blockedTap.optString("code")
                        val suggestion = blockedTap.optInt("suggestion_index", -1)
                        report.appendLine(
                            "STEP4 index=$blockedIndex expected=$expectedIndex code=$code " +
                                "blocked_by=${blockedTap.optString("blocked_by")} suggestion=$suggestion " +
                                "retryable=${blockedTap.optBoolean("retryable", true)} " +
                                "ok=${blockedTap.optBoolean("ok")}",
                        )
                        report.appendLine(
                            "STEP4 verdict=" + when {
                                code != "COORDINATE_FALLBACK_UNSAFE_TARGET" -> "FAIL_NOT_BLOCKED"
                                suggestion != expectedIndex -> "FAIL_SUGGESTION_MISMATCH"
                                blockedTap.optBoolean("retryable", true) -> "FAIL_RETRYABLE"
                                else -> "OK"
                            },
                        )
                    } else {
                        report.appendLine("STEP4 skipped（没有「中心落在非开关可交互节点上」的容器）")
                    }
                    // STEP5：屏上没有可见窗口时的错误码。
                    // 回归点：以前这种情况被 catch 成 INVALID_ARGUMENT，模型会以为参数写错了去改参数重试；
                    // 现在应是 DISPLAY_NO_VISIBLE_WINDOW（且 retryable=false）。
                    val empty = call(
                        "virtual_display_create",
                        JSONObject()
                            .put("width", 480)
                            .put("height", 800)
                            .put("density_dpi", 320)
                            .put("purpose", "empty display probe"),
                    )
                    val emptyDisplay = empty.optInt("display_id", -1)
                    if (emptyDisplay > 0) {
                        val emptyScroll = call(
                            "scroll",
                            JSONObject().put("display_id", emptyDisplay).put("direction", "down"),
                        )
                        report.appendLine(
                            "STEP5 display=$emptyDisplay code=${emptyScroll.optString("code")} " +
                                "retryable=${emptyScroll.optBoolean("retryable", true)}",
                        )
                        report.appendLine(
                            "STEP5 verdict=" +
                                if (emptyScroll.optString("code") == "DISPLAY_NO_VISIBLE_WINDOW") "OK" else "FAIL",
                        )
                        call("virtual_display_release", JSONObject().put("id", empty.optString("id")))
                    } else {
                        report.appendLine("STEP5 skipped create_empty_display_failed")
                    }
                    call("virtual_display_release", JSONObject().put("id", id))
                }
            } catch (error: Throwable) {
                report.appendLine("EXCEPTION=${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runCatching { tools.close() }
                runCatching {
                    File(context.getExternalFilesDir(null), "step-probe.txt").writeText(report.toString())
                }
                // 报告落盘之后再放开，下一个探针不会写到一半被覆盖。
                probeRunning.set(false)
            }
        }, "eta-step-probe").start()
        return "step probe started"
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
