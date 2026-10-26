package io.github.mangi.eta.agent.device

import android.annotation.SuppressLint
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.CoordinateSpaceErrors
import io.github.mangi.eta.agent.tool.DisplayTextErrors
import io.github.mangi.eta.agent.tool.ObservationScopeErrors
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
    fun stepProbe(context: Context, inputProbePackage: String? = null): String {
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
                            "method=${scrollTool.optString("method")} code=${scrollTool.optString("code")} " +
                            "region_from=${scrollTool.optString("region_from")}",
                    )
                    // STEP3c（fix158）：指定「面积较小的那个」可滚动节点来滚。
                    // 兜底以前会重新挑面积最大的节点，于是指了内层列表却滑了外层容器（且回 moved=true）。
                    // 现在应看到 fallback.region_from=preferred_node；若仍是 largest_scrollable，
                    // 说明「指东滑西」回来了。方向用 up + small：不把列表滑到底，也不改变后续步骤看到的内容。
                    val preferredState = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    val preferredObservationId = preferredState.optString("observation_id")
                    val nodesPreferred = preferredState.optJSONArray("ui_nodes")
                    var smallestScrollable = -1
                    var smallestArea = Long.MAX_VALUE
                    var scrollableCount = 0
                    if (nodesPreferred != null) {
                        for (i in 0 until nodesPreferred.length()) {
                            val node = nodesPreferred.optJSONObject(i) ?: continue
                            if (!node.optBoolean("scrollable")) continue
                            scrollableCount++
                            val area = areaOfNode(node)
                            if (area > 0 && area < smallestArea) {
                                smallestArea = area
                                smallestScrollable = node.optInt("index", -1)
                            }
                        }
                    }
                    if (preferredObservationId.isNotBlank() && scrollableCount >= 2 && smallestScrollable >= 0) {
                        // 设置页在滚动后仍可能有动画 / 异步重排，工具内部自动重试一次也可能仍是
                        // STALE_CONTENT（真机 STEP3c 就撞上了）。这里再补一轮：等页面停稳、重新观察、
                        // 重新取「最小可滚节点」，而不是把「没测到」当成「回归了」。
                        var preferredScroll = call(
                            "scroll_element",
                            JSONObject()
                                .put("observation_id", preferredObservationId)
                                .put("index", smallestScrollable)
                                .put("direction", "up")
                                .put("amount", "small"),
                        )
                        var staleRetry = 0
                        while (preferredScroll.optString("code") == "STALE_CONTENT" && staleRetry < 2) {
                            staleRetry++
                            call("wait", JSONObject().put("duration_ms", 1200))
                            val again = call(
                                "observe_screen",
                                JSONObject().put("display_id", displayId).put("max_nodes", 60),
                            )
                            val againId = again.optString("observation_id")
                            val againNodes = again.optJSONArray("ui_nodes")
                            var retryIndex = -1
                            var retryArea = Long.MAX_VALUE
                            if (againNodes != null) {
                                for (i in 0 until againNodes.length()) {
                                    val node = againNodes.optJSONObject(i) ?: continue
                                    if (!node.optBoolean("scrollable")) continue
                                    val area = areaOfNode(node)
                                    if (area > 0 && area < retryArea) {
                                        retryArea = area
                                        retryIndex = node.optInt("index", -1)
                                    }
                                }
                            }
                            if (againId.isBlank() || retryIndex < 0) break
                            smallestScrollable = retryIndex
                            preferredScroll = call(
                                "scroll_element",
                                JSONObject()
                                    .put("observation_id", againId)
                                    .put("index", retryIndex)
                                    .put("direction", "up")
                                    .put("amount", "small"),
                            )
                        }
                        val fallbackBody = preferredScroll.optJSONObject("fallback")
                        val regionFrom = fallbackBody?.optString("region_from").orEmpty()
                        report.appendLine(
                            "STEP3c index=$smallestScrollable scrollables=$scrollableCount " +
                                "ok=${preferredScroll.optBoolean("ok")} " +
                                "method=${preferredScroll.optString("method")} region_from=$regionFrom",
                        )
                        report.appendLine(
                            "STEP3c verdict=" + when {
                                regionFrom == "preferred_node" -> "OK"
                                regionFrom == "largest_scrollable" -> "FAIL_IGNORED_PREFERENCE"
                                // 页面一直在动：这一轮没测到，不是「指东滑西」回来了。
                                preferredScroll.optString("code") == "STALE_CONTENT" -> "INCONCLUSIVE_PAGE_STILL_MOVING"
                                !preferredScroll.optBoolean("ok") -> "FAIL_SCROLL_ERROR"
                                else -> "FAIL_NO_REGION_SOURCE"
                            },
                        )
                    } else {
                        report.appendLine(
                            "STEP3c skipped（可滚动节点 $scrollableCount 个，需要至少 2 个才能区分「指定的」与「最大的」）",
                        )
                    }
                    // STEP4：拦截后是否给出「可直接点」的替代目标（suggestion_index）。
                    // 注意：STEP3 / 3b 刚滚过一屏，前面那次 observe 的 observation_id 已经过期
                    // （真机上先报了 STALE_OBSERVATION）—— 这里必须重新观察一次再点。
                    // 只挑「中心落在**非开关**可交互节点上」的容器：即使判据哪天回归失效，
                    // 最多打开一个设置子页；绝不用会拨开关的目标（fix155 事故就是拨到了飞行模式）。
                    // 被拦下时不会发出任何手势，所以这一步本身不改设备状态。
                    // 刚滚过一屏：等动画落定再观察，否则「观察 → 点击」之间的树变化会让动作判过期
                    // （真机上先报 STALE_OBSERVATION、再报 STALE_CONTENT）。
                    call("wait", JSONObject().put("duration_ms", 900))
                    val scrollState = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    val nodes3 = scrollState.optJSONArray("ui_nodes")
                    val observationId3 = scrollState.optString("observation_id")
                    var blockedIndex = -1
                    var expectedIndex = -1
                    if (nodes3 != null) {
                        outer@ for (i in 0 until nodes3.length()) {
                            val container = nodes3.optJSONObject(i) ?: continue
                            if (container.optBoolean("clickable")) continue
                            val containerBox = boundsOf(container) ?: continue
                            val containerArea = areaOf(containerBox)
                            if (containerArea <= 0L) continue
                            val center = centerOf(container) ?: continue
                            var hit: JSONObject? = null
                            var hitArea = Long.MAX_VALUE
                            for (j in 0 until nodes3.length()) {
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
                    if (blockedIndex >= 0 && observationId3.isNotBlank()) {
                        val blockedTap = call(
                            "tap_element",
                            JSONObject().put("observation_id", observationId3).put("index", blockedIndex),
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
                                suggestion <= 0 || suggestion == blockedIndex -> "FAIL_NO_USABLE_SUGGESTION"
                                blockedTap.optBoolean("retryable", true) -> "FAIL_RETRYABLE"
                                // 索引可能因「观察 → 点击」之间的树变化而偏移：只要响应给了可用的其他节点就算成立，
                                // 偏移如实记下，不当作通过。
                                suggestion != expectedIndex -> "OK_INDEX_SHIFTED"
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
                        // STEP6：屏**释放之后**再用同一个 display_id —— 同样是「屏不对」，不能报成「参数错」。
                        // 故意用 observe_screen：它只读、不发任何手势，万一判据没拦住也只是多读一次空屏，
                        // 不会像 tap/scroll 那样有可能真的注入到主屏（那正是这条判据要防的事）。
                        // 若这里得到 DISPLAY_NO_VISIBLE_WINDOW，说明释放还没从注册表里消失（异步回收）：
                        // 同样不会误发动作，但如实记为「尚未立即失效」，不当作通过。
                        val releasedObs = call(
                            "observe_screen",
                            JSONObject()
                                .put("display_id", emptyDisplay)
                                .put("include_ui_tree", false),
                        )
                        val releasedCode = releasedObs.optString("code")
                        report.appendLine(
                            "STEP6 display=$emptyDisplay code=$releasedCode " +
                                "retryable=${releasedObs.optBoolean("retryable", true)}",
                        )
                        report.appendLine(
                            "STEP6 verdict=" + when (releasedCode) {
                                "DISPLAY_RELEASED" -> "OK"
                                "DISPLAY_NO_VISIBLE_WINDOW" -> "OK_NOT_YET_RELEASED"
                                "INVALID_ARGUMENT" -> "FAIL_REGRESSED_TO_ARGUMENT"
                                else -> "FAIL_UNEXPECTED"
                            },
                        )
                    } else {
                        report.appendLine("STEP5 skipped create_empty_display_failed")
                    }
                    // STEP7（fix159）：文本到底有没有写进去。
                    // `input -d N text ...` 在没有聚焦输入框时会静默丢弃文本、退出码却是 0；
                    // 现在响应里必须带 input_verification，而不是只靠「命令成功」就说 ok。
                    val inputState = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    var inputObservationId = inputState.optString("observation_id")
                    var editableIndex = -1
                    var focusedEditable = false
                    var searchIndex = -1
                    fun scanInputs(raw: org.json.JSONArray?) {
                        if (raw == null) return
                        for (i in 0 until raw.length()) {
                            val node = raw.optJSONObject(i) ?: continue
                            val index = node.optInt("index", -1)
                            if (node.optBoolean("editable")) {
                                if (editableIndex < 0) editableIndex = index
                                if (node.optBoolean("focused")) {
                                    editableIndex = index
                                    focusedEditable = true
                                    return
                                }
                            }
                            if (searchIndex < 0 && node.optString("desc").contains("搜索")) searchIndex = index
                        }
                    }
                    // 点搜索框后不一定立刻出输入框（真机第一次就是 1200ms 不够 / 页面还在加载），
                    // 最多再等两轮；并把当时看到的树存下来，免得「没找到」时无法判断是页面没到还是
                    // 页面到了但这个输入框在无障碍里不可编辑。
                    var treeDigest = ""
                    fun describeTree(raw: org.json.JSONArray?) {
                        if (raw == null) return
                        val parts = ArrayList<String>()
                        for (i in 0 until minOf(raw.length(), 10)) {
                            val node = raw.optJSONObject(i) ?: continue
                            parts += "${node.optInt("index")}:${node.optString("class")}" +
                                (if (node.optBoolean("editable")) ":editable" else "") +
                                (if (node.optBoolean("focused")) ":focused" else "") +
                                (if (node.optString("desc").isNotBlank()) ":${node.optString("desc").take(8)}" else "")
                        }
                        treeDigest = parts.joinToString(" ")
                    }
                    scanInputs(inputState.optJSONArray("ui_nodes"))
                    describeTree(inputState.optJSONArray("ui_nodes"))
                    // STEP7a：当前页面上没有任何可编辑节点时，`input text` 会静默丢弃文本。
                    // 工具必须说「不确定」，不能因为命令退出 0 就假装成功 —— 这段就是那条回归。
                    if (editableIndex < 0) {
                        val blind = call("type_text", JSONObject().put("text", "eta"))
                        val blindVerification = blind.optString("input_verification")
                        val blindNote = blind.optString("note")
                        report.appendLine(
                            "STEP7a ok=${blind.optBoolean("ok")} verification=$blindVerification " +
                                "note=${blindNote.take(36)}",
                        )
                        report.appendLine(
                            "STEP7a verdict=" + when {
                                blindVerification == "verified" -> "FAIL_CLAIMED_VERIFIED"
                                blindNote.isNotBlank() -> "OK_HONEST"
                                // 参数／屏校验就拒了（虚拟屏没窗口、已释放…）：压根没尝试输入，
                                // “没 verification”是诚实的，不是静默成功。
                                blind.optString("code").isNotEmpty() ->
                                    "OK_NOT_ATTEMPTED:" + blind.optString("code")
                                else -> "FAIL_SILENT"
                            },
                        )
                        // STEP7b（fix160）：同一块「没有输入框」的屏上清空，同样必须说「不确定」。
                        // 清空走 CTRL+A + DEL，与 input text 一样「退出 0 但可能什么都没做」。
                        val blindClear = call("clear_text", JSONObject().put("display_id", displayId))
                        report.appendLine(
                            "STEP7b clear verification=${blindClear.optString("input_verification")} " +
                                "mode=${blindClear.optString("input_mode")} " +
                                "note=${blindClear.optString("note").take(30)}",
                        )
                        report.appendLine(
                            "STEP7b verdict=" + when {
                                blindClear.optString("input_verification") == "unknown" &&
                                    blindClear.optString("note").isNotBlank() -> "OK_HONEST"
                                blindClear.optString("input_verification") == "verified" -> "FAIL_CLAIMED_CLEARED"
                                blindClear.optString("code").isNotEmpty() ->
                                    "OK_NOT_ATTEMPTED:" + blindClear.optString("code")
                                else -> "FAIL_SILENT"
                            },
                        )
                    }
                    if (editableIndex < 0 && searchIndex >= 0) {
                        // 设置首页没有输入框：点它的「搜索框」进搜索页（那里有真的输入框）。
                        call(
                            "tap_element",
                            JSONObject().put("observation_id", inputObservationId).put("index", searchIndex),
                        )
                        var attempt = 0
                        while (editableIndex < 0 && attempt < 3) {
                            attempt++
                            call("wait", JSONObject().put("duration_ms", if (attempt == 1) 1800 else 1200))
                            val searchState = call(
                                "observe_screen",
                                JSONObject().put("display_id", displayId).put("max_nodes", 60),
                            )
                            inputObservationId = searchState.optString("observation_id")
                            scanInputs(searchState.optJSONArray("ui_nodes"))
                            describeTree(searchState.optJSONArray("ui_nodes"))
                            if (editableIndex < 0 && attempt < 3) {
                                // 第一下常常只是聚焦 / 展开搜索栏，再点一次才会真正进搜索页。
                                call(
                                    "tap_element",
                                    JSONObject().put("observation_id", inputObservationId).put("index", searchIndex),
                                )
                            }
                        }
                    }
                    if (editableIndex >= 0 && !focusedEditable) {
                        // 没聚焦就点它一下（input text 只往焦点里打）。
                        call(
                            "tap_element",
                            JSONObject().put("observation_id", inputObservationId).put("index", editableIndex),
                        )
                        call("wait", JSONObject().put("duration_ms", 600))
                    }
                    if (editableIndex >= 0 && inputObservationId.isNotBlank()) {
                        val typed = call("type_text", JSONObject().put("text", "eta"))
                        val verification = typed.optString("input_verification")
                        report.appendLine(
                            "STEP7 index=$editableIndex focused=$focusedEditable ok=${typed.optBoolean("ok")} " +
                                "verification=$verification method=${typed.optString("method")} note=${typed.optString("note").take(30)}",
                        )
                        report.appendLine(
                            "STEP7 verdict=" + when (verification) {
                                "verified" -> "OK"
                                "not_applied" -> "FAIL_NOT_APPLIED"
                                "unknown" -> "OK_UNVERIFIABLE"
                                else -> "FAIL_NO_VERIFICATION_FIELD"
                            },
                        )
                    } else {
                        report.appendLine(
                            "STEP7 skipped（没有可编辑节点：editable=$editableIndex search=$searchIndex）",
                        )
                        report.appendLine("STEP7 tree=$treeDigest")
                    }
                    // STEP8（fix159 正向）：STEP7 只能证明「unknown + 诚实提示」，因为系统设置的
                    // 搜索框在无障碍里根本不是 editable。要证明 verified 分支真的能成立，必须换一个
                    // 真有 EditText 的页面：默认用便签的搜索框（它只做本地检索，输入不产生任何数据）。
                    // 目标包可用 `--es eta.debug.input_pkg <包名>` 换，换机型时不必改代码。
                    val probePkg = inputProbePackage?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "com.coloros.note"
                    val inputDisplay = call(
                        "virtual_display_create",
                        JSONObject()
                            .put("width", 720)
                            .put("height", 1280)
                            .put("density_dpi", 320)
                            .put("purpose", "text input probe")
                            .put("package_name", probePkg),
                    )
                    val inputDisplayId = inputDisplay.optInt("display_id", -1)
                    if (inputDisplayId <= 0) {
                        report.appendLine("STEP8 skipped create_failed pkg=$probePkg")
                    } else {
                        var inputState = call(
                            "observe_screen",
                            JSONObject().put("display_id", inputDisplayId).put("max_nodes", 60),
                        )
                        var inputSnapshot = inputState.optString("observation_id")
                        var editIndex = -1
                        var editFocused = false
                        fun scanEditable(raw: org.json.JSONArray?) {
                            if (raw == null) return
                            for (i in 0 until raw.length()) {
                                val node = raw.optJSONObject(i) ?: continue
                                if (!node.optBoolean("editable")) continue
                                if (node.optBoolean("focused")) {
                                    editIndex = node.optInt("index", -1)
                                    editFocused = true
                                    return
                                }
                                if (editIndex < 0) editIndex = node.optInt("index", -1)
                            }
                        }
                        fun searchButtonIn(raw: org.json.JSONArray?): Int {
                            if (raw == null) return -1
                            for (i in 0 until raw.length()) {
                                val node = raw.optJSONObject(i) ?: continue
                                if (!node.optBoolean("clickable")) continue
                                val label = node.optString("desc") + node.optString("text")
                                if (label.contains("搜索")) return node.optInt("index", -1)
                            }
                            return -1
                        }
                        scanEditable(inputState.optJSONArray("ui_nodes"))
                        // 首页没有输入框（便签就是列表页）：先点搜索入口，进去才有 EditText。
                        var inputRound = 0
                        while (editIndex < 0 && inputRound < 3) {
                            inputRound++
                            val searchButton = searchButtonIn(inputState.optJSONArray("ui_nodes"))
                            if (searchButton >= 0 && inputSnapshot.isNotBlank()) {
                                call(
                                    "tap_element",
                                    JSONObject()
                                        .put("observation_id", inputSnapshot)
                                        .put("index", searchButton),
                                )
                            }
                            call("wait", JSONObject().put("duration_ms", 1500))
                            inputState = call(
                                "observe_screen",
                                JSONObject().put("display_id", inputDisplayId).put("max_nodes", 60),
                            )
                            inputSnapshot = inputState.optString("observation_id")
                            scanEditable(inputState.optJSONArray("ui_nodes"))
                        }
                        if (editIndex >= 0 && !editFocused && inputSnapshot.isNotBlank()) {
                            call(
                                "tap_element",
                                JSONObject().put("observation_id", inputSnapshot).put("index", editIndex),
                            )
                            call("wait", JSONObject().put("duration_ms", 600))
                        }
                        if (editIndex >= 0) {
                            val typed = call(
                                "type_text",
                                JSONObject().put("text", "eta").put("display_id", inputDisplayId),
                            )
                            val verification = typed.optString("input_verification")
                            report.appendLine(
                                "STEP8 pkg=$probePkg display=$inputDisplayId index=$editIndex " +
                                    "focused=$editFocused ok=${typed.optBoolean("ok")} " +
                                    "verification=$verification method=${typed.optString("method")} " +
                                    "note=${typed.optString("note").take(30)}",
                            )
                            report.appendLine(
                                "STEP8 verdict=" + when (verification) {
                                    "verified" -> "OK"
                                    "not_applied" -> "FAIL_NOT_APPLIED"
                                    "unknown" -> "FAIL_STILL_UNVERIFIED"
                                    else -> "FAIL_NO_VERIFICATION_FIELD"
                                },
                            )
                            // STEP9（fix160）：清空与追加各自要有结论。
                            // 两者都走「退出码 0 但可能什么都没做」的命令，只看退出码等于从不验证。
                            fun editableTextIn(raw: org.json.JSONArray?): String? {
                                if (raw == null) return null
                                for (i in 0 until raw.length()) {
                                    val node = raw.optJSONObject(i) ?: continue
                                    if (!node.optBoolean("editable")) continue
                                    return node.optString("text")
                                }
                                return null
                            }
                            val cleared = call("clear_text", JSONObject().put("display_id", inputDisplayId))
                            val afterClear = call(
                                "observe_screen",
                                JSONObject().put("display_id", inputDisplayId).put("max_nodes", 40),
                            )
                            val afterClearText = editableTextIn(afterClear.optJSONArray("ui_nodes"))
                            report.appendLine(
                                "STEP9 clear verification=${cleared.optString("input_verification")} " +
                                    "mode=${cleared.optString("input_mode")} field=[${afterClearText ?: "?"}] " +
                                    "note=${cleared.optString("note").take(28)}",
                            )
                            report.appendLine(
                                "STEP9 verdict=" + when {
                                    cleared.optString("input_verification") == "verified" &&
                                        afterClearText.isNullOrEmpty() -> "OK"
                                    // 说清空了、页面里却还有字 —— 正是本轮要防的那种假成功。
                                    cleared.optString("input_verification") == "verified" -> "FAIL_CLAIMED_CLEARED"
                                    cleared.optString("input_verification") == "not_applied" -> "OK_DETECTED"
                                    else -> "OK_UNVERIFIABLE"
                                },
                            )
                            // 追加：先写入 et，再 append a；正确结果必须是 eta（不是 a）。
                            call("type_text", JSONObject().put("text", "et").put("display_id", inputDisplayId))
                            val baseState = call(
                                "observe_screen",
                                JSONObject().put("display_id", inputDisplayId).put("max_nodes", 40),
                            )
                            val baseText = editableTextIn(baseState.optJSONArray("ui_nodes"))
                            report.appendLine("STEP9b base=[$baseText]")
                            if (!baseText.isNullOrEmpty()) {
                                val appended = call(
                                    "type_text",
                                    JSONObject().put("text", "a").put("display_id", inputDisplayId).put("mode", "append"),
                                )
                                val afterAppend = call(
                                    "observe_screen",
                                    JSONObject().put("display_id", inputDisplayId).put("max_nodes", 40),
                                )
                                val afterAppendText = editableTextIn(afterAppend.optJSONArray("ui_nodes"))
                                val appendVerification = appended.optString("input_verification")
                                report.appendLine(
                                    "STEP9b append verification=$appendVerification " +
                                        "mode=${appended.optString("input_mode")} field=[$afterAppendText] " +
                                        "note=${appended.optString("note").take(28)}",
                                )
                                report.appendLine(
                                    "STEP9b verdict=" + when {
                                        appendVerification == "verified" &&
                                            afterAppendText == baseText + "a" -> "OK"
                                        // 判成 verified 但实际是整体替换：旧判据的漏洞。
                                        appendVerification == "verified" -> "FAIL_CLAIMED_APPEND"
                                        appendVerification == "not_applied" &&
                                            afterAppendText == "a" -> "OK_DETECTED_REPLACED"
                                        appendVerification == "not_applied" -> "OK_DETECTED"
                                        else -> "OK_UNVERIFIABLE"
                                    },
                                )
                            }
                            // STEP10（fix161）：坐标校验必须用**这块屏**的尺寸。
                            // 720x1280 的屏上报 (900,1200)：旧实现拿主屏 1216x2640 校验 → 放行 →
                            // 直到控制器才拦，错误码变成 TOOL_ERROR。现在应直接 INVALID_ARGUMENT，
                            // 且提示里写的就是 720x1280。
                            val outOfScreen = call(
                                "tap",
                                JSONObject()
                                    .put("x", 900)
                                    .put("y", 1200)
                                    .put("coordinate_space", "screen")
                                    .put("display_id", inputDisplayId),
                            )
                            val outMessage = outOfScreen.optString("message")
                            report.appendLine(
                                "STEP10 code=${outOfScreen.optString("code")} " +
                                    "message=${outMessage.take(40)}",
                            )
                            report.appendLine(
                                "STEP10 verdict=" + when {
                                    outOfScreen.optString("code") == "INVALID_ARGUMENT" &&
                                        outMessage.contains("720x1280") -> "OK"
                                    outMessage.contains("1216x2640") -> "FAIL_WRONG_SCREEN_SIZE"
                                    outOfScreen.optString("code") == "TOOL_ERROR" -> "FAIL_LEAKED_TO_CONTROLLER"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                        } else {
                            report.appendLine("STEP8 skipped pkg=$probePkg 三轮内没有可编辑节点")
                        }
                        // STEP11（fix162）：截图坐标系也得是「这块屏的截图」。
                        // 自己建一块屏并启动设置：STEP8 那块输入屏可能已把应用迁回主屏，
                        // 拿它测只会得到「没有可见窗口」，把真正要看的坐标系判断遮住。
                        val shotDisplay = call(
                            "virtual_display_create",
                            JSONObject()
                                .put("width", 720)
                                .put("height", 1280)
                                .put("density_dpi", 320)
                                .put("package_name", PROBE_PACKAGE)
                                .put("force", true)
                                .put("purpose", "fix162 screenshot coordinate space"),
                        )
                        val shotDisplayId = shotDisplay.optInt("display_id", -1)
                        report.appendLine("STEP11 display_id=$shotDisplayId")
                        if (shotDisplayId <= 0) {
                            report.appendLine("STEP11 skipped 建屏失败")
                        } else {
                            // 先对主屏带截图观察，再用 screenshot 坐标点这块虚拟屏。
                            // 旧实现拿主屏 1216x2640 当尺子，越界坐标被放行到控制器，报成 TOOL_ERROR。
                            call(
                                "observe_screen",
                                JSONObject().put("include_screenshot", true).put("max_nodes", 1),
                            )
                            val crossScreen = call(
                                "tap",
                                JSONObject()
                                    .put("x", 900)
                                    .put("y", 1200)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            val crossCode = crossScreen.optString("code")
                            val crossMessage = crossScreen.optString("message")
                            report.appendLine("STEP11 code=$crossCode message=${crossMessage.take(70)}")
                            report.appendLine(
                                "STEP11 verdict=" + when {
                                    crossCode == CoordinateSpaceErrors.SCREENSHOT_OTHER_DISPLAY &&
                                        crossMessage.contains("主屏") -> "OK"
                                    crossCode == "TOOL_ERROR" -> "FAIL_LEAKED_TO_CONTROLLER"
                                    crossScreen.optBoolean("ok") -> "FAIL_SILENTLY_ACCEPTED"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                            // STEP11c：同一把尺子也要用在 tap_area / swipe / long_press 上
                            // （它们共用 convertPoint，但这轮只端到端验过 tap）。
                            val areaTap = call(
                                "tap_area",
                                JSONObject()
                                    .put("x1", 10).put("y1", 10)
                                    .put("x2", 900).put("y2", 1200)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            val swipeCall = call(
                                "swipe",
                                JSONObject()
                                    .put("x1", 100).put("y1", 100)
                                    .put("x2", 900).put("y2", 1200)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            val longCall = call(
                                "long_press",
                                JSONObject()
                                    .put("x", 900).put("y", 1200)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            report.appendLine(
                                "STEP11c area=${areaTap.optString("code")} swipe=${swipeCall.optString("code")} " +
                                    "long=${longCall.optString("code")}",
                            )
                            report.appendLine(
                                "STEP11c verdict=" + if (
                                    listOf(areaTap, swipeCall, longCall).all {
                                        it.optString("code") == CoordinateSpaceErrors.SCREENSHOT_OTHER_DISPLAY
                                    }
                                ) "OK" else "FAIL_UNEXPECTED",
                            )
                            // STEP11b：同一块屏的整屏截图坐标必须照旧可用（别修成“截图全废”）。
                            val sameShot = call(
                                "observe_screen",
                                JSONObject()
                                    .put("display_id", shotDisplayId)
                                    .put("include_screenshot", true)
                                    .put("max_nodes", 1),
                            )
                            val sameContract = sameShot.optJSONObject("coordinate_contract")
                                ?.optJSONObject("screenshot")
                            val sameTap = call(
                                "tap",
                                JSONObject()
                                    .put("x", 20)
                                    .put("y", 12)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            report.appendLine(
                                "STEP11b screenshot=${sameContract?.optInt("width")}x${sameContract?.optInt("height")} " +
                                    "code=${sameTap.optString("code")} ok=${sameTap.optBoolean("ok")}",
                            )
                            report.appendLine(
                                "STEP11b verdict=" + when {
                                    sameTap.optBoolean("ok") -> "OK"
                                    sameTap.optString("code") ==
                                        CoordinateSpaceErrors.SCREENSHOT_OTHER_DISPLAY -> "FAIL_REJECTED_SAME_SCREEN"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                            // STEP12：region 裁剪后附图不再是整屏图，screenshot 坐标系必须失效，
                            // 否则 coordinate_contract 报的尺寸与附图不符，模型换算后静默点偏。
                            val croppedObs = call(
                                "observe_screen",
                                JSONObject()
                                    .put("display_id", shotDisplayId)
                                    .put("include_screenshot", true)
                                    .put("max_nodes", 1)
                                    .put("region", "420,380,160,80"),
                            )
                            val croppedContract = croppedObs.optJSONObject("coordinate_contract")
                            val croppedTap = call(
                                "tap",
                                JSONObject()
                                    .put("x", 10)
                                    .put("y", 10)
                                    .put("coordinate_space", "screenshot")
                                    .put("display_id", shotDisplayId),
                            )
                            report.appendLine(
                                "STEP12 contract.screenshot=${croppedContract?.opt("screenshot")} " +
                                    "note=${croppedContract?.optString("note")?.take(40)} " +
                                    "code=${croppedTap.optString("code")}",
                            )
                            report.appendLine(
                                "STEP12 verdict=" + when {
                                    croppedTap.optString("code") ==
                                        CoordinateSpaceErrors.SCREENSHOT_UNAVAILABLE -> "OK"
                                    croppedTap.optBoolean("ok") -> "FAIL_SILENTLY_ACCEPTED_CROPPED"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                            // STEP13（fix163）：节点动作必须落在「快照所属那块屏」。
                            // 快照取自虚拟屏、display_id 却写主屏（0）：旧实现只比 observation_id，
                            // 动作按快照自己的窗口执行 → 点在虚拟屏上却返回 ok（反方向更重：会真动用户主屏）。
                            val scopedObs = call(
                                "observe_screen",
                                JSONObject().put("display_id", shotDisplayId).put("max_nodes", 60),
                            )
                            val scopedNodes = scopedObs.optJSONArray("ui_nodes")
                            var scopedIndex = -1
                            for (i in 0 until (scopedNodes?.length() ?: 0)) {
                                val node = scopedNodes?.optJSONObject(i) ?: continue
                                if (node.optBoolean("clickable")) {
                                    scopedIndex = node.optInt("index", -1)
                                    break
                                }
                            }
                            val scopedTap = if (scopedIndex >= 0) {
                                call(
                                    "tap_element",
                                    JSONObject()
                                        .put("index", scopedIndex)
                                        .put("observation_id", scopedObs.optString("observation_id"))
                                        .put("display_id", 0),
                                )
                            } else {
                                JSONObject()
                            }
                            report.appendLine(
                                "STEP13 index=$scopedIndex code=${scopedTap.optString("code")} " +
                                    "ok=${scopedTap.optBoolean("ok")} " +
                                    "message=${scopedTap.optString("message").take(60)}",
                            )
                            report.appendLine(
                                "STEP13 verdict=" + when {
                                    scopedIndex < 0 -> "SKIPPED_NO_CLICKABLE_NODE"
                                    scopedTap.optString("code") == ObservationScopeErrors.OTHER_DISPLAY -> "OK"
                                    scopedTap.optBoolean("ok") -> "FAIL_SILENTLY_ACCEPTED"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                            // STEP13b：同一块屏的节点动作必须照旧可用（别把节点动作整体禁掉）。
                            // 上一步被拒后界面没变，同一快照与 index 仍成立。
                            val sameScopeTap = if (scopedIndex >= 0) {
                                call(
                                    "tap_element",
                                    JSONObject()
                                        .put("index", scopedIndex)
                                        .put("observation_id", scopedObs.optString("observation_id"))
                                        .put("display_id", shotDisplayId),
                                )
                            } else {
                                JSONObject()
                            }
                            report.appendLine(
                                "STEP13b code=${sameScopeTap.optString("code")} " +
                                    "ok=${sameScopeTap.optBoolean("ok")} " +
                                    "method=${sameScopeTap.optString("method")}",
                            )
                            report.appendLine(
                                "STEP13b verdict=" + when {
                                    scopedIndex < 0 -> "SKIPPED_NO_CLICKABLE_NODE"
                                    sameScopeTap.optBoolean("ok") -> "OK"
                                    sameScopeTap.optString("code") ==
                                        ObservationScopeErrors.OTHER_DISPLAY -> "FAIL_REJECTED_SAME_SCREEN"
                                    else -> "FAIL_UNEXPECTED"
                                },
                            )
                            // STEP14（fix164）：虚拟屏写入到底认不认 index。
                            // schema 写着「index 指定 editable 节点」，但虚拟屏分支直接走 input -d，
                            // index 被完全忽略 → 会静默写进当前焦点输入框（可能是完全另一处）。
                            val mainObs = call("observe_screen", JSONObject().put("max_nodes", 40))
                            val mainNodes = mainObs.optJSONArray("ui_nodes")
                            var mainIndex = -1
                            for (i in 0 until (mainNodes?.length() ?: 0)) {
                                val node = mainNodes?.optJSONObject(i) ?: continue
                                mainIndex = node.optInt("index", -1)
                                if (mainIndex >= 0) break
                            }
                            val searchObs = call(
                                "observe_screen",
                                JSONObject().put("display_id", shotDisplayId).put("max_nodes", 40),
                            )
                            val searchNodes = searchObs.optJSONArray("ui_nodes")
                            var searchIndex = -1
                            for (i in 0 until (searchNodes?.length() ?: 0)) {
                                val node = searchNodes?.optJSONObject(i) ?: continue
                                val label = node.optString("view_id") + node.optString("desc")
                                if (label.contains("searchView") || label.contains("搜索")) {
                                    searchIndex = node.optInt("index", -1)
                                    if (searchIndex >= 0) break
                                }
                            }
                            if (searchIndex >= 0) {
                                call(
                                    "tap_element",
                                    JSONObject()
                                        .put("index", searchIndex)
                                        .put("observation_id", searchObs.optString("observation_id"))
                                        .put("display_id", shotDisplayId),
                                )
                            }
                            val typedWithIndex = call(
                                "type_text",
                                JSONObject()
                                    .put("text", "fix164probe")
                                    .put("index", mainIndex)
                                    .put("observation_id", mainObs.optString("observation_id"))
                                    .put("display_id", shotDisplayId),
                            )
                            val afterType = call(
                                "observe_screen",
                                JSONObject().put("display_id", shotDisplayId).put("max_nodes", 40),
                            )
                            val leaked = afterType.optString("ui_nodes").isNotEmpty() &&
                                afterType.toString().contains("fix164probe")
                            report.appendLine(
                                "STEP14 mainIndex=$mainIndex searchIndex=$searchIndex " +
                                    "code=${typedWithIndex.optString("code")} ok=${typedWithIndex.optBoolean("ok")} " +
                                    "verification=${typedWithIndex.optString("input_verification")} leaked=$leaked",
                            )
                            report.appendLine(
                                "STEP14 verdict=" + when {
                                    mainIndex < 0 -> "SKIPPED_NO_NODE"
                                    typedWithIndex.optBoolean("ok") -> "FAIL_INDEX_SILENTLY_IGNORED"
                                    typedWithIndex.optString("code") ==
                                        DisplayTextErrors.INDEX_ON_VIRTUAL_DISPLAY -> "OK"
                                    else -> "OK_OTHER_REJECTION:" + typedWithIndex.optString("code")
                                },
                            )
                            call("virtual_display_release", JSONObject().put("id", shotDisplay.optString("id")))
                        }
                        call("virtual_display_release", JSONObject().put("id", inputDisplay.optString("id")))
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
