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

    /**
     * 报告自证：这份报告是哪一份 APK 跑出来的。
     *
     * 为什么需要：真机踩过一次「`pm install` 报 Success，装的却是另一个包」—— canary 一直是旧包，
     * 探针拿**旧代码**跑出「新结论」，报告字段格式甚至都对不上，只能靠人肉看格式才发现。
     * 报告第一行写上包名 / 版本 / base.apk 字节数，事后任何一份报告都能核对，
     * `tools/probe.sh` 也会拿这里的 apk_size 与刚装的 APK 比对。
     */
    private fun buildMarker(context: Context): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val sourceDir = info?.applicationInfo?.sourceDir.orEmpty()
        val size = if (sourceDir.isEmpty()) 0L else runCatching { File(sourceDir).length() }.getOrDefault(0L)
        return buildString {
            append("BUILD apk_size=").append(size)
            append(" package=").append(context.packageName)
            append(" versionName=").append(info?.versionName.orEmpty())
            append(" versionCode=").append(info?.longVersionCode ?: 0L)
            append(" apk=").append(sourceDir)
        }
    }

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
            report.appendLine(buildMarker(context))
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
                            // STEP15~18 已抽成独立函数：全量探针照常调用，也可用 scope_probe 单独跑。
                            runDisplayScopeSteps(
                                call = { toolName, toolArgs -> call(toolName, toolArgs) },
                                report = report,
                                // 原先这里直接写 `id`（virtual_display_create 返回的字符串 id）当
                                // display_id 用，数值恰好一致才没暴露；抽取后统一成数字 id。
                                settingsDisplayId = displayId,
                                inputDisplay = inputDisplay,
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
     * STEP15~18 单独一段：屏作用域（fix165）、按键落点（fix166）、
     * 节点手势三件套的同屏校验（fix167）、参数名提示（fix168）。
     *
     * 抽出来是为了「只验这几段」时不必重跑 STEP1~14（整套约 3 分钟，这段约 30 秒），
     * 入口是 `--es eta.debug.virtual_display scope_probe`。
     * 全量探针仍在 STEP14 之后调用它，行为不变。
     */
    private fun runDisplayScopeSteps(
        call: (String, JSONObject) -> JSONObject,
        report: StringBuilder,
        settingsDisplayId: Int,
        inputDisplay: JSONObject,
    ) {

        // STEP15（fix165）：动作在哪块屏，after 就必须是哪块屏的反馈。
        // 修复前：after 跟着「最近一次观察的屏」走 —— 快照在 A 屏、动作在 B 屏时，
        // 反馈来自 A 屏，而 after 里连屏信息都没有，模型完全无从察觉。
        val scopeObs = call(
            "observe_screen",
            JSONObject().put("display_id", settingsDisplayId).put("max_nodes", 20),
        )
        val crossDisplayId = inputDisplay.optInt("display_id", -1)
        fun crossTapAction() = call(
            "tap",
            JSONObject()
                .put("display_id", crossDisplayId)
                .put("coordinate_space", "normalized")
                .put("x", 500)
                .put("y", 900),
        )
        var crossTap = crossTapAction()
        if (crossTap.optString("code") == "DISPLAY_NO_VISIBLE_WINDOW") {
            // 全量探针里 STEP8 会在这块屏上做输入测试，应用可能已被系统迁回主屏，
            // 到 STEP15 这块屏上就没窗口了（实测），tap 会被正确拒掉、整段退化成
            // NOT_ATTEMPTED。这里兜一次：按原包重新拉起再点，否则这一段等于没跑。
            val relaunch = call(
                "virtual_display_launch",
                JSONObject()
                    .put("id", inputDisplay.optString("id"))
                    .put("package_name", inputDisplay.optString("package_name"))
                    .put("force", true),
            )
            report.appendLine(
                "STEP15 relaunch ok=${relaunch.optBoolean("ok")} " +
                    "launched=${relaunch.optBoolean("launched")} " +
                    "code=${relaunch.optString("code")}",
            )
            Thread.sleep(1_500L)
            crossTap = crossTapAction()
        }
        val crossAfter = crossTap.optJSONObject("after")
        val crossAfterDisplay = crossAfter?.optJSONObject("display")
        val crossAfterId = crossAfterDisplay?.optInt("id", -1) ?: -1
        val crossDiffAvailable = crossAfter
            ?.optJSONObject("changes")?.optBoolean("available", true) ?: true
        report.appendLine(
            "STEP15 snapshot=${scopeObs.optString("observation_id")} session=$settingsDisplayId " +
                "actionDisplay=$crossDisplayId " +
                "code=${crossTap.optString("code")} ok=${crossTap.optBoolean("ok")} " +
                "afterScope=${crossAfterDisplay?.optString("scope").orEmpty()} " +
                "afterId=$crossAfterId package=${crossAfter?.optString("package").orEmpty()} " +
                "diffAvailable=$crossDiffAvailable",
        )
        report.appendLine(
            "STEP15 verdict=" + when {
                !crossTap.optBoolean("ok") ->
                    "OK_NOT_ATTEMPTED:" + crossTap.optString("code")
                crossAfterDisplay == null -> "FAIL_AFTER_WITHOUT_DISPLAY"
                crossAfterId != crossDisplayId -> "FAIL_AFTER_OTHER_DISPLAY"
                crossDiffAvailable -> "FAIL_CROSS_DISPLAY_DIFF"
                else -> "OK"
            },
        )
        // STEP16（fix166）：按键落点必须由 display_id 决定，并在响应里写明。
        // 修复前：display_id=0 被归一成 null，走无障碍 globalAction（作用于焦点窗口），
        // 而返回只有 executor=accessibility，模型无从核对。
        val keyOnDisplay = call(
            "press_key",
            JSONObject().put("display_id", crossDisplayId).put("button", "BACK"),
        )
        val keyLanded = keyOnDisplay.optJSONObject("landed")
        report.appendLine(
            "STEP16a display=$crossDisplayId code=${keyOnDisplay.optString("code")} " +
                "ok=${keyOnDisplay.optBoolean("ok")} " +
                "landedScope=${keyLanded?.optString("scope").orEmpty()} " +
                "landedId=${keyLanded?.optInt("id", -1)} " +
                "route=${keyLanded?.optString("route").orEmpty()}",
        )
        report.appendLine(
            "STEP16a verdict=" + when {
                !keyOnDisplay.optBoolean("ok") ->
                    "OK_NOT_ATTEMPTED:" + keyOnDisplay.optString("code")
                keyLanded == null -> "FAIL_NO_LANDED"
                keyLanded.optString("scope") != "virtual" ||
                    keyLanded.optInt("id", -1) != crossDisplayId ->
                    "FAIL_WRONG_LANDING"
                else -> "OK"
            },
        )
        // 显式主屏：必须走 input -d 0，而不是交给焦点漂移。
        // 注意：这会让主屏当前的前台应用退到后台（探针故意放在最后）。
        val keyOnMain = call(
            "press_key",
            JSONObject().put("display_id", 0).put("button", "BACK"),
        )
        val mainLanded = keyOnMain.optJSONObject("landed")
        report.appendLine(
            "STEP16b code=${keyOnMain.optString("code")} " +
                "ok=${keyOnMain.optBoolean("ok")} " +
                "landedScope=${mainLanded?.optString("scope").orEmpty()} " +
                "route=${mainLanded?.optString("route").orEmpty()}",
        )
        report.appendLine(
            "STEP16b verdict=" + when {
                !keyOnMain.optBoolean("ok") ->
                    "OK_NOT_ATTEMPTED:" + keyOnMain.optString("code")
                mainLanded == null -> "FAIL_NO_LANDED"
                mainLanded.optString("scope") != "main" -> "FAIL_WRONG_LANDING"
                else -> "OK"
            },
        )
        // STEP17（fix167）：节点类手势三件套对 display_id 必须一样严。
        // 修复前 scroll_element 既不解析 display_id、也不查那块屏还在不在：
        // 传 display_id=0（以为在主屏滚动）时参数被默默丢掉，动作仍在快照所属的
        // 虚拟屏上执行、结果照旧 ok —— 与 fix163 同一个病，只漏了这一个工具。
        val scrollScope = call(
            "observe_screen",
            JSONObject().put("display_id", crossDisplayId).put("max_nodes", 20),
        )
        val scrollScopeId = scrollScope.optString("observation_id")
        val wrongScopeScroll = if (scrollScopeId.isBlank()) {
            JSONObject().put("code", "NO_SNAPSHOT")
        } else {
            call(
                "scroll_element",
                JSONObject()
                    .put("observation_id", scrollScopeId)
                    .put("index", 0)
                    .put("direction", "down")
                    .put("display_id", 0),
            )
        }
        report.appendLine(
            "STEP17a snapshot=$scrollScopeId display=$crossDisplayId " +
                "code=${wrongScopeScroll.optString("code")} " +
                "ok=${wrongScopeScroll.optBoolean("ok")}",
        )
        report.appendLine(
            "STEP17a verdict=" + when {
                scrollScopeId.isBlank() -> "SKIPPED_NO_SNAPSHOT"
                wrongScopeScroll.optBoolean("ok") -> "FAIL_DISPLAY_ID_IGNORED"
                wrongScopeScroll.optString("code") ==
                    "OBSERVATION_OTHER_DISPLAY" -> "OK"
                else -> "FAIL_OTHER_CODE:" + wrongScopeScroll.optString("code")
            },
        )
        // 17b：不传 display_id 的正常路径不能被这次收紧误伤。
        // 正向样本优先用虚拟屏上的系统设置列表（不受 agent 自己界面影响）。
        // 但全量探针跑到这里时，设置可能已被前面几步退到后台（实测 DISPLAY_NO_VISIBLE_WINDOW），
        // 所以样本不可用就退回主屏；主屏又不稳定（STALE_NODE）时记 SKIPPED，不记 FAIL。
        fun firstScrollableIndex(state: JSONObject): Int {
            val nodes = state.optJSONArray("ui_nodes") ?: return -1
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                if (node.optBoolean("scrollable")) return node.optInt("index", -1)
            }
            return -1
        }
        fun sampleOn(display: Int): Triple<String, Int, JSONObject> {
            val state = call(
                "observe_screen",
                JSONObject().put("display_id", display).put("max_nodes", 60),
            )
            val observation = state.optString("observation_id")
            val scrollable = firstScrollableIndex(state)
            val result =
                if (observation.isBlank() || scrollable < 0) {
                    JSONObject().put("code", "NO_SCROLLABLE")
                } else {
                    call(
                        "scroll_element",
                        JSONObject()
                            .put("observation_id", observation)
                            .put("index", scrollable)
                            .put("direction", "down")
                            .put("amount", "small"),
                    )
                }
            return Triple(observation, scrollable, result)
        }

        var sameScopeDisplay = settingsDisplayId
        var sameScopeSample = sampleOn(settingsDisplayId)
        if (!sameScopeSample.third.optBoolean("ok") && settingsDisplayId != 0) {
            report.appendLine(
                "STEP17b fallback_to_main reason=" + sameScopeSample.third.optString("code"),
            )
            sameScopeDisplay = 0
            sameScopeSample = sampleOn(0)
        }
        val sameScopeId = sameScopeSample.first
        val sameScopeScrollable = sameScopeSample.second
        val sameScopeScroll = sameScopeSample.third
        val sameScopeCode = sameScopeScroll.optString("code")
        report.appendLine(
            "STEP17b display=$sameScopeDisplay scrollable=$sameScopeScrollable " +
                "code=$sameScopeCode ok=${sameScopeScroll.optBoolean("ok")} " +
                "method=${sameScopeScroll.optString("method")}",
        )
        report.appendLine(
            "STEP17b verdict=" + when {
                sameScopeCode == "OBSERVATION_OTHER_DISPLAY" ||
                    sameScopeCode == "DISPLAY_RELEASED" -> "FAIL_NORMAL_PATH_BROKEN"
                sameScopeId.isBlank() || sameScopeScrollable < 0 -> "SKIPPED_NO_SCROLLABLE"
                sameScopeScroll.optBoolean("ok") -> "OK"
                sameScopeDisplay == 0 -> "SKIPPED_MAIN_UNSTABLE:" + sameScopeCode
                else -> "FAIL_SCROLL:" + sameScopeCode
            },
        )
        // STEP18（fix168）：参数名写错不能被静默吞掉。
        // `observe_screen` 的声明名是 include_screenshot；模型很爱写 includeScreenshot。
        // 这个工具只读，写错名字除了多一张截图没有任何副作用，适合做探针。
        val typoObs = call(
            "observe_screen",
            JSONObject().put("max_nodes", 20).put("includeScreenshot", true),
        )
        val typoHints = typoObs.optJSONArray("arg_hints")
        val typoFirst = typoHints?.optString(0).orEmpty()
        report.appendLine(
            "STEP18 ok=${typoObs.optBoolean("ok")} " +
                "hints=${typoHints?.length() ?: 0} first=$typoFirst",
        )
        report.appendLine(
            "STEP18 verdict=" + when {
                typoHints == null || typoHints.length() == 0 -> "FAIL_NO_HINT"
                typoFirst.contains("include_screenshot") -> "OK"
                else -> "OK_OTHER:" + typoFirst.take(40)
            },
        )
        // 反方向：合法参数一个都不该冒出提示（误报比漏报更烦人）。
        val cleanObs = call("observe_screen", JSONObject().put("max_nodes", 10))
        report.appendLine(
            "STEP18b hints=${cleanObs.optJSONArray("arg_hints")?.length() ?: 0}",
        )
        report.appendLine(
            "STEP18b verdict=" + when {
                cleanObs.optJSONArray("arg_hints") == null -> "OK"
                else -> "FAIL_SPURIOUS_HINT"
            },
        )
        // 18c（fix170）：device 目录（一百多个工具）原先不在参数索引里，传错名字连提示都没有。
        // `get_device_state` 只读、target 必填，正好当样本：带一个错名 targetName，
        // 期望「动作真的执行了 + 提示叫 target」，而不是沉默。
        val deviceTypo = call(
            "get_device_state",
            JSONObject().put("target", "wifi").put("targetName", "wifi"),
        )
        val deviceTypoHints = deviceTypo.optJSONArray("arg_hints")
        val deviceTypoFirst = deviceTypoHints?.optString(0).orEmpty()
        report.appendLine(
            "STEP18c ok=${deviceTypo.optBoolean("ok")} enabled=${deviceTypo.opt("enabled")} " +
                "hints=${deviceTypoHints?.length() ?: 0} first=$deviceTypoFirst",
        )
        report.appendLine(
            "STEP18c verdict=" + when {
                !deviceTypo.optBoolean("ok") ->
                    "OK_NOT_ATTEMPTED:" + deviceTypo.optString("code")
                deviceTypoHints == null || deviceTypoHints.length() == 0 -> "FAIL_NO_HINT"
                deviceTypoFirst.contains("target") -> "OK"
                else -> "OK_OTHER:" + deviceTypoFirst.take(40)
            },
        )
    }

    /**
     * 文本通路的快通道（本机实测约 40 秒）：主屏「带 index 真写入」、同框清空、
     * 虚拟屏拒绝 index、跨屏快照拒绝。
     *
     * 为什么单独一段：这四条的正向样本以前**只有单测**（fix160 / fix164），真机只验过
     * 「虚拟屏 + index 被拒绝」这一条反向路径。代价不对等：写错一个字的后果是文本落到
     * **别的输入框**，而返回 ok=true。
     * 入口：`am start ... --es eta.debug.virtual_display text_probe`
     */
    fun textProbe(context: Context, inputProbePackage: String? = null): String {
        if (!probeRunning.compareAndSet(false, true)) return "text probe already running"
        Thread({
            val report = StringBuilder()
            report.appendLine(buildMarker(context))
            val tools = AgentLocalTools(
                context = context.applicationContext,
                logger = AndroidAgentLogger,
                browserToolsEnabled = { false },
                terminalToolsEnabled = { false },
                deviceDirectToolsEnabled = { false },
                deviceSensitiveReadToolsEnabled = { false },
                deviceSensitiveActionToolsEnabled = { false },
                memoryToolsEnabled = { false },
                memoryWritable = false,
                rootAvailable = { true },
            )
            fun call(name: String, args: JSONObject, log: Boolean = true): JSONObject {
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "textprobe-$name",
                        name = name,
                        argumentsJson = args.toString(),
                    ),
                )
                val json = runCatching { JSONObject(result.content) }.getOrDefault(JSONObject())
                if (log) report.appendLine("$name=").appendLine(result.content.take(1_500))
                return json
            }
            fun firstEditable(state: JSONObject): Int {
                val nodes = state.optJSONArray("ui_nodes") ?: return -1
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optBoolean("editable")) return node.optInt("index", -1)
                }
                return -1
            }
            fun nodeText(state: JSONObject, index: Int): String {
                val nodes = state.optJSONArray("ui_nodes") ?: return ""
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optInt("index", -1) == index) return node.optString("text")
                }
                return ""
            }
            /** fix178 用：节点文本被砍之前有多长（线格式的 text_len）；没标就是 0。 */
            fun nodeDeclaredTextLen(state: JSONObject, index: Int): Int {
                val nodes = state.optJSONArray("ui_nodes") ?: return 0
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optInt("index", -1) == index) return node.optInt("text_len", 0)
                }
                return 0
            }
            fun nodeTextClipped(state: JSONObject, index: Int): Boolean {
                val nodes = state.optJSONArray("ui_nodes") ?: return false
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optInt("index", -1) == index) return node.optBoolean("text_clipped")
                }
                return false
            }
            fun isEditable(state: JSONObject, index: Int): Boolean {
                val nodes = state.optJSONArray("ui_nodes") ?: return false
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optInt("index", -1) == index) return node.optBoolean("editable")
                }
                return false
            }

            /** 搜索入口：不同应用叫法不同（搜索 \/ 搜索应用 \/ 查找），只要可点就点。 */
            fun findSearchEntry(state: JSONObject): Int {
                val nodes = state.optJSONArray("ui_nodes") ?: return -1
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (!node.optBoolean("clickable")) continue
                    val label = node.optString("desc") + node.optString("text")
                    if (label.contains("搜索") || label.contains("查找")) {
                        return node.optInt("index", -1)
                    }
                }
                return -1
            }

            /** 按内容找节点：会话列表一直在动，按旧 index 回读会把写成功误判成写丢。 */
            fun findText(state: JSONObject, needle: String): Int {
                val nodes = state.optJSONArray("ui_nodes") ?: return -1
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    if (node.optString("text").contains(needle)) return node.optInt("index", -1)
                }
                return -1
            }
            val probePkg = inputProbePackage?.trim()?.takeIf { it.isNotEmpty() }
                ?: "com.coloros.calendar"
            val probeText = "etaprobe"
            val seedText = "etaseed"
            val tailText = "probe"
            // 中文（非 ASCII）：fix160 §七 #3 一直记着「无障碍 ACTION_SET_TEXT 通路的中文 verified
            // 没有真机样本」。只有单测覆盖 UTF-16 偏移，而真实输入框怎么处理是另一回事。
            val cnSeed = "测试一下"
            val cnTail = "追加"
            // 主屏那一组（TEXT3c/3d）用另一对字：同一个报告里两串中文内容不同，
            // 回读时不会互相干扰（findText 是按内容找节点）。
            val cnMainSeed = "中文种子"
            val cnMainTail = "后缀"
            var displayKey = ""
            try {
                var waitMs = 0
                while (waitMs < 30_000) {
                    val ready = call("observe_screen", JSONObject().put("max_nodes", 1), log = false)
                    if (ready.optJSONObject("accessibility")?.optBoolean("available") == true) break
                    Thread.sleep(1_000L)
                    waitMs += 1_000
                }
                report.appendLine("TEXT accessibility_ready_after=${waitMs}ms")

                // ① 主屏带 index 写入：这是 fix160 / fix164 一直缺的正向样本。
                // 探针会把 canary 自己拉到前台（脚本保证），所以这个输入框是测试包的会话框，
                // 写完立刻在同一框里清掉，不发送。
                // 两个真机坑（本机踩到）：
                //   a. 输入框在会话最底部，首屏快照常只截到列表上半部分（ui_tree_truncated）
                //      —— 没翻到底不能当成「界面没输入框」；
                //   b. 系统安全中心会弹出「检测到 Eta 获取无障碍权限」的窗，把主屏整个盖住，
                //      对着它操作可能点到「关闭无障碍」。前台不是自己就跳过，宁可不测。
                var mainObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                var mainId = mainObs.optString("observation_id")
                val foreground = mainObs.optJSONObject("focus")?.optString("package").orEmpty()
                report.appendLine("TEXT0 foreground=$foreground expected=${context.packageName}")
                var mainEditable =
                    if (foreground == context.packageName) firstEditable(mainObs) else -1
                var scrollRounds = 0
                while (mainEditable < 0 && scrollRounds < 2 && foreground == context.packageName) {
                    call("scroll", JSONObject().put("direction", "down").put("amount", "page"))
                    Thread.sleep(900L)
                    mainObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                    mainId = mainObs.optString("observation_id")
                    mainEditable = firstEditable(mainObs)
                    scrollRounds++
                }
                report.appendLine(
                    "TEXT1 observation=$mainId editable=$mainEditable scrollRounds=$scrollRounds",
                )
                if (foreground != context.packageName) {
                    report.appendLine("TEXT1 verdict=SKIPPED_FOREGROUND_OTHER:$foreground")
                    report.appendLine("TEXT2 verdict=SKIPPED_FOREGROUND_OTHER")
                    report.appendLine("TEXT3 verdict=SKIPPED_FOREGROUND_OTHER")
                    report.appendLine("TEXT3c verdict=SKIPPED_FOREGROUND_OTHER")
                    report.appendLine("TEXT3d verdict=SKIPPED_FOREGROUND_OTHER")
                } else if (mainEditable < 0) {
                    report.appendLine("TEXT1 verdict=SKIPPED_NO_EDITABLE")
                    report.appendLine("TEXT2 verdict=SKIPPED_NO_EDITABLE")
                    report.appendLine("TEXT3 verdict=SKIPPED_NO_EDITABLE")
                    report.appendLine("TEXT3c verdict=SKIPPED_NO_EDITABLE")
                    report.appendLine("TEXT3d verdict=SKIPPED_NO_EDITABLE")
                } else {
                    val typed = call(
                        "type_text",
                        JSONObject()
                            .put("text", probeText)
                            .put("index", mainEditable)
                            .put("observation_id", mainId),
                    )
                    val verification = typed.optString("input_verification")
                    report.appendLine(
                        "TEXT1 ok=${typed.optBoolean("ok")} code=${typed.optString("code")} " +
                            "verification=$verification",
                    )
                    report.appendLine(
                        "TEXT1 verdict=" + when {
                            !typed.optBoolean("ok") ->
                                "OK_NOT_ATTEMPTED:" + typed.optString("code")
                            verification == "not_applied" -> "FAIL_NOT_APPLIED"
                            verification == "verified" -> "OK"
                            else -> "OK_UNVERIFIED"
                        },
                    )
                    // ② 读回：这串字到底落到哪个节点上？
                    // 不能按原 index 回读：会话列表一直在动，新快照里同一个 index 已经换了节点
                    // （真机第一轮就是这么误报 FAIL_FIELD_EMPTY —— 字其实写进去了）。
                    val afterObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                    val landedIndex = findText(afterObs, probeText)
                    val landedEditable = landedIndex >= 0 && isEditable(afterObs, landedIndex)
                    report.appendLine(
                        "TEXT2 index=$landedIndex editable=$landedEditable " +
                            "text=${nodeText(afterObs, landedIndex).take(40)}",
                    )
                    report.appendLine(
                        "TEXT2 verdict=" + when {
                            landedIndex < 0 -> "FAIL_FIELD_EMPTY"
                            !landedEditable -> "FAIL_WRONG_FIELD:index=$landedIndex"
                            else -> "OK"
                        },
                    )
                    // ③ 清空：用「刚观察到的那个框」清，跟模型的做法一致。
                    if (landedIndex < 0) {
                        report.appendLine("TEXT3 verdict=SKIPPED_NO_LANDED_TEXT")
                    } else {
                        val clearObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val clearIndex = findText(clearObs, probeText).takeIf { it >= 0 } ?: landedIndex
                        val cleared = call(
                            "clear_text",
                            JSONObject()
                                .put("observation_id", clearObs.optString("observation_id"))
                                .put("index", clearIndex),
                        )
                        val afterClear = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val leftover = findText(afterClear, probeText)
                        report.appendLine(
                            "TEXT3 clearIndex=$clearIndex ok=${cleared.optBoolean("ok")} " +
                                "code=${cleared.optString("code")} " +
                                "verification=${cleared.optString("input_verification")} " +
                                "leftover=$leftover",
                        )
                        report.appendLine(
                            "TEXT3 verdict=" + when {
                                !cleared.optBoolean("ok") ->
                                    "OK_NOT_ATTEMPTED:" + cleared.optString("code")
                                leftover >= 0 -> "FAIL_NOT_CLEARED"
                                else -> "OK"
                            },
                        )
                    }
                    // ③b 主屏 + 中文（非 ASCII）：fix175 只测了虚拟屏的中文，主屏没有样本。
                    // 两者走同一个无障碍 ACTION_SET_TEXT，但主屏用的是「当前输入焦点」（没有
                    // display 参数），是真机上最容易写错框的一条 —— 这里补齐：
                    // 点框 → 写中文 → 追加中文 → 读回 → 清空。
                    var cnMainObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                    // 只接受“真的主屏”样本：本机实测过“published 观察停在虚拟屏”的情形，
                    // 那时不带 display_id 的 type_text 会写到那块虚拟屏；再把结论写成主屏样本就是假的。
                    val cnMainScope = cnMainObs.optJSONObject("display")?.optString("scope").orEmpty()
                    val cnMainDisplay = cnMainObs.optJSONObject("display")?.optInt("id", -1) ?: -1
                    // fix176：主屏在 observe_screen 里是 scope="default"、id=0（工具结果里另一种写法是
                    // scope="main"）；第一版只认 "main"，于是每次主屏样本都被判成
                    // SKIPPED_NOT_MAIN:default/0 —— 样本没跑，报告却像是"主屏没这个能力"。
                    val cnMainIsMain = cnMainDisplay == 0 ||
                        cnMainScope == "main" || cnMainScope == "default"
                    val cnMainEditable = firstEditable(cnMainObs)
                    if (!cnMainIsMain) {
                        // 不在主屏就根本别写：不带 display_id 的写入会落到快照那块屏上，
                        // 既污染虚拟屏样本，又让「主屏通过」变成假结论。
                        report.appendLine("TEXT3c verdict=SKIPPED_NOT_MAIN:$cnMainScope/$cnMainDisplay")
                        report.appendLine("TEXT3d verdict=SKIPPED_NOT_MAIN")
                    } else if (cnMainEditable < 0) {
                        report.appendLine("TEXT3c verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT3d verdict=SKIPPED_NO_EDITABLE")
                    } else {
                        call(
                            "tap_element",
                            JSONObject()
                                .put("observation_id", cnMainObs.optString("observation_id"))
                                .put("index", cnMainEditable),
                        )
                        Thread.sleep(400L)
                        val cnMainWrote = call(
                            "type_text",
                            JSONObject().put("text", cnMainSeed),
                        )
                        val cnMainAppended = call(
                            "type_text",
                            JSONObject().put("mode", "append").put("text", cnMainTail),
                        )
                        cnMainObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val cnMainBoth = findText(cnMainObs, cnMainSeed + cnMainTail)
                        val cnMainSeedOnly = findText(cnMainObs, cnMainSeed)
                        report.appendLine(
                            "TEXT3c write=${cnMainWrote.optBoolean("ok")} " +
                                "append=${cnMainAppended.optBoolean("ok")} " +
                                "scope=$cnMainScope/$cnMainDisplay " +
                                "method=${cnMainWrote.optString("method")} " +
                                "verify=${cnMainWrote.optString("input_verification")}/" +
                                cnMainAppended.optString("input_verification") +
                                " both=$cnMainBoth seedOnly=$cnMainSeedOnly " +
                                "text=${nodeText(cnMainObs, cnMainBoth).take(40)}",
                        )
                        report.appendLine("TEXT3c raw=" + cnMainWrote.toString().take(400))
                        report.appendLine(
                            "TEXT3c verdict=" + when {
                                !cnMainWrote.optBoolean("ok") ->
                                    "FAIL_CN_WRITE:" + cnMainWrote.optString("code")
                                cnMainWrote.optString("input_verification") != "verified" ->
                                    "FAIL_CN_NOT_VERIFIED:" +
                                        cnMainWrote.optString("input_verification")
                                cnMainAppended.optString("code") ==
                                    "TEXT_SELECTION_UNAVAILABLE" -> "OK_HONEST_DEGRADE"
                                !cnMainAppended.optBoolean("ok") ->
                                    "OK_NOT_ATTEMPTED:append:" + cnMainAppended.optString("code")
                                cnMainBoth >= 0 &&
                                    cnMainAppended.optString("input_verification") == "verified" ->
                                    "OK"
                                cnMainBoth >= 0 -> "FAIL_NOT_VERIFIED:append"
                                cnMainSeedOnly >= 0 -> "FAIL_APPEND_NOT_APPLIED"
                                else -> "FAIL_CN_LOST"
                            },
                        )
                        // 主屏里同样不能留测试文本（这个框是 canary 自己的会话框，不 submit）。
                        val cnClearObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val cnClearIndex = findText(cnClearObs, cnMainSeed)
                            .takeIf { it >= 0 } ?: cnMainEditable
                        val cnMainCleared = call(
                            "clear_text",
                            JSONObject()
                                .put("observation_id", cnClearObs.optString("observation_id"))
                                .put("index", cnClearIndex),
                        )
                        val cnAfterClear = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val cnMainLeft = findText(cnAfterClear, cnMainSeed)
                        report.appendLine(
                            "TEXT3d clearIndex=$cnClearIndex ok=${cnMainCleared.optBoolean("ok")} " +
                                "leftover=$cnMainLeft " +
                                "verdict=" + when {
                                    !cnMainCleared.optBoolean("ok") ->
                                        "FAIL_CLEANUP:" + cnMainCleared.optString("code")
                                    cnMainLeft >= 0 -> "FAIL_NOT_CLEARED"
                                    else -> "OK"
                                },
                        )
                        // ③c 主屏 + append 超长文本：超过 200 字会改走 pasteText（剪贴板/粘贴兜底）通路。
                        // fix177 之前那条通路整段跑在无障碍主线程上、粘贴后立刻读回，
                        // 和 TEXT3c 一样会把「其实已经写进去了」报成结果未知。
                        // 顺带验剪贴板：不能把测试文本留在用户剪贴板里。
                        // 注：ui_nodes 的 text 有上限（fix178 起采集端与线格式共用 200 字），
                        // 所以这里用「前缀找到节点」+「写入端自报 verified（它比的是全文）」两个证据，
                        // 再加一条 fix178 的验收：节点必须自报 text_clipped 且 text_len = 全文长度。
                        // 修复前这里只会看到 120 字、text_clipped 一次都不出现 —— 那就是「静默截断」。
                        val longMarker = "长文本0123456789"
                        val longSeed = longMarker + "0123456789".repeat(24) // 243 字
                        val clipBefore = call("get_clipboard", JSONObject())
                        val longWrote = call(
                            "type_text",
                            JSONObject().put("mode", "append").put("text", longSeed),
                        )
                        val longObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val longIndex = findText(longObs, longMarker)
                        val longText = nodeText(longObs, longIndex)
                        val longDeclaredLen = nodeDeclaredTextLen(longObs, longIndex)
                        val longClipped = nodeTextClipped(longObs, longIndex)
                        val clipAfter = call("get_clipboard", JSONObject())
                        val clipSame = (!clipBefore.optBoolean("ok") && !clipAfter.optBoolean("ok")) ||
                            clipBefore.optString("text") == clipAfter.optString("text")
                        report.appendLine(
                            "TEXT3e ok=${longWrote.optBoolean("ok")} " +
                                "method=${longWrote.optString("method")} " +
                                "verify=${longWrote.optString("input_verification")} " +
                                "idx=$longIndex seenLen=${longText.length} " +
                                "declaredLen=$longDeclaredLen clipped=$longClipped " +
                                "clipBefore=${clipBefore.optBoolean("ok")}/${clipBefore.optString("text").length} " +
                                "clipAfter=${clipAfter.optBoolean("ok")}/${clipAfter.optString("text").length} " +
                                "clipSame=$clipSame",
                        )
                        report.appendLine("TEXT3e raw=" + longWrote.toString().take(400))
                        report.appendLine(
                            "TEXT3e verdict=" + when {
                                !longWrote.optBoolean("ok") ->
                                    "FAIL_LONG_WRITE:" + longWrote.optString("code")
                                longWrote.optString("input_verification") != "verified" ->
                                    "FAIL_LONG_NOT_VERIFIED:" + longWrote.optString("input_verification")
                                longIndex < 0 -> "FAIL_LONG_TEXT_MISSING"
                                // fix178：长文本必须「显式说明被砍」——既要有 text_clipped，
                                // 也要有 text_len 告诉模型全文到底多长；缺一个就是静默截断。
                                !longClipped -> "FAIL_LONG_TEXT_TRUNCATION_UNMARKED"
                                longDeclaredLen < longSeed.length ->
                                    "FAIL_LONG_TEXT_LEN_UNDECLARED:$longDeclaredLen<${longSeed.length}"
                                !clipSame -> "FAIL_CLIPBOARD_NOT_RESTORED"
                                else -> "OK"
                            },
                        )
                        if (!clipSame && clipBefore.optBoolean("ok")) {
                            // 服务没把剪贴板还给用户：这里补回去，不能把探针的测试文本留在别人剪贴板里。
                            val clipFix = call(
                                "set_clipboard",
                                JSONObject().put("text", clipBefore.optString("text")),
                            )
                            report.appendLine("TEXT3e clipboard_recovered=" + clipFix.optBoolean("ok"))
                        }
                        // 清掉长文本（同样走 clear_text，index 用刚找到的那个）。
                        val longClearObs = call("observe_screen", JSONObject().put("max_nodes", 60))
                        val longClearIndex = findText(longClearObs, longMarker)
                            .takeIf { it >= 0 } ?: cnMainEditable
                        val longCleared = call(
                            "clear_text",
                            JSONObject()
                                .put("observation_id", longClearObs.optString("observation_id"))
                                .put("index", longClearIndex),
                        )
                        val longAfter = call("observe_screen", JSONObject().put("max_nodes", 60))
                        report.appendLine(
                            "TEXT3f clear ok=${longCleared.optBoolean("ok")} " +
                                "leftover=${findText(longAfter, longMarker)} " +
                                "verdict=" + when {
                                    !longCleared.optBoolean("ok") ->
                                        "FAIL_CLEANUP:" + longCleared.optString("code")
                                    findText(longAfter, longMarker) >= 0 -> "FAIL_NOT_CLEARED"
                                    else -> "OK"
                                },
                        )
                    }
                }

                // ③d 主屏 + 「只接受粘贴」的输入框（fix181）：
                // pasteText 的粘贴兜底分支只在目标拒绝 ACTION_SET_TEXT 时才执行，而 Compose 的
                // BasicTextField 与普通 EditText 都接受 SET_TEXT —— 这条分支（fix177 改过判定）
                // 一直没有真机样本。PasteOnlyProbeActivity 提供一个受控样本，把这条路真的走一遍。
                val pasteSeed = "粘贴兜底" + "0123456789".repeat(24) // 244 字 > 200，必走 pasteText
                // 夹具靠标题确认「前台真的是样本窗口」：只比包名不够 ——
                // 2026-10-27 实测过探针启动后前台被切回 Eta 主界面，那次 editable 找到的是
                // Eta 自己的输入框，等于把测试文本写进用户正在用的输入框。
                val pasteTitle = context.getString(io.github.mangi.eta.R.string.probe_paste_only_title)
                val pasteLaunched = runCatching {
                    context.startActivity(
                        android.content.Intent(context, PasteOnlyProbeActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                var pasteObs = JSONObject()
                var pasteIndex = -1
                var pasteWait = 0
                while (pasteWait < 10) {
                    Thread.sleep(500L)
                    pasteObs = call("observe_screen", JSONObject().put("max_nodes", 40), log = false)
                    val pasteFg = pasteObs.optJSONObject("focus")?.optString("package").orEmpty()
                    if (pasteFg != context.packageName) {
                        pasteWait++
                        continue
                    }
                    val candidate = firstEditable(pasteObs)
                    if (candidate >= 0 && findText(pasteObs, pasteTitle) >= 0) {
                        pasteIndex = candidate
                        break
                    }
                    pasteWait++
                }
                report.appendLine(
                    "TEXT3g launch=${if (pasteLaunched) "ok" else "FAIL"} " +
                        "wait=${pasteWait * 500}ms editable=$pasteIndex " +
                        "foreground=${pasteObs.optJSONObject("focus")?.optString("package").orEmpty()}",
                )
                if (pasteIndex < 0) {
                    report.appendLine("TEXT3g verdict=SKIPPED_NO_PASTE_ONLY_FIELD")
                    call("press_key", JSONObject().put("button", "BACK"), log = false)
                } else {
                    val pasteClipBefore = call("get_clipboard", JSONObject(), log = false)
                    val pasteWrote = call(
                        "type_text",
                        JSONObject().put("mode", "append").put("text", pasteSeed),
                    )
                    val pasteAfter = call("observe_screen", JSONObject().put("max_nodes", 60), log = false)
                    val pasteAfterIndex = firstEditable(pasteAfter)
                    val pasteDeclared = nodeDeclaredTextLen(pasteAfter, pasteAfterIndex)
                    val pasteClipAfter = call("get_clipboard", JSONObject(), log = false)
                    val pasteClipSame =
                        (!pasteClipBefore.optBoolean("ok") && !pasteClipAfter.optBoolean("ok")) ||
                            pasteClipBefore.optString("text") == pasteClipAfter.optString("text")
                    val pasteMethod = pasteWrote.optString("method")
                    // 两个字段都要认：fix171 给 SET_TEXT 通路补 `input_verification`，
                    // fix182 起 ACTION_PASTE 兜底也补（之前只有 `verified`，按前者判断会把
                    // 真的走通兜底的结果报成失败 —— 2026-10-27 真机踩到）。
                    // 保留双读是为了兼容旧版本 APK 的报告，不代表其中一个是多余的。
                    val pasteVerified =
                        pasteWrote.optBoolean("verified") ||
                            pasteWrote.optString("input_verification") == "verified"
                    report.appendLine(
                        "TEXT3g ok=${pasteWrote.optBoolean("ok")} method=$pasteMethod " +
                            "verified=${pasteWrote.optBoolean("verified")} " +
                            "verify=${pasteWrote.optString("input_verification")} " +
                            "idx=$pasteAfterIndex declaredLen=$pasteDeclared seed=${pasteSeed.length} " +
                            "clipBefore=${pasteClipBefore.optBoolean("ok")} " +
                            "clipAfter=${pasteClipAfter.optBoolean("ok")} clipSame=$pasteClipSame",
                    )
                    report.appendLine("TEXT3g raw=" + pasteWrote.toString().take(400))
                    report.appendLine(
                        "TEXT3g verdict=" + when {
                            !pasteWrote.optBoolean("ok") ->
                                "FAIL_PASTE_WRITE:" + pasteWrote.optString("code")
                            // 样本本身没造对（控件接受了 SET_TEXT）：这条不算通过，否则等于
                            // 「测了一个没走到兜底分支的样本」还报绿。
                            // 必须是兜底分支本身：`ACTION_SET_TEXT_PASTE` 意思是 SET_TEXT 被
                            // 接受了（夹具没造对），名字里同样含 PASTE，用 contains 会把它放过去。
                            pasteMethod != "ACTION_PASTE" ->
                                "FAIL_NOT_PASTE_FALLBACK:$pasteMethod"
                            !pasteVerified ->
                                "FAIL_PASTE_NOT_VERIFIED:" +
                                    pasteWrote.optBoolean("verified") +
                                    "/" + pasteWrote.optString("input_verification")
                            pasteDeclared < pasteSeed.length ->
                                "FAIL_PASTE_TEXT_LEN_UNDECLARED:$pasteDeclared"
                            !pasteClipSame -> "FAIL_CLIPBOARD_NOT_RESTORED"
                            else -> "OK"
                        },
                    )
                    if (!pasteClipSame && pasteClipBefore.optBoolean("ok")) {
                        val pasteClipFix = call(
                            "set_clipboard",
                            JSONObject().put("text", pasteClipBefore.optString("text")),
                            log = false,
                        )
                        report.appendLine("TEXT3g clipboard_recovered=" + pasteClipFix.optBoolean("ok"))
                    }
                    // 关掉样本窗口：写入内容只在这个控件里，窗口销毁即消失，不落盘。
                    call("press_key", JSONObject().put("button", "BACK"), log = false)
                    Thread.sleep(600L)
                    val pasteBack = call("observe_screen", JSONObject().put("max_nodes", 20), log = false)
                    report.appendLine(
                        "TEXT3g back_foreground=" +
                            pasteBack.optJSONObject("focus")?.optString("package").orEmpty(),
                    )
                }

                // ④ 虚拟屏：带 index 必须被拒结，而不是默默写进当前焦点框。
                val virtual = call(
                    "virtual_display_create",
                    JSONObject()
                        .put("width", 720)
                        .put("height", 1280)
                        .put("density_dpi", 320)
                        .put("purpose", "text probe")
                        .put("package_name", probePkg)
                        .put("force", true),
                )
                displayKey = virtual.optString("id")
                val displayId = virtual.optInt("display_id", -1)
                report.appendLine(
                    "TEXT0 display=$displayId launched=${virtual.optBoolean("launched")} pkg=$probePkg",
                )
                if (displayId <= 0) {
                    report.appendLine("TEXT4 verdict=SKIPPED_CREATE_FAILED")
                    report.appendLine("TEXT5 verdict=SKIPPED_CREATE_FAILED")
                    report.appendLine("TEXT6 verdict=SKIPPED_CREATE_FAILED")
                } else {
                    val virtualTyped = call(
                        "type_text",
                        JSONObject()
                            .put("display_id", displayId)
                            .put("index", 0)
                            .put("text", "x"),
                    )
                    report.appendLine(
                        "TEXT4 type code=${virtualTyped.optString("code")} " +
                            "ok=${virtualTyped.optBoolean("ok")}",
                    )
                    report.appendLine(
                        "TEXT4 verdict=" + when {
                            virtualTyped.optBoolean("ok") -> "FAIL_INDEX_ACCEPTED"
                            virtualTyped.optString("code") ==
                                DisplayTextErrors.INDEX_ON_VIRTUAL_DISPLAY -> "OK"
                            else -> "FAIL_OTHER_CODE:" + virtualTyped.optString("code")
                        },
                    )
                    val virtualCleared = call(
                        "clear_text",
                        JSONObject().put("display_id", displayId).put("index", 0),
                    )
                    report.appendLine(
                        "TEXT5 clear code=${virtualCleared.optString("code")} " +
                            "ok=${virtualCleared.optBoolean("ok")}",
                    )
                    report.appendLine(
                        "TEXT5 verdict=" + when {
                            virtualCleared.optBoolean("ok") -> "FAIL_INDEX_ACCEPTED"
                            virtualCleared.optString("code") ==
                                DisplayTextErrors.INDEX_ON_VIRTUAL_DISPLAY -> "OK"
                            else -> "FAIL_OTHER_CODE:" + virtualCleared.optString("code")
                        },
                    )
                    // ⑥ 跳屏：快照在虚拟屏，却要操作主屏的 index。
                    val virtualObs = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 20),
                    )
                    val crossClear = call(
                        "clear_text",
                        JSONObject()
                            .put("display_id", 0)
                            .put("index", 0)
                            .put("observation_id", virtualObs.optString("observation_id")),
                    )
                    report.appendLine(
                        "TEXT6 snapshot=${virtualObs.optString("observation_id")} " +
                            "code=${crossClear.optString("code")} ok=${crossClear.optBoolean("ok")}",
                    )
                    report.appendLine(
                        "TEXT6 verdict=" + when {
                            crossClear.optBoolean("ok") -> "FAIL_CROSS_SCOPE_ACCEPTED"
                            crossClear.optString("code") ==
                                ObservationScopeErrors.OTHER_DISPLAY -> "OK"
                            else -> "FAIL_OTHER_CODE:" + crossClear.optString("code")
                        },
                    )
                }

                // ⑦ 追加写（mode=append）：走的是 TextEditPlanner.insertAtSelection，
                // 与 replace（直接 ACTION_SET_TEXT 整段写入）是两条独立实现，
                // fix160 起**只有单测、真机零样本**。这里在虚拟屏上补真机取证：
                // 先写入 seed，再 append 一段，读回必须是 seed+tail（不是 tail，也不是 seed）。
                if (displayId <= 0) {
                    report.appendLine("TEXT7 verdict=SKIPPED_NO_DISPLAY")
                    report.appendLine("TEXT8 verdict=SKIPPED_NO_DISPLAY")
                    report.appendLine("TEXT9 verdict=SKIPPED_NO_DISPLAY")
                    report.appendLine("TEXT10 verdict=SKIPPED_NO_DISPLAY")
                    report.appendLine("TEXT11 verdict=SKIPPED_NO_DISPLAY")
                    report.appendLine("TEXT12 verdict=SKIPPED_NO_DISPLAY")
                } else {
                    // 日历主界面没有输入框，换一个真有 EditText 的应用（默认便签搜索）。
                    val appendPkg = inputProbePackage?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "com.coloros.note"
                    call(
                        "launch_app",
                        JSONObject().put("package_name", appendPkg).put("display_id", displayId),
                    )
                    var appendObs = call(
                        "observe_screen",
                        JSONObject().put("display_id", displayId).put("max_nodes", 60),
                    )
                    var editIndex = firstEditable(appendObs)
                    var tapRounds = 0
                    // 不只靠一次 tap：搜索页展开时首屏节点列表可能被上下文预算截掉尾部，
                    // 而 EditText（search_src_text）往往就在尾部（2026-10-27 实测：
                    // 截断后只见 27 个节点，输入框排在第 30 个）→ 只看一次会误判
                    // 「没有输入框」并记成 SKIPPED_NO_EDITABLE（假跳过）。
                    // 所以：找不到就再等一拍重新观察，最多 3 次。
                    var idleRounds = 0
                    while (editIndex < 0 && tapRounds < 3 && idleRounds < 3) {
                        val entry = findSearchEntry(appendObs)
                        if (entry >= 0) {
                            call(
                                "tap_element",
                                JSONObject()
                                    .put("display_id", displayId)
                                    .put("observation_id", appendObs.optString("observation_id"))
                                    .put("index", entry),
                            )
                            tapRounds++
                        } else {
                            idleRounds++
                        }
                        call("wait", JSONObject().put("duration_ms", 1_500))
                        appendObs = call(
                            "observe_screen",
                            JSONObject().put("display_id", displayId).put("max_nodes", 60),
                        )
                        editIndex = firstEditable(appendObs)
                    }
                    report.appendLine(
                        "TEXT7 display=$displayId pkg=$appendPkg editable=$editIndex tapRounds=$tapRounds",
                    )
                    if (editIndex < 0) {
                        report.appendLine("TEXT7 verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT8 verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT9 verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT10 verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT11 verdict=SKIPPED_NO_EDITABLE")
                        report.appendLine("TEXT12 verdict=SKIPPED_NO_EDITABLE")
                    } else {
                        // display 通路的写入只认焦点框（或唯一的可编辑框），先点成焦点。
                        call(
                            "tap_element",
                            JSONObject()
                                .put("display_id", displayId)
                                .put("observation_id", appendObs.optString("observation_id"))
                                .put("index", editIndex),
                        )
                        call("wait", JSONObject().put("duration_ms", 600))
                        val seeded = call(
                            "type_text",
                            JSONObject().put("display_id", displayId).put("text", seedText),
                        )
                        report.appendLine(
                            "TEXT7 seed ok=${seeded.optBoolean("ok")} code=${seeded.optString("code")} " +
                                "method=${seeded.optString("method")} " +
                                "verification=${seeded.optString("input_verification")}",
                        )
                        report.appendLine(
                            "TEXT7 verdict=" + when {
                                !seeded.optBoolean("ok") ->
                                    "OK_NOT_ATTEMPTED:seed:" + seeded.optString("code")
                                // 光靠 ok 不够：ok 只代表动作被接受，verified 才是「读回一致」。
                                seeded.optString("input_verification") != "verified" ->
                                    "FAIL_NOT_VERIFIED:" + seeded.optString("input_verification")
                                else -> "OK"
                            },
                        )
                        val appended = call(
                            "type_text",
                            JSONObject()
                                .put("display_id", displayId)
                                .put("mode", "append")
                                .put("text", tailText),
                        )
                        val appendObs2 = call(
                            "observe_screen",
                            JSONObject().put("display_id", displayId).put("max_nodes", 60),
                        )
                        val both = findText(appendObs2, seedText + tailText)
                        val seedOnly = findText(appendObs2, seedText)
                        report.appendLine(
                            "TEXT8 ok=${appended.optBoolean("ok")} code=${appended.optString("code")} " +
                                "method=${appended.optString("method")} " +
                                "verification=${appended.optString("input_verification")} " +
                                "both=$both seedOnly=$seedOnly text=${nodeText(appendObs2, both).take(40)}",
                        )
                        report.appendLine(
                            "TEXT8 verdict=" + when {
                                // 输入框不提供光标/选区时，诚实降级比「假装追加成功」强。
                                appended.optString("code") == "TEXT_SELECTION_UNAVAILABLE" ->
                                    "OK_HONEST_DEGRADE"
                                !appended.optBoolean("ok") ->
                                    "OK_NOT_ATTEMPTED:" + appended.optString("code")
                                both >= 0 && appended.optString("input_verification") == "verified" ->
                                    "OK"
                                both >= 0 ->
                                    "FAIL_NOT_VERIFIED:" + appended.optString("input_verification")
                                seedOnly >= 0 -> "FAIL_APPEND_NOT_APPLIED"
                                else -> "FAIL_APPEND_LOST"
                            },
                        )
                        // 清空：别把测试文本留在搜索框里。
                        val cleared = call(
                            "type_text",
                            JSONObject().put("display_id", displayId).put("text", ""),
                        )
                        val clearObs = call(
                            "observe_screen",
                            JSONObject().put("display_id", displayId).put("max_nodes", 60),
                        )
                        val leftover = findText(clearObs, seedText)
                        report.appendLine(
                            "TEXT9 ok=${cleared.optBoolean("ok")} code=${cleared.optString("code")} " +
                                "leftover=$leftover",
                        )
                        report.appendLine(
                            "TEXT9 verdict=" + when {
                                !cleared.optBoolean("ok") -> "FAIL_CLEANUP:" + cleared.optString("code")
                                leftover >= 0 -> "FAIL_NOT_CLEARED"
                                else -> "OK"
                            },
                        )
                        // ⑧ 中文（非 ASCII）：ASCII 能写进去不等于中文能。中文必须走无障碍写入，
                        // 而它的回读比较、以及「光标 = 前面几个 UTF-16 单元」都只在单测里验过。
                        // 这里在同一个框里先写中文、再追加中文，读回必须逐字是「测试一下追加」。
                        val cnWrote = call(
                            "type_text",
                            JSONObject().put("display_id", displayId).put("text", cnSeed),
                        )
                        report.appendLine(
                            "TEXT10 ok=${cnWrote.optBoolean("ok")} code=${cnWrote.optString("code")} " +
                                "method=${cnWrote.optString("method")} " +
                                "verification=${cnWrote.optString("input_verification")}",
                        )
                        val cnAppended = call(
                            "type_text",
                            JSONObject()
                                .put("display_id", displayId)
                                .put("mode", "append")
                                .put("text", cnTail),
                        )
                        val cnObs = call(
                            "observe_screen",
                            JSONObject().put("display_id", displayId).put("max_nodes", 60),
                        )
                        val cnBoth = findText(cnObs, cnSeed + cnTail)
                        val cnSeedOnly = findText(cnObs, cnSeed)
                        report.appendLine(
                            "TEXT11 ok=${cnAppended.optBoolean("ok")} " +
                                "code=${cnAppended.optString("code")} " +
                                "both=$cnBoth seedOnly=$cnSeedOnly " +
                                "text=${nodeText(cnObs, cnBoth).take(40)}",
                        )
                        report.appendLine(
                            "TEXT10 verdict=" + when {
                                !cnWrote.optBoolean("ok") -> "FAIL_CN_WRITE:" + cnWrote.optString("code")
                                cnWrote.optString("input_verification") != "verified" ->
                                    "FAIL_CN_NOT_VERIFIED:" + cnWrote.optString("input_verification")
                                cnSeedOnly >= 0 -> "OK"
                                else -> "FAIL_CN_LOST"
                            },
                        )
                        report.appendLine(
                            "TEXT11 verdict=" + when {
                                cnAppended.optString("code") == "TEXT_SELECTION_UNAVAILABLE" ->
                                    "OK_HONEST_DEGRADE"
                                !cnAppended.optBoolean("ok") ->
                                    "OK_NOT_ATTEMPTED:" + cnAppended.optString("code")
                                cnBoth >= 0 -> "OK"
                                cnSeedOnly >= 0 -> "FAIL_CN_APPEND_NOT_APPLIED"
                                else -> "FAIL_CN_APPEND_LOST"
                            },
                        )
                        // 中文同样不能留在用户 App 里。
                        val cnCleared = call(
                            "type_text",
                            JSONObject().put("display_id", displayId).put("text", ""),
                        )
                        val cnClearObs = call(
                            "observe_screen",
                            JSONObject().put("display_id", displayId).put("max_nodes", 60),
                        )
                        val cnLeftover = findText(cnClearObs, cnSeed)
                        report.appendLine(
                            "TEXT12 ok=${cnCleared.optBoolean("ok")} leftover=$cnLeftover " +
                                "verdict=" + when {
                                    !cnCleared.optBoolean("ok") ->
                                        "FAIL_CLEANUP:" + cnCleared.optString("code")
                                    cnLeftover >= 0 -> "FAIL_NOT_CLEARED"
                                    else -> "OK"
                                },
                        )
                    }
                }
            } catch (error: Throwable) {
                report.appendLine("EXCEPTION=${error.javaClass.simpleName}: ${error.message}")
            } finally {
                if (displayKey.isNotEmpty()) {
                    runCatching {
                        tools.execute(
                            AgentModelClient.ToolCall(
                                id = "textprobe-release",
                                name = "virtual_display_release",
                                argumentsJson = JSONObject().put("id", displayKey).toString(),
                            ),
                        )
                    }
                }
                runCatching { tools.close() }
                runCatching {
                    File(context.getExternalFilesDir(null), "text-probe.txt").writeText(report.toString())
                }
                probeRunning.set(false)
            }
        }, "eta-text-probe").start()
        return "text probe started"
    }

    /**
     * 只跑 STEP15~18 的快通道（本机实测约 30 秒）。
     *
     * 建两块屏：设置屏给「跨屏 after」当观察源，便签屏给按键 / 节点手势用，
     * 然后直接调 [runDisplayScopeSteps]。STEP1~14 守着别的缺陷，
     * 改这几段时不该连带重跑那三分钟。
     * 用法：`am start ... --es eta.debug.virtual_display scope_probe`
     */
    fun scopeProbe(context: Context, inputProbePackage: String? = null): String {
        if (!probeRunning.compareAndSet(false, true)) return "scope probe already running"
        Thread({
            val report = StringBuilder()
            report.appendLine(buildMarker(context))
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
            fun call(name: String, args: JSONObject, log: Boolean = true): JSONObject {
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "scopeprobe-$name",
                        name = name,
                        argumentsJson = args.toString(),
                    ),
                )
                val json = runCatching { JSONObject(result.content) }.getOrDefault(JSONObject())
                if (log) report.appendLine("$name=").appendLine(result.content.take(1_500))
                return json
            }
            try {
                // 装包会杀掉进程，无障碍服务需要重新 binding（本机实测 2~10 秒）。
                // 虚拟屏的 UI 树只能由无障碍提供（uiautomator 读不到别的屏），没连上时
                // observe 返回空节点、tap 报 DISPLAY_NO_VISIBLE_WINDOW —— 整段会看起来
                // 像「功能坏了」，其实只是探针起得太早。
                var waitMs = 0
                while (waitMs < 30_000) {
                    val ready = call(
                        "observe_screen",
                        JSONObject().put("max_nodes", 1),
                        log = false,
                    )
                    if (ready.optJSONObject("accessibility")?.optBoolean("available") == true) break
                    Thread.sleep(1_000L)
                    waitMs += 1_000
                }
                report.appendLine("SCOPE accessibility_ready_after=${waitMs}ms")
                val settings = call(
                    "virtual_display_create",
                    JSONObject()
                        .put("width", 720)
                        .put("height", 1280)
                        .put("density_dpi", 320)
                        .put("purpose", "scope probe")
                        .put("package_name", PROBE_PACKAGE),
                )
                val settingsDisplayId = settings.optInt("display_id", -1)
                // 输入屏默认用日历而不是便签：这一段（STEP15~18）只需要「有可见窗口」，
                // 而便签常被用户留在主屏跑（不 force 就启不来），联系人又会在虚拟屏上
                // 启动即退出（实测 LAUNCH_NOT_ON_DISPLAY）。日历不带用户数据，且本机实测
                // 在 720x1280 虚拟屏上能拿到完整首屏（53 个节点）。
                val probePkg = inputProbePackage?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "com.coloros.calendar"
                val inputDisplay = call(
                    "virtual_display_create",
                    JSONObject()
                        .put("width", 720)
                        .put("height", 1280)
                        .put("density_dpi", 320)
                        .put("purpose", "scope probe input")
                        .put("package_name", probePkg)
                        .put("force", true),
                )
                val inputDisplayId = inputDisplay.optInt("display_id", -1)
                report.appendLine(
                    "SCOPE settings=$settingsDisplayId input=$inputDisplayId pkg=$probePkg " +
                        "launched=${inputDisplay.optBoolean("launched")}",
                )
                if (settingsDisplayId > 0 && inputDisplayId > 0) {
                    runDisplayScopeSteps(
                        call = { toolName, toolArgs -> call(toolName, toolArgs) },
                        report = report,
                        settingsDisplayId = settingsDisplayId,
                        inputDisplay = inputDisplay,
                    )
                } else {
                    report.appendLine("SCOPE skipped create_failed")
                }
                call("virtual_display_release", JSONObject().put("id", inputDisplay.optString("id")))
                call("virtual_display_release", JSONObject().put("id", settings.optString("id")))
            } catch (error: Throwable) {
                report.appendLine("EXCEPTION=${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runCatching { tools.close() }
                runCatching {
                    File(context.getExternalFilesDir(null), "scope-probe.txt").writeText(report.toString())
                }
                probeRunning.set(false)
            }
        }, "eta-scope-probe").start()
        return "scope probe started"
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
