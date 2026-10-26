package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal class RootShellDeviceController(
    private val logger: AgentLogger,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val virtualDisplays: VirtualDisplayController? = null,
    /** 当前包名：虚拟屏动作期间用它切换到无 UI 输入法（避免主屏弹键盘）。 */
    private val packageNameProvider: () -> String = { "" },
) {
    /** 虚拟屏输入法租约：只保留一份静默 IME，按虚拟屏输入会话/焦点自动归还。 */
    private var silentImeOriginal: String? = null
    private var silentImeDisplayId: Int? = null
    private var silentImeStartedAtElapsed = 0L
    private var silentImeLeaseToken = 0L
    private val imeLeaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-virtual-ime").apply { isDaemon = true }
    }
    data class Observation(
        val content: String,
        val image: AgentModelClient.ModelImage?,
        val elementObservation: ElementObservation?,
        val coordinateSpace: CoordinateSpace?,
    )

    data class ElementObservation(
        val id: String,
        val source: ElementSource,
        val packageName: String,
        val windowId: Int?,
        val nodes: List<UiNode>,
        val maxNodes: Int,
        val truncated: Boolean,
        val treeSignature: String = "",
        internal val accessibilitySnapshot: AgentAccessibilityService.NodeSnapshot? = null,
    )

    enum class ElementSource(val wireName: String) {
        ACCESSIBILITY("accessibility"),
        UIAUTOMATOR("uiautomator"),
    }

    data class CoordinateSpace(
        val screenWidth: Int,
        val screenHeight: Int,
        val screenshotWidth: Int,
        val screenshotHeight: Int
    ) {
        fun fromScreenshot(x: Int, y: Int): ScreenPoint {
            require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
                "截图坐标超出范围：($x,$y) not in ${screenshotWidth}x$screenshotHeight"
            }
            return ScreenPoint(
                x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
                y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
            )
        }

        fun summary(): String =
            "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
    }

    data class ScreenPoint(val x: Int, val y: Int)

    fun screenDimensions(): Pair<Int, Int> = screenSize()

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        val checked: Boolean? = null,
        val selected: Boolean = false,
        val hint: String = "",
        /** 无障碍节点稳定 ID；uiautomator 回退路径为空。 */
        val nodeId: String = "",
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
        displayId: Int? = null,
    ): Observation {
        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null && !rootAvailable()) {
            return Observation(accessibilityUnavailable(), null, null, null)
        }
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val virtual = resolveVirtualSession(displayId)
        // 虚拟屏的"前台包"只能靠任务栈查（无障碍服务只跟踪默认屏）。
        // 不查就会退化成"报主屏的包"——观测结果会说假话，模型据此判断应用起没起来。
        val virtualForeground = if (virtual != null) {
            runCatching { foregroundPackageOn(virtual.displayId) }.getOrDefault("")
        } else {
            ""
        }
        // 任务栈只说「有过这个任务」：应用崩溃或被系统回收后记录还留着，
        // 于是「前台包名」会指向一个已经没有进程的应用（真机上因此把崩溃误判成「没起来」）。
        val virtualForegroundAlive = if (virtualForeground.isNotEmpty()) {
            runCatching { packageHasProcess(virtualForeground) }.getOrNull()
        } else {
            null
        }
        // 虚拟屏上的无障碍事件不可靠（无障碍服务跟随默认屏），一律走 Root 路径。
        val display = if (virtual != null) {
            virtual.width to virtual.height
        } else {
            screenSize()
        }
        val focus = if (virtual != null) {
            JSONObject()
                .put("package", virtualForeground)
                .put("component", "")
                .put("source", "virtual_display")
        } else {
            accessibility
                ?.currentPackageName()
                ?.takeIf { it.isNotBlank() }
                ?.let { packageName ->
                    JSONObject()
                        .put("package", packageName)
                        .put("component", packageName)
                        .put("source", "accessibility")
                }
                ?: focusedWindow()
        }
        // 即使 UI 树暂时拿不到，事件流也能说明「这个屏刚刚有没有变过」：
        // 年龄很小 → 截图可能已过期，模型应重新观察而不是复用坐标。
        val displayEventAge = virtual?.let { session ->
            accessibility?.displayEventAgeMs(session.displayId)
        }

        var uiDumpFailure: String? = null
        val elementObservation = if (includeUiTree) {
            // 虚拟屏也试着取 UI 树：无障碍的 rootInActiveWindow 只跟默认屏，
            // 但 windows 列表带 displayId，可以按它拿到虚拟屏窗口的根节点。
            // 拿不到时 captureNodeSnapshot 返回 null，调用方回退到截图路径。
            val accessibilitySnapshot = accessibility?.captureNodeSnapshot(
                nodeLimit,
                displayId = virtual?.displayId,
            )
            if (accessibilitySnapshot != null) {
                ElementObservation(
                    id = accessibilitySnapshot.id,
                    source = ElementSource.ACCESSIBILITY,
                    packageName = accessibilitySnapshot.packageName,
                    windowId = accessibilitySnapshot.windowId,
                    nodes = accessibilitySnapshot.nodes.map { it.toUiNode() },
                    maxNodes = nodeLimit,
                    truncated = accessibilitySnapshot.truncated,
                    accessibilitySnapshot = accessibilitySnapshot,
                )
            } else if (rootAvailable()) {
                val dump = dumpUiNodesReported(nodeLimit, displayId = virtual?.displayId)
                uiDumpFailure = dump.failure
                val rootNodes = dump.nodes
                ElementObservation(
                    id = "u${ROOT_OBSERVATION_IDS.incrementAndGet()}",
                    source = ElementSource.UIAUTOMATOR,
                    packageName = rootNodes.firstOrNull()?.packageName.orEmpty(),
                    windowId = null,
                    nodes = rootNodes,
                    maxNodes = nodeLimit,
                    truncated = rootNodes.size >= nodeLimit,
                    treeSignature = uiTreeSignature(rootNodes),
                )
            } else {
                null
            }
        } else {
            null
        }
        val nodes = elementObservation?.nodes.orEmpty()
        // 节点投影：紧凑线格式 + 体积预算（取舍见 UiNodeWire 的 KDoc）。
        // 超预算时少输出尾部节点，而不是让整段结果被溢写落盘 —— 一旦落盘，
        // 上下文里只剩预览，模型必须再 read_file 一次才拿得到节点，步数直接翻倍。
        val nodeProjection = UiNodeWire.toArray(
            nodes = nodes,
            foregroundPackage = if (virtual != null) virtualForeground else elementObservation?.packageName.orEmpty(),
            includeNodeId = false,
            includeBounds = true,
            budgetChars = UiNodeWire.OBSERVE_NODES_BUDGET_CHARS,
        )
        val nodesTruncatedByBudget = nodeProjection.shown < nodes.size
        val capture = if (includeScreenshot) {
            if (virtual != null) captureVirtualScreenshot(virtual) else captureScreenshot()
        } else {
            ScreenCapture.notRequested()
        }
        val image = capture.image
        val coordinateSpace = if (image?.width != null && image.height != null) {
            CoordinateSpace(
                screenWidth = display.first,
                screenHeight = display.second,
                screenshotWidth = image.width,
                screenshotHeight = image.height
            )
        } else {
            null
        }
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("screen", JSONObject().put("width", display.first).put("height", display.second))
            .put(
                "display",
                if (virtual == null) {
                    JSONObject().put("scope", "default").put("id", defaultDisplayId())
                } else {
                    JSONObject()
                        .put("scope", "virtual")
                        .put("id", virtual.displayId)
                        .put("virtual_id", virtual.id)
                        .put(
                            "note",
                            when {
                                virtualForeground.isEmpty() ->
                                    "这是虚拟屏，但当前没有任何任务：应用可能已被系统迁回主屏" +
                                        "（本机实测：应用内部打开新页面时会发生），也可能已经退出、崩溃或被系统清理。" +
                                        "先在主屏确认它是否还在运行；要在这块屏继续操作就用 virtual_display_launch 重新拉起。"

                                virtualForegroundAlive == false ->
                                    "$virtualForeground 的任务还留在这块屏上，但它的进程已经不在了" +
                                        "（多半是崩溃或被系统回收）；界面内容不会更新，先重新拉起再操作。"

                                else -> "这是虚拟屏；操作不会影响用户主屏"
                            },
                        )
                },
            )
            .put(
                "accessibility",
                JSONObject()
                    .put("available", accessibility != null)
                    // 虚拟屏上绝不能报主屏的包：那是"这块屏的前台"以外的事实，
                    // 会让模型以为虚拟屏里跑的是用户主屏那个应用（实测误导过）。
                    .put(
                        "package",
                        if (virtual != null) virtualForeground else accessibility?.currentPackageName().orEmpty(),
                    )
                    .put(
                        "note",
                        when {
                            virtual != null && elementObservation?.source == ElementSource.ACCESSIBILITY ->
                                "节点来自虚拟屏的无障碍窗口，支持 tap_element、scroll_element 等节点动作；" +
                                    "上面的 package 来自任务栈，坐标仍可用截图核对"

                            virtual != null && elementObservation != null ->
                                "节点来自 uiautomator；虚拟屏坐标动作请以截图为准"

                            virtual != null ->
                                "虚拟屏当前没有可读的无障碍节点（窗口未暴露或应用暂无内容）；" +
                                    "可改用截图 + 坐标动作"

                            accessibility != null ->
                                "节点来自无障碍服务，支持 tap_element、type_text、scroll_element 等稳定节点动作"

                            else ->
                                "无障碍服务未启用，节点来自 uiautomator；坐标工具会回退到 Root Shell"
                        }
                    )
            )
            .put(
                "coordinate_contract",
                if (coordinateSpace == null) {
                    JSONObject()
                        .put("note", "坐标工具必须显式填写 coordinate_space：ui_nodes 的 center/bounds 用 screen；本次未附图")
                } else {
                    JSONObject()
                        .put("recommended_coordinate_space", "normalized")
                        .put(
                            "screenshot",
                            JSONObject()
                                .put("width", coordinateSpace.screenshotWidth)
                                .put("height", coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "screen",
                            JSONObject()
                                .put("width", coordinateSpace.screenWidth)
                                .put("height", coordinateSpace.screenHeight)
                        )
                        .put(
                            "scale_to_screen",
                            JSONObject()
                                .put("x", coordinateSpace.screenWidth.toDouble() / coordinateSpace.screenshotWidth)
                                .put("y", coordinateSpace.screenHeight.toDouble() / coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "note",
                            "看截图定位用 normalized（0–999，相对整屏），不受模型侧图片缩放影响；ui_nodes 的 center/bounds 用 screen",
                        )
                }
            )
            .put("focus", focus)
            .put("observation_id", elementObservation?.id ?: JSONObject.NULL)
            .put("observation_source", elementObservation?.source?.wireName ?: JSONObject.NULL)
            .put("window_id", elementObservation?.windowId ?: JSONObject.NULL)
            .put(
                "capabilities",
                JSONObject()
                    .put("ui_tree", elementObservation != null)
                    .put("node_actions", elementObservation != null)
                    .put("screenshot", true)
                    .put("input_text", virtual == null || rootAvailable())
                    .put("system_panel", virtual == null)
                    .put("open_uri", virtual == null)
                    .put("launch_app", virtual == null || rootAvailable()),
            )
            .put("node_limit", elementObservation?.maxNodes ?: 0)
            .put("ui_tree_truncated", elementObservation?.truncated ?: false)
            .put("ui_nodes", nodeProjection.nodes)
            .put("ui_nodes_shown", nodeProjection.shown)
            .put("ui_nodes_truncated", nodesTruncatedByBudget)
            .put(
                "ui_diagnostics",
                uiDiagnostics(
                    virtualDisplayId = virtual?.displayId,
                    foregroundPackage = if (virtual != null) virtualForeground else "",
                    foregroundAlive = virtualForegroundAlive,
                    nodeCount = nodes.size,
                    source = elementObservation?.source,
                    uiDumpFailure = uiDumpFailure,
                ),
            )
            .put(
                "screenshot",
                if (image == null) {
                    capture.toJson().put("attached", false)
                } else {
                    capture.toJson()
                        .put("attached", true)
                        .put("mime_type", image.mimeType)
                        .put("bytes", image.bytes)
                        .put("width", image.width)
                        .put("height", image.height)
                }
            )
        if (nodesTruncatedByBudget) {
            json.put(
                "ui_nodes_note",
                "本次只输出 ${nodeProjection.shown}/${nodes.size} 个节点（按上下文体积裁剪尾部）。" +
                    "已输出节点的 index 可直接用于 tap_element；需要尾部节点时先 scroll 让它们进入视野再观察，" +
                    "不要为了看全树反复提高 max_nodes。",
            )
        }
        if (displayEventAge != null) {
            json.put("display_event_age_ms", displayEventAge)
            json.put(
                "display_event_note",
                "该虚拟屏距上次无障碍事件 ${displayEventAge}ms。" +
                    "数值很小（<1s）说明界面刚变过，此前截的图可能已过期；" +
                    "数值很大说明界面一直静止，同一张图可放心复用。" +
                    "注意：input 注入的点击不会产生事件，只有应用自身界面变化才会。",
            )
        }
        return Observation(json.toString(), image, elementObservation, coordinateSpace)
    }

    fun tap(x: Int, y: Int, displayId: Int? = null): String {
        if (displayId == null &&
            AgentAccessibilityService.current() == null &&
            !rootAvailable()
        ) {
            return accessibilityUnavailable()
        }
        validatePoint(x, y, displayId)
        // 虚拟屏必须直接走 input -d：无障碍手势只作用于默认屏。
        // 这里原本没有判 displayId，于是「在虚拟屏上点按」实际点在**用户主屏**上，
        // 还返回 ok=true —— 模型完全不会察觉自己动错了屏。实测缺陷，勿回退。
        if (displayId == null) {
            AgentAccessibilityService.current()?.let { service ->
                val result = service.gestureTap(x.toFloat(), y.toFloat())
                if (result.ok) {
                    waitForUiSettle("tap")
                    return nodeActionJson("tap", result)
                }
                if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                    return nodeActionJson("tap", result)
                }
                if (!rootAvailable()) return nodeActionJson("tap", result)
            }
        }
        return inputCommand("input tap $x $y", "tap", displayId)
    }

    fun longPress(x: Int, y: Int, durationMs: Int, displayId: Int? = null): String {
        if (displayId == null &&
            AgentAccessibilityService.current() == null &&
            !rootAvailable()
        ) {
            return accessibilityUnavailable()
        }
        validatePoint(x, y, displayId)
        val duration = durationMs.coerceIn(300, 3_000)
        // 同 tap：虚拟屏不能走无障碍手势（那只作用于默认屏）。
        if (displayId == null) {
            AgentAccessibilityService.current()?.let { service ->
                val result = service.gestureTap(x.toFloat(), y.toFloat(), duration.toLong())
                if (result.ok) {
                    waitForUiSettle("long_press")
                    return nodeActionJson("long_press", result.copy(method = "GESTURE_LONG_PRESS"))
                }
                if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                    return nodeActionJson("long_press", result)
                }
                if (!rootAvailable()) return nodeActionJson("long_press", result)
            }
        }
        return inputCommand("input swipe $x $y $x $y $duration", "long_press", displayId)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int? = null): String {
        if (displayId == null &&
            AgentAccessibilityService.current() == null &&
            !rootAvailable()
        ) {
            return accessibilityUnavailable()
        }
        validatePoint(x1, y1, displayId)
        validatePoint(x2, y2, displayId)
        val duration = durationMs.coerceIn(100, 2_000)
        // 同 tap：虚拟屏不能走无障碍手势（那只作用于默认屏），否则滑的是用户主屏。
        if (displayId == null) {
            AgentAccessibilityService.current()?.let { service ->
                val result = service.gestureSwipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    duration.toLong(),
                )
                if (result.ok) {
                    waitForUiSettle("swipe")
                    return nodeActionJson("swipe", result)
                }
                if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                    return nodeActionJson("swipe", result)
                }
                if (!rootAvailable()) return nodeActionJson("swipe", result)
            }
        }
        return inputCommand("input swipe $x1 $y1 $x2 $y2 $duration", "swipe", displayId)
    }

    fun scroll(direction: String, amount: String = "", displayId: Int? = null): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        val parsedAmount = ScrollAmount.parse(amount)
            ?: return scrollErrorJson("scroll", parsed, "INVALID_ARGUMENT", "amount 仅支持 small/page")
        AgentAccessibilityService.current()?.let { service ->
            // 虚拟屏也有无障碍树了：优先用节点滚动动作；拿不到根/滚动容器，
            // 或节点只支持手势（手势只作用于默认屏）时才退回 input -d 坐标滑动。
            val result = service.scrollCurrent(parsed, parsedAmount, displayId)
            // 虚拟屏上「无障碍这条路走不通」的任何理由都该退到 input -d。
            // 以前只认两个错误码（NO_ACTIVE_WINDOW / GESTURE_UNSUPPORTED），其余原样报错；
            // 实测同一调用时好时坏：失败那次 7ms 就返回了，说明压根没滑，
            // 对模型来说与「不支持」没区别，却白费一轮。虚拟屏上坐标滑动是等价且更可靠的
            // 那条路（结果仍由节点位移验证），所以这里对非默认屏一律兜底。
            // displayId != 0 即非默认屏（0 是 Display.DEFAULT_DISPLAY）。
            val onVirtualDisplay = displayId != null && displayId != 0
            val canFallbackToRoot = onVirtualDisplay || result.code == "NO_ACTIVE_WINDOW"
            if (displayId == null || result.ok || !canFallbackToRoot) {
                return scrollActionJson("scroll", result, displayId)
            }
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val beforeNodes = dumpUiNodes(120, displayId)
        val targetBounds = beforeNodes
            .asSequence()
            .filter(UiNode::scrollable)
            .maxByOrNull { node -> node.bounds.width().toLong() * node.bounds.height().toLong() }
            ?.bounds
            ?: screenContentBounds(displayId)
        return rootScroll(
            tool = "scroll",
            direction = parsed,
            bounds = targetBounds,
            beforeNodes = beforeNodes,
            maxNodes = 120,
            targetIndex = null,
            amount = parsedAmount,
            displayId = displayId,
        )
    }

    /**
     * 兜底失败时的收尾：把「试过兜底、以及它到底发生了什么」带回给模型。
     *
     * 关键点：兜底已经把坐标滑动发给了系统，就不能再回「虚拟屏不支持手势」那种与事实相反的结论 ——
     * 模型看到「本次未发送任何手势」会再滚一次，用户看到的就是「滚两屏」。所以这里用兜底自己的
     * code/message（常见是 ACTION_OUTCOME_UNKNOWN：发出去了、但没能确认位移），并用
     * `fallback_from` 保留原始的无障碍错误码。
     *
     * 不直接返回兜底的完整结果：它带 after/changes（几十个节点），而这里已经是失败路径，
     * 模型需要的是原因，不是又一棵 UI 树。
     */
    private fun withFallbackFailure(
        base: String,
        reason: String,
        fallback: JSONObject?,
        error: Throwable?,
    ): String {
        val json = runCatching { JSONObject(base) }.getOrElse { return base }
        if (fallback != null) {
            json.put("code", fallback.optString("code").ifBlank { "ACTION_OUTCOME_UNKNOWN" })
            json.put(
                "message",
                fallback.optString("message").ifBlank { "坐标滑动已发出，但无法确认位移；请先重新观察再决定" },
            )
            json.put("executor", "root")
            json.put("method", fallback.optString("method"))
            json.put("verified_by", fallback.optString("verified_by"))
            json.put("fallback_from", reason)
            // 兜底自己的诊断字段必须原样带上，否则「为什么没确认」在合并时就丢了：
            // swipes（补发过几次）、verification（配到几个锚点）、真实耗时。
            fallback.optInt("elapsed_ms", -1).takeIf { it >= 0 }?.let { json.put("fallback_elapsed_ms", it) }
            fallback.optJSONObject("verification")?.let { json.put("verification", it) }
            fallback.optInt("swipes", 1).takeIf { it > 1 }?.let { json.put("swipes", it) }
        } else {
            json.put(
                "fallback_error",
                (error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "兜底未返回结果").take(300),
            )
        }
        return json.toString()
    }

    fun inputText(text: String): String {
        if (text.isEmpty()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        if (text.length > MAX_INPUT_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "input_text 最多支持 $MAX_INPUT_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val result = service.inputTextFocused(text)
            if (result.ok) {
                waitForUiSettle("input_text")
                return nodeActionJson("input_text", result)
            }
            return nodeActionJson("input_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "input_text 需要无障碍服务确认真实输入焦点；本次未发送任何按键",
        )
    }

    /**
     * 在指定显示上输入文本。
     *
     * 虚拟屏上仍统一走 input -d：它不依赖焦点/节点，也不会把文本打到主屏；
     * 节点路径留给 tap_element / scroll_element。
     * 而且**不会报错**——它会安静地打到主屏的焦点上。所以虚拟屏必须走 `input -d N`。
     *
     * 三个已知限制，写在返回值里而不是假装支持：
     * 1. `input text` 只能打 ASCII 可打印字符；含非 ASCII 时改走「剪贴板 + KEYCODE_PASTE」，
     *    要求目标输入框已聚焦且接受粘贴。
     * 2. 清空只能用 CTRL+A + DEL 尽力而为，不是所有输入框都支持全选。
     * 3. 不走输入法组合，中文候选/联想不会出现（非 ASCII 靠粘贴，不靠 IME）。
     */
    /** 无障碍注入结果：ok=false 时带上可读原因，供上层决定是否回退。 */
    private data class DisplayTextInjection(
        val ok: Boolean,
        val method: String = "",
        val code: String = "",
        val message: String = "",
    )

    /**
     * 用无障碍在指定 display 上直接写文本。
     *
     * 刻意不使用主屏焦点与剪贴板：虚拟屏任务的目的是「不打扰用户主屏」，
     * 一旦点击其他应用的输入框就会把主屏输入法拉起来（打游戏时是致命打扰），
     * 写剪贴板则会覆盖用户自己的内容。
     */
    private fun injectTextToDisplay(
        displayId: Int,
        text: String,
        replace: Boolean,
    ): DisplayTextInjection {
        val service = AgentAccessibilityService.current()
            ?: return DisplayTextInjection(
                ok = false,
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "无障碍服务未连接",
            )
        val result = service.setTextOnDisplay(displayId, text, replace)
        return if (result.ok) {
            DisplayTextInjection(ok = true, method = result.method.ifBlank { "A11Y_SET_TEXT" })
        } else {
            DisplayTextInjection(
                ok = false,
                code = result.code.ifBlank { "A11Y_SET_TEXT_FAILED" },
                message = result.message.ifBlank { "无障碍写入被拒" },
            )
        }
    }

    fun textOnDisplay(
        context: Context,
        displayId: Int,
        text: String,
        clearFirst: Boolean,
        submit: Boolean,
    ): String {
        if (!rootAvailable()) return rootRequired()
        if (text.length > MAX_INPUT_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "虚拟屏文本输入最多支持 $MAX_INPUT_TEXT_CHARS 个字符")
        }
        val steps = ArrayList<String>(4)
        if (clearFirst) {
            // CTRL+A 全选后 DEL。尽力而为：部分自绘输入框不实现全选。
            steps += "input -d $displayId keycombination 113 29"
            steps += "input -d $displayId keyevent 67"
        }
        var method = "CLEAR_ONLY"
        if (text.isNotEmpty() && !RootShellText.isAsciiPrintable(text)) {
            // 非 ASCII 不能走 input text（KeyCharacterMap 返回 null，会 NPE）。
            // 首选无障碍 ACTION_SET_TEXT：直接写该屏窗口里的节点，
            // 不点击、不聚焦、不唤醒主屏输入法，也不覆盖用户剪贴板。
            val injected = injectTextToDisplay(displayId, text, replace = clearFirst)
            if (injected.ok) {
                if (submit) {
                    val submitResult = runSuText("input -d $displayId keyevent 66", timeoutSeconds = 10)
                    if (ShellActionOutcomePolicy.classify(submitResult.exitCode) ==
                        ShellActionOutcomePolicy.Outcome.FAILED
                    ) {
                        return errorJson(
                            "COMMAND_FAILED",
                            "文本已写入，但提交回车失败：${submitResult.output.ifBlank { "exit=${submitResult.exitCode}" }}",
                        )
                    }
                }
                waitForUiSettle("type_text")
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "type_text")
                    .put("display_id", displayId)
                    .put("executor", "accessibility")
                    .put("method", injected.method)
                    .put("chars", text.length)
                    .put("cleared", clearFirst)
                    .put("submitted", submit)
                    .toString()
            }
            // 无障碍不通时只有本应用还能自救：进程内注入同样不碰 IME/剪贴板。
            val foreground = runCatching { foregroundPackageOn(displayId) }.getOrDefault("")
            if (foreground == context.packageName) {
                val component = context.packageName + "/io.github.mangi.eta.ui.MainActivity"
                steps += "am start --display $displayId -n $component --es eta.inject.text " +
                    RootShellText.shellQuote(text)
                method = "APP_INJECT"
            } else {
                // 不点击其他应用的输入框、不写剪贴板：两条都会打扰主屏上的用户。
                return errorJson(
                    injected.code,
                    "虚拟屏上无法写入非 ASCII 文本：${injected.message}。" +
                        "可选做法：先 tap_element 点开该输入框再重试；" +
                        "或改用 ASCII 文本；Eta 自身界面可用进程内注入",
                )
            }
        } else if (text.isNotEmpty()) {
            steps += "input -d $displayId text " +
                RootShellText.shellQuote(RootShellText.encodeInputText(text))
            method = "INPUT_TEXT"
        }
        if (submit) steps += "input -d $displayId keyevent 66"
        for (command in steps) {
            val result = runSuText(command, timeoutSeconds = 10)
            if (result.output == "ROOT_REQUIRED" && result.exitCode == -1) return rootRequired()
            if (ShellActionOutcomePolicy.classify(result.exitCode) ==
                ShellActionOutcomePolicy.Outcome.FAILED
            ) {
                return errorJson(
                    "COMMAND_FAILED",
                    "虚拟屏输入命令失败：${result.output.ifBlank { "exit=${result.exitCode}" }}",
                )
            }
        }
        if (steps.isNotEmpty()) waitForUiSettle("type_text")
        return JSONObject()
            .put("ok", true)
            .put("tool", "type_text")
            .put("display_id", displayId)
            .put("executor", "root")
            .put("method", method)
            .put("chars", text.length)
            .put("cleared", clearFirst)
            .put("submitted", submit)
            .toString()
    }

    /**
     * 当前所有任务及其所在屏。
     *
     * 用于「启动前预检」与「启动后核验」：`am start --display N` 对已有任务的应用
     * 并不会新建任务，而是把那个任务搬过来或把用户主屏上的实例顶到前台
     * （真机实测），所以必须先知道它现在在哪，启动后再确认到底落在哪。
     */
    fun tasksNow(): List<DisplayTask> = tasksOrNull().orEmpty()

    /** 读不到任务时返回 null，而不是空列表：调用方要能区分「确实没有任务」与「查询失败」。 */
    fun tasksOrNull(): List<DisplayTask>? {
        if (!rootAvailable()) return null
        val result = runSuText("am stack list", timeoutSeconds = 8)
        if (result.exitCode != 0) return null
        return VirtualDisplayTasks.parse(result.output)
    }

    /** 某块屏当前前台的包名；读不到时返回空串。 */
    fun foregroundPackageOn(displayId: Int): String =
        VirtualDisplayTasks.foregroundOn(tasksNow(), displayId)

    /**
     * 释放虚拟屏前，把这块屏上的任务先移除（不是搬到主屏）。
     *
     * 为什么不能直接 `VirtualDisplay.release()`：系统销毁显示时，挂在上面的任务
     * 可能被重新挂到默认屏 —— 用户正在看的界面会突然被虚拟屏里的应用顶掉。
     * 先 `am stack remove` 掉这些根任务，任务随显示一起消失，不会迁移。
     *
     * 只移除任务，不 force-stop：应用的进程和后台状态仍由系统按常规回收。
     * 返回移除前后各有多少任务，调用方据此决定是否真的释放显示。
     */
    fun closeTasksOnDisplay(displayId: Int): TaskCleanup {
        if (!rootAvailable()) return TaskCleanup(0, 0, rootAvailable = false)
        // 查询失败（null）不能当成「没有任务」，否则会在任务未知时直接释放显示。
        val before = tasksOrNull() ?: return TaskCleanup(0, 1, rootAvailable = true)
        val onDisplay = before.filter { it.displayId == displayId }
        if (onDisplay.isEmpty()) return TaskCleanup(0, 0, rootAvailable = true)
        // 同一 RootTask 下可能挂着多个 activity/task，只移除一次。
        onDisplay.map { it.rootTaskId }.distinct().forEach { rootTaskId ->
            val result = runSuText("am stack remove $rootTaskId", timeoutSeconds = 8)
            if (result.exitCode != 0) {
                logger.warn(
                    "Agent virtual display task cleanup outcome=failed displayId=$displayId " +
                        "rootTaskId=$rootTaskId exit=${result.exitCode}",
                )
            }
        }
        // `am stack remove` 返回成功时任务可能仍在 ActivityManager 的任务列表里短暂可见；
        // 立刻查询会得到 remaining=1，进而拒绝释放。实测必须给系统一点时间收口。
        var remaining = onDisplay.size
        val deadline = SystemClock.elapsedRealtime() + TASK_CLEANUP_SETTLE_MS
        do {
            Thread.sleep(TASK_CLEANUP_POLL_MS)
            val after = tasksOrNull()
                ?: return TaskCleanup(0, onDisplay.size, rootAvailable = true)
            remaining = after.count { it.displayId == displayId }
        } while (remaining > 0 && SystemClock.elapsedRealtime() < deadline)
        if (remaining > 0) {
            logger.warn(
                "Agent virtual display task cleanup outcome=remaining displayId=$displayId " +
                    "remaining=$remaining",
            )
        }
        return TaskCleanup(
            closedTasks = (onDisplay.size - remaining).coerceAtLeast(0),
            remainingTasks = remaining,
            rootAvailable = true,
        )
    }

    data class TaskCleanup(
        val closedTasks: Int,
        val remainingTasks: Int,
        val rootAvailable: Boolean,
    ) {
        val ok: Boolean get() = !rootAvailable || remainingTasks == 0
    }

    fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
    ): String {
        if (text.length > MAX_REPLACE_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "replace_text 最多支持 $MAX_REPLACE_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val snapshot = observation?.accessibilitySnapshot
            val result = service.setTextNode(snapshot, index, text)
            if (result.ok) {
                waitForUiSettle("replace_text")
                return nodeActionJson("replace_text", result)
            }
            return nodeActionJson("replace_text", result)
        }
        return errorJson("ACCESSIBILITY_UNAVAILABLE", "replace_text 需要先启用 Eta Agent 无障碍服务")
    }

    fun clearText(index: Int?, observation: ElementObservation?): String =
        replaceText("", index, observation).let { result ->
            val json = JSONObject(result)
            json.put("tool", "clear_text").toString()
        }

    fun tapElement(observation: ElementObservation, index: Int): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
            val result = service.clickNode(snapshot, index)
            if (result.ok) {
                waitForUiSettle("tap")
                return nodeActionJson("tap_element", result)
            }
            // 虚拟屏：节点不接受 ACTION_CLICK 时，无障碍手势兜底会落到用户主屏，
            // 所以以前这里只能报错，模型得再补一次坐标 tap。改为直接用节点中心走 input -d。
            if (VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                    result.code,
                    snapshot.displayId,
                    rootAvailable(),
                )
            ) {
                fallbackToRootNodeAction(observation, index, durationMs = null)?.let { fallback ->
                    waitForUiSettle("tap")
                    return fallback.put("tool", "tap_element").toString()
                }
            }
            return nodeActionJson("tap_element", result)
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val node = resolved.node
        return tap(node.centerX, node.centerY).rewriteTool("tap_element")
    }

    fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
            val result = service.longClickNode(snapshot, index, durationMs.toLong())
            if (result.ok) {
                waitForUiSettle("long_press")
                return nodeActionJson("long_press_element", result)
            }
            // 同 tap_element：虚拟屏上节点不接受 ACTION_LONG_CLICK 时改走 input -d 长按。
            if (VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                    result.code,
                    snapshot.displayId,
                    rootAvailable(),
                )
            ) {
                fallbackToRootNodeAction(observation, index, durationMs = durationMs)?.let { fallback ->
                    waitForUiSettle("long_press")
                    return fallback.put("tool", "long_press_element").toString()
                }
            }
            return nodeActionJson("long_press_element", result)
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val node = resolved.node
        return longPress(node.centerX, node.centerY, durationMs).rewriteTool("long_press_element")
    }

    /**
     * 虚拟屏上节点动作被「手势只作用于默认屏」挡住时的坐标兜底。
     *
     * 用最近一次观察里该节点的中心点走 `input -d`：不重新 dump 树（省一次几百毫秒），
     * 也不会因为漏传 display_id 点到用户主屏。
     */
    private fun fallbackToRootNodeAction(
        observation: ElementObservation,
        index: Int,
        durationMs: Int?,
    ): JSONObject? {
        val node = observation.nodes.firstOrNull { candidate -> candidate.index == index } ?: return null
        if (node.bounds.isEmpty) return null
        val displayId = observation.accessibilitySnapshot?.displayId ?: return null
        // 盲点前的安全判定：中心点若落在别的节点上，那个「中心」其实是别人的落点
        // （真机事故：设置首页根 ScrollView 的中心正好落在快捷开关行，把飞行模式打开了，
        //  设备当场断网）。这种情况不发手势，直接把真正会被点到的节点报回去。
        val unsafeHit = BlindTapSafetyPolicy.centerHitOnOtherNode(
            targetIndex = index,
            target = node.bounds.toBlindTapBox(),
            candidates = observation.nodes.map { candidate ->
                BlindTapSafetyPolicy.Candidate(
                    index = candidate.index,
                    box = candidate.bounds.toBlindTapBox(),
                    interactive = candidate.clickable || candidate.editable || candidate.checked != null,
                )
            },
        )
        if (unsafeHit != null) {
            return JSONObject()
                .put("ok", false)
                .put("method", "ROOT_COORDINATE_FALLBACK")
                .put("code", BlindTapSafetyPolicy.UNSAFE_CODE)
                .put("fallback_reason", VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED)
                .put("blocked_target_index", unsafeHit.index)
                .put("blocked_target_interactive", unsafeHit.interactive)
                .put(
                    "message",
                    "节点 #$index 的中心点落在节点 #${unsafeHit.index} 上" +
                        (if (unsafeHit.interactive) "（那是可交互控件，盲点可能误触开关）" else "") +
                        "：已取消这次盲点，请改点 #${unsafeHit.index} 或它的文本子节点",
                )
        }
        // 页面还在切换时打进去的坐标动作会落在过渡中的窗口上（点了/滑了都没反应），
        // 所以先等这屏刷稳再发。
        awaitStableTree(displayId)
        val raw = if (durationMs == null) {
            tap(node.centerX, node.centerY, displayId)
        } else {
            longPress(node.centerX, node.centerY, durationMs, displayId)
        }
        return runCatching { JSONObject(raw) }.getOrNull()
            ?.put("method", "ROOT_COORDINATE_FALLBACK")
            ?.put("fallback_reason", VirtualDisplayFallbackPolicy.GESTURE_UNSUPPORTED)
    }

    fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
        amount: String = "",
    ): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll_element",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        val parsedAmount = ScrollAmount.parse(amount)
            ?: return scrollErrorJson("scroll_element", parsed, "INVALID_ARGUMENT", "amount 仅支持 small/page")
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return scrollErrorJson(
                    "scroll_element",
                    parsed,
                    "ACCESSIBILITY_UNAVAILABLE",
                    "无障碍服务已断开，请重新观察屏幕",
                )
            val nodeResult = service.scrollNode(snapshot, index, parsed, parsedAmount)
            if (!VirtualDisplayFallbackPolicy.mayFallbackToRootCoordinates(
                    nodeResult.code,
                    snapshot.displayId,
                    rootAvailable(),
                )
            ) {
                return scrollActionJson("scroll_element", nodeResult, snapshot.displayId)
            }
            // 虚拟屏上「节点只支持手势」不是终点：改走 scroll 里已验证过的 input -d 坐标滚动。
            // 这样 scroll_element 在虚拟屏上也能真正滚起来，而不是让模型自己换算坐标再补一次 swipe。
            // 页面切换动画期间发出去的滑动会打在过渡上（发了也不动），先等这屏刷稳。
            awaitStableTree(snapshot.displayId)
            val attempt = runCatching { JSONObject(scroll(direction, amount, snapshot.displayId)) }
            val fallback = attempt.getOrNull()
            if (fallback != null && fallback.optBoolean("ok")) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "scroll_element")
                    .put("direction", parsed.name.lowercase())
                    .put("display_id", snapshot.displayId)
                    .put("method", "ROOT_SCROLL_FALLBACK")
                    .put("fallback_reason", nodeResult.code)
                    .put("fallback", fallback)
                    .toString()
            }
            // 兜底也失败：不能像以前那样直接丢掉，只把原始错误码回给模型 ——
            // 那样看不出「已经试过另一条路」，下次它还会原样重试。这里把尝试与原因一并带回。
            return withFallbackFailure(
                base = scrollActionJson("scroll_element", nodeResult, snapshot.displayId),
                reason = nodeResult.code,
                fallback = fallback,
                error = attempt.exceptionOrNull(),
            )
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return scrollErrorJson(
                "scroll_element",
                parsed,
                "STALE_NODE",
                "无法在当前界面唯一确认滚动目标，请重新观察屏幕",
            )
        val node = resolved.node
        if (!node.scrollable) {
            return scrollErrorJson(
                "scroll_element",
                parsed,
                "NOT_SCROLLABLE",
                "指定节点不可滚动",
            )
        }
        return rootScroll(
            tool = "scroll_element",
            direction = parsed,
            bounds = node.bounds,
            beforeNodes = resolved.currentNodes,
            maxNodes = observation.maxNodes,
            targetIndex = index,
            amount = parsedAmount,
            displayId = null,
        )
    }

    fun pressKey(button: String, displayId: Int? = null): String {
        val normalized = button.uppercase()
        if (normalized == "PASTE" && !rootAvailable()) return rootRequired()
        if (displayId == null) AgentAccessibilityService.current()?.let { service ->
            when (normalized) {
                "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS" -> {
                    val actionResult = service.globalActionResult(normalized)
                    if (actionResult.ok) {
                        waitForUiSettle("press_key")
                        return okJson("press_key", "accessibility").let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                        return nodeActionJson("press_key", actionResult).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    if (!rootAvailable()) return nodeActionJson("press_key", actionResult)
                }
                "ENTER" -> {
                    val result = service.imeEnter()
                    if (result.ok) {
                        waitForUiSettle("press_key")
                        return nodeActionJson("press_key", result).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    return nodeActionJson("press_key", result).let {
                        JSONObject(it).put("button", normalized).toString()
                    }
                }
            }
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val keyCode = when (normalized) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "RECENTS" -> 187
            "PASTE" -> 279
            "NOTIFICATIONS", "QUICK_SETTINGS" -> return errorJson(
                "UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                "通知面板与快捷设置面板只属于主屏，虚拟屏上不可用",
            )
            else -> return errorJson("INVALID_ARGUMENT", "button 仅支持 BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS")
        }
        return inputCommand("input keyevent $keyCode", "press_key", displayId)
    }

    fun waitMs(durationMs: Int): String {
        val duration = durationMs.coerceIn(100, 30_000)
        Thread.sleep(duration.toLong())
        return JSONObject()
            .put("ok", true)
            .put("tool", "wait")
            .put("duration_ms", duration)
            .toString()
    }

    fun waitForText(text: String, timeoutMs: Int, includeDesc: Boolean, matchMode: String, displayId: Int? = null): String {
        val needle = text.trim()
        if (needle.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val service = AgentAccessibilityService.current()
            if (service == null && !rootAvailable()) return accessibilityUnavailable()
            val nodes = service
                ?.queryNodes(120, displayId)
                ?.map { it.toUiNode() }
                ?.takeIf { it.isNotEmpty() }
                ?: dumpUiNodes(120, displayId)
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text, node.desc) else listOf(node.text)
                haystacks.any { value -> matches(value, needle, matchMode) }
            }
            if (match != null) {
                val matchedNode = UiNodeWire.toArray(
                    nodes = listOf(match),
                    foregroundPackage = match.packageName,
                    includeNodeId = true,
                    includeBounds = true,
                ).nodes.getJSONObject(0)
                matchedNode.remove("index")
                matchedNode.put("actionable", false)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_text")
                    .put("attempts", attempts)
                    .put("matched_node", matchedNode)
                    .put("note", "等待查询不会发布元素快照；如需节点动作，请重新调用 observe_screen")
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_text")
            .put("code", "TIMEOUT")
            .put("message", "等待文本超时：$needle")
            .put("attempts", attempts)
            .toString()
    }

    fun waitForPackage(packageName: String, timeoutMs: Int, displayId: Int? = null): String {
        val target = packageName.trim()
        if (target.isBlank()) return errorJson("INVALID_ARGUMENT", "package_name 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        var lastPackage = ""
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val current = if (displayId != null) {
                // 虚拟屏：任务栈是唯一可靠的前台来源；无障碍只跟踪窗口，空屏时没有 activity root。
                runCatching { foregroundPackageOn(displayId) }.getOrDefault("")
            } else {
                val service = AgentAccessibilityService.current()
                if (service == null && !rootAvailable()) return accessibilityUnavailable()
                service?.currentPackageName().orEmpty().ifBlank {
                    focusedWindow().optString("package")
                }
            }
            lastPackage = current
            if (current == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .put("display_id", displayId ?: JSONObject.NULL)
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_package")
            .put("code", "TIMEOUT")
            .put("message", "等待应用前台超时：$target")
            .put("last_package", lastPackage)
            .put("attempts", attempts)
            .put("display_id", displayId ?: JSONObject.NULL)
            .toString()
    }

    fun clipboardSet(context: Context, text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "剪贴板文本最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        val serviceResult = AgentAccessibilityService.current()?.copyToClipboard(text)
        val ok = serviceResult?.ok ?: runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("eta_agent", text))
            true
        }.getOrDefault(false)
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", text.length)
        if (!ok) {
            json
                .put("code", serviceResult?.code?.ifBlank { null } ?: "CLIPBOARD_WRITE_FAILED")
                .put(
                    "message",
                    serviceResult?.message?.ifBlank { null } ?: "写入系统剪贴板失败",
                )
        }
        return json.toString()
    }

    fun clipboardGet(context: Context): String {
        val serviceResult = AgentAccessibilityService.current()?.readClipboard()
        val result = serviceResult ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { clipboard.primaryClip }.getOrNull()
            if (clip == null || clip.itemCount <= 0) {
                AgentAccessibilityService.ClipboardReadResult.failure()
            } else {
                AgentAccessibilityService.ClipboardReadResult(
                    ok = true,
                    text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty(),
                )
            }
        }
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", "get_clipboard")
            .put("text", result.text.take(8_000))
            .put("truncated", result.text.length > 8_000)
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", "剪贴板为空，或当前应用无权读取剪贴板")
        }
        return json.toString()
    }

    fun pasteText(text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "paste_text 最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val result = service.pasteText(text)
            if (result.ok) {
                waitForUiSettle("input_text")
                return nodeActionJson("paste_text", result)
            }
            return nodeActionJson("paste_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "paste_text 需要无障碍服务确认真实输入焦点；本次未修改剪贴板",
        )
    }

    fun openSystemPanel(panel: String): String {
        val normalized = panel.lowercase()
        val accessibilityAction = when (normalized) {
            "notifications", "notification" -> "NOTIFICATIONS"
            "quick_settings", "quicksettings", "settings" -> "QUICK_SETTINGS"
            else -> return errorJson("INVALID_ARGUMENT", "panel 仅支持 notifications/quick_settings")
        }
        AgentAccessibilityService.current()?.let { service ->
            val actionResult = service.globalActionResult(accessibilityAction)
            if (actionResult.ok) {
                waitForUiSettle("press_key")
                return okJson("open_system_panel", "accessibility").let {
                    JSONObject(it).put("panel", normalized).toString()
                }
            }
            if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                return nodeActionJson("open_system_panel", actionResult).let {
                    JSONObject(it).put("panel", normalized).toString()
                }
            }
            if (!rootAvailable()) return nodeActionJson("open_system_panel", actionResult)
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val command = when (accessibilityAction) {
            "NOTIFICATIONS" -> "cmd statusbar expand-notifications"
            else -> "cmd statusbar expand-settings"
        }
        return inputCommand(command, "open_system_panel")
    }

    /**
     * 虚拟屏截图：从 ImageReader 取最新一帧。
     * 不能走 screencap —— 它只能截默认屏；也不能走无障碍窗口截图 —— 那只覆盖默认屏的窗口。
     */
    private fun captureVirtualScreenshot(session: VirtualDisplayController.Session): ScreenCapture {
        val startedAt = SystemClock.elapsedRealtime()
        val frame = virtualDisplays?.capture(session.id)
        val bytes = frame?.png
        if (bytes == null || bytes.isEmpty()) {
            logger.debug {
                "Agent device action=capture_screenshot outcome=empty source=virtual " +
                    "virtualId=${session.id} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
            }
            return ScreenCapture.failed(source = "virtual", code = "VIRTUAL_FRAME_NOT_READY")
        }
        val image = runCatching { AgentImageCodec.fromScreenBytes(bytes, source = "virtual") }
            .onFailure { throwable ->
                logger.warn(
                    "Agent device action=encode_screenshot outcome=failed source=virtual " +
                        "type=${throwable.javaClass.simpleName}"
                )
            }
            .getOrNull() ?: return ScreenCapture.failed(source = "virtual")
        return ScreenCapture(
            image = image,
            source = "virtual",
            complete = true,
            partial = false,
            expectedWindows = 1,
            capturedWindows = 1,
            // 屏幕静止时拿到的是上一帧：图是对的，但不是"此刻"的，必须让调用方看得见。
            stale = frame?.cached == true,
        )
    }

    /** 把模型传来的 display_id 解析成受管虚拟屏；null 表示主屏，未知 id 抛参数错误。 */
    private fun resolveVirtualSession(displayId: Int?): VirtualDisplayController.Session? {
        if (displayId == null) return null
        val controller = virtualDisplays ?: error("虚拟屏能力未初始化")
        val session = controller.findByDisplayId(displayId)
            ?: error("未知 display_id=$displayId；请先用 virtual_display_list 查看可用虚拟屏")
        // 任何落到这块屏上的动作（截图/点击/滑动）都算「在用」：空闲回收据此判断，
        // 否则一块正在被连续操作的屏会在 10 分钟后被当成闲置收走。
        controller.touch(session.id)
        return session
    }

    private fun defaultDisplayId(): Int = virtualDisplays?.defaultDisplayId ?: 0

    private fun captureScreenshot(): ScreenCapture {
        val excludedPackages = screenshotExcludedPackages()
        // Android 14 起优先逐窗口截图，排除 Eta 浮层；旧系统只保留满足排除合同的 Root 回退。
        val service = AgentAccessibilityService.current()
        val windowScreenshotSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (service != null && windowScreenshotSupported) {
            val captureStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                service.captureScreenshotExcludingOverlays(excludedPackages)
            }.getOrNull()
            val bitmap = result?.bitmap
            if (bitmap != null) {
                val capturedAt = SystemClock.elapsedRealtime()
                val image = try {
                    runCatching {
                        AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
                    }.onFailure { throwable ->
                        logger.warn(
                            "Agent device action=encode_screenshot outcome=failed " +
                                "source=accessibility type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    bitmap.recycle()
                }
                if (image != null) {
                    logger.debug {
                        "Agent device action=capture_screenshot outcome=completed source=accessibility " +
                            "capture_ms=${capturedAt - captureStartedAt} " +
                            "encode_ms=${SystemClock.elapsedRealtime() - capturedAt} " +
                            "image=${image.width}x${image.height} bytes=${image.bytes}"
                    }
                }
                if (image != null && image.bytes > 0) {
                    return ScreenCapture(
                        image = image,
                        source = "accessibility",
                        complete = result.complete,
                        partial = result.partial,
                        expectedWindows = result.expectedWindows,
                        capturedWindows = result.capturedWindows,
                        missingWindowIds = result.missingWindowIds,
                        failureCodes = result.failureCodes,
                        timedOut = result.timedOut,
                        criticalWindowMissing = result.criticalWindowMissing,
                    )
                }
            }
            if (result?.criticalWindowMissing == true) {
                logger.warn(
                    "Agent device action=capture_screenshot outcome=failed " +
                        "reason=critical_window_missing failures=${result.failureCodes.size}"
                )
                return ScreenCapture(
                    image = null,
                    source = "accessibility",
                    complete = false,
                    partial = false,
                    expectedWindows = result.expectedWindows,
                    capturedWindows = result.capturedWindows,
                    missingWindowIds = result.missingWindowIds,
                    failureCodes = result.failureCodes,
                    timedOut = result.timedOut,
                    criticalWindowMissing = true,
                )
            }
        }
        if (
            !rootAvailable() ||
            !ScreenshotOutcomePolicy.mayFallbackToRoot(
                excludedPackagesPresent = excludedPackages.isNotEmpty(),
                criticalWindowMissing = false,
            )
        ) {
            val code = if (!windowScreenshotSupported) {
                "WINDOW_SCREENSHOT_UNSUPPORTED"
            } else {
                "SCREENSHOT_UNAVAILABLE"
            }
            logger.debug {
                "Agent device action=capture_screenshot outcome=unavailable " +
                    "code=$code excludedPackages=${excludedPackages.size}"
            }
            return ScreenCapture.failed(source = "accessibility", code = code)
        }
        logger.debug {
            "Agent device action=capture_screenshot outcome=fallback source=root"
        }
        // 无需排除入口窗口时才回退 root screencap；否则宁可返回无图，也不把错误浮窗交给模型。
        val result = runSuBytes("screencap -p", timeoutSeconds = 8)
        if (result.exitCode != 0 || result.output.isEmpty()) {
            logger.warn(
                "Agent device action=capture_screenshot outcome=failed source=root " +
                    "exitCode=${result.exitCode} outputBytes=${result.output.size} " +
                    "errorChars=${result.stderr.length}"
            )
            return ScreenCapture.failed(source = "root")
        }
        val encodeStartedAt = SystemClock.elapsedRealtime()
        val image = runCatching {
            AgentImageCodec.fromScreenBytes(result.output, source = "screen")
        }.onFailure { throwable ->
            logger.warn(
                "Agent device action=encode_screenshot outcome=failed source=root " +
                    "type=${throwable.javaClass.simpleName}"
            )
        }.getOrNull() ?: return ScreenCapture.failed(source = "root")
        logger.debug {
            "Agent device action=capture_screenshot outcome=completed source=root " +
                "encode_ms=${SystemClock.elapsedRealtime() - encodeStartedAt} " +
                "image=${image.width}x${image.height} bytes=${image.bytes}"
        }
        return ScreenCapture(
            image = image,
            source = "root",
            complete = true,
            partial = false,
            expectedWindows = 1,
            capturedWindows = 1,
        )
    }

    private fun dumpUiNodes(maxNodes: Int, displayId: Int? = null): List<UiNode> =
        dumpUiNodesReported(maxNodes, displayId).nodes

    /**
     * 读 UI 树，并把**失败原因**带出来，而不是静默返回空列表。
     *
     * 空节点在观测结果里曾经等于「什么都没说」：模型分不清「界面真没有节点」与
     * 「读取本身失败了（root 掉线 / uiautomator 超时 / 无障碍拿不到窗口）」，
     * 只能猜；真机上我就把「应用已崩溃」误判成「进程没启动」。
     */
    private fun dumpUiNodesReported(maxNodes: Int, displayId: Int? = null): UiDumpResult {
        if (displayId != null) {
            // 虚拟屏：uiautomator dump 只覆盖默认屏，改用无障碍的按 display 窗口根节点。
            val service = AgentAccessibilityService.current()
                ?: return UiDumpResult(emptyList(), "无障碍服务不可用（虚拟屏的 UI 树只能由无障碍提供）")
            val snapshot = service.captureNodeSnapshot(maxNodes, displayId)
                ?: return UiDumpResult(emptyList(), "无障碍拿不到 display $displayId 的窗口根节点（窗口未暴露或已销毁）")
            return UiDumpResult(snapshot.nodes.map { node -> node.toUiNode() }, null)
        }
        if (!rootAvailable()) return UiDumpResult(emptyList(), "Root 授权不可用，无法用 uiautomator")
        val result = runSuText(
                "uiautomator dump --compressed /data/local/tmp/eta_window.xml >/dev/null && " +
                "cat /data/local/tmp/eta_window.xml && rm -f /data/local/tmp/eta_window.xml",
            timeoutSeconds = 10
        )
        if (result.exitCode != 0 || result.output.isBlank()) {
            logger.warn(
                "Agent device action=dump_ui outcome=failed source=root " +
                    "exitCode=${result.exitCode} outputChars=${result.output.length}"
            )
            return UiDumpResult(
                emptyList(),
                "uiautomator dump 失败（exit=${result.exitCode}，输出 ${result.output.length} 字符）",
            )
        }
        return UiDumpResult(parseUiNodes(result.output, maxNodes), null)
    }

    private data class UiDumpResult(val nodes: List<UiNode>, val failure: String?)

    /**
     * 某个包此刻有没有活着的进程。
     *
     * `am stack list` 只反映任务栈：应用崩溃或被系统回收后，任务记录仍然留着，
     * 于是「前台包名」会指向一个已经没有进程的应用。返回 null 表示查不出来 ——
     * 不能把「查不到」当成「没有进程」，那是另一种假话。
     *
     * 用 pidof 而不是 ps | grep：pidof 的退出码直接表达「有没有」，且不会被
     * 命令自身的报错混进结果；包名先做字符校验，避免拼进 shell 的命令被注入。
     */
    fun packageHasProcess(packageName: String): Boolean? {
        if (packageName.isBlank()) return false
        if (!packageName.matches(Regex("[A-Za-z0-9_.:]+"))) return null
        if (!rootAvailable()) return null
        val result = runSuText("pidof $packageName", timeoutSeconds = 4)
        return when (result.exitCode) {
            0 -> result.output.isNotBlank()
            1 -> false
            else -> null
        }
    }

    /**
     * 「为什么没有节点」必须有结论。
     *
     * 只回 `ui_nodes: []` 时，调用方只能猜：无障碍没开？uiautomator 失败？应用崩了？
     * 猜错的代价是重复同样的操作，或者把已经死掉的界面当成还在加载。
     */
    private fun uiDiagnostics(
        virtualDisplayId: Int?,
        foregroundPackage: String,
        foregroundAlive: Boolean?,
        nodeCount: Int,
        source: ElementSource?,
        uiDumpFailure: String?,
    ): JSONObject {
        val accessibility = AgentAccessibilityService.current()
        val diagnostics = JSONObject()
            .put("node_source", source?.wireName ?: JSONObject.NULL)
            .put("node_count", nodeCount)
            .put("accessibility_available", accessibility != null)
            .put("root_available", rootAvailable())
        if (virtualDisplayId != null) diagnostics.put("display_id", virtualDisplayId)
        if (foregroundPackage.isNotEmpty()) {
            diagnostics.put("foreground_package", foregroundPackage)
            diagnostics.put("foreground_process_alive", foregroundAlive ?: JSONObject.NULL)
        }
        if (uiDumpFailure != null) diagnostics.put("uiautomator_failure", uiDumpFailure)
        if (nodeCount == 0) {
            val deadProcess = foregroundPackage.isNotEmpty() && foregroundAlive == false
            diagnostics.put(
                "empty_reason",
                when {
                    deadProcess -> "前台包 $foregroundPackage 的任务还在，但进程已经不在（崩溃或被系统回收）"
                    uiDumpFailure != null -> "UI 树读取失败：$uiDumpFailure"
                    accessibility == null && virtualDisplayId != null ->
                        "虚拟屏的 UI 树只能来自无障碍服务，而当前无障碍服务不可用"
                    accessibility == null -> "无障碍服务不可用，uiautomator 也没有读到节点"
                    else -> "窗口可能还在加载，或当前界面没有可读节点（全屏视频、游戏画面、纯图片）"
                },
            )
            diagnostics.put(
                "hint",
                if (deadProcess) {
                    "先重新拉起应用（launch_app / virtual_display_launch），或释放这块虚拟屏；" +
                        "不要继续在这个界面上点坐标"
                } else {
                    "改用 include_screenshot=true 看画面，或先 wait 再重新观察；坐标动作以截图为准"
                },
            )
        }
        return diagnostics
    }

    private fun parseUiNodes(xml: String, maxNodes: Int): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && nodes.size < maxNodes) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val visible = parser.attr("visible-to-user") != "false"
                val bounds = parser.attr("bounds").toRectOrNull()
                if (visible && bounds != null && bounds.width() > 2 && bounds.height() > 2) {
                    val text = parser.attr("text").take(120)
                    val desc = parser.attr("content-desc").take(120)
                    val clickable = parser.attr("clickable").toBoolean()
                    val scrollable = parser.attr("scrollable").toBoolean()
                    val focused = parser.attr("focused").toBoolean()
                    val enabled = parser.attr("enabled") != "false"
                    if (text.isNotBlank() || desc.isNotBlank() || clickable || scrollable || focused) {
                        nodes += UiNode(
                            index = nodes.size,
                            text = text,
                            desc = desc,
                            className = parser.attr("class"),
                            packageName = parser.attr("package"),
                            viewId = parser.attr("resource-id"),
                            bounds = bounds,
                            clickable = clickable,
                            longClickable = parser.attr("long-clickable").toBoolean(),
                            scrollable = scrollable,
                            focused = focused,
                            editable = parser.attr("class").contains("EditText", ignoreCase = true),
                            password = parser.attr("password").toBoolean(),
                            enabled = enabled,
                            checked = parser.attr("checked").toBoolean()
                                .takeIf { parser.attr("checkable").toBoolean() },
                            selected = parser.attr("selected").toBoolean(),
                        )
                    }
                }
            }
            event = parser.next()
        }
        return nodes
    }

    private fun focusedWindow(): JSONObject {
        if (!rootAvailable()) return JSONObject().put("source", "unavailable")
        val result = runSuText("dumpsys window", timeoutSeconds = 8)
        val focused = FocusedWindowParser.parse(result.output)
        return JSONObject()
            .put("raw", focused?.rawLine?.take(240).orEmpty())
            .put("component", focused?.component.orEmpty())
            .put("package", focused?.packageName.orEmpty())
    }

    private fun screenSize(): Pair<Int, Int> {
        AgentAccessibilityService.current()?.displaySize()?.let { return it }
        if (!rootAvailable()) throw DeviceControlUnavailableException()
        val result = runSuText("wm size", timeoutSeconds = 5)
        return AndroidDisplaySizeParser.parse(result.output)
            ?: error("无法读取屏幕尺寸：${result.output.take(160)}")
    }

    private fun screenContentBounds(displayId: Int? = null): Rect {
        val virtual = displayId?.let { resolveVirtualSession(it) }
        val (width, height) = if (virtual != null) virtual.width to virtual.height else screenSize()
        return Rect(
            0,
            (height * 0.1f).toInt(),
            width,
            (height * 0.9f).toInt(),
        )
    }

    private fun rootScroll(
        tool: String,
        direction: ScrollDirection,
        bounds: Rect,
        beforeNodes: List<UiNode>,
        maxNodes: Int,
        targetIndex: Int?,
        amount: ScrollAmount = ScrollAmount.PAGE,
        displayId: Int? = null,
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        val gesture = direction.gestureWithin(bounds, amount)
            ?: return scrollErrorJson(
                tool,
                direction,
                "INVALID_NODE_BOUNDS",
                "滚动区域过小或不在屏幕内",
            )
        val swipeCommand = "input" + (displayId?.let { " -d $it" } ?: "") +
            " swipe ${gesture.start.x} ${gesture.start.y} " +
            "${gesture.end.x} ${gesture.end.y} 300"
        var attempts = 0
        var commandOutcome = ShellActionOutcomePolicy.Outcome.SUCCEEDED
        var probe = ScrollMotionProbe(null, anchors = 0, movingAnchors = 0)
        while (true) {
            attempts++
            val result = runSuText(swipeCommand, timeoutSeconds = 8)
            if (result.output == "ROOT_REQUIRED" && result.exitCode == -1) return rootRequired()
            commandOutcome = ShellActionOutcomePolicy.classify(result.exitCode)
            if (commandOutcome == ShellActionOutcomePolicy.Outcome.FAILED) {
                return scrollErrorJson(
                    tool,
                    direction,
                    "COMMAND_FAILED",
                    result.output.ifBlank { "exit=${result.exitCode}" },
                )
            }
            if (commandOutcome == ShellActionOutcomePolicy.Outcome.SUCCEEDED) {
                Thread.sleep(SCROLL_SETTLE_MS)
            }
            probe = probeRootScrollMotion(
                beforeNodes,
                dumpUiNodes(maxNodes, displayId),
                bounds,
                direction,
            )
            val movedNow = ScrollEvidenceContract.classify(
                direction = direction,
                delta = probe.delta,
                movementSource = probe.delta?.let { ScrollMovementSource.ANCHOR_MOTION },
                atBoundary = false,
            ) == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
            // 锚点全在、一个都没动 → 这次手势大概率没生效（多半打在了页面切换动画上），
            // 补发一次不会重复滚动；一个锚点都没配上也可能是页面换了，那时不补发。
            if (!RootScrollResendPolicy.shouldResend(movedNow, probe.anchors, probe.movingAnchors, attempts)) {
                break
            }
        }
        val inferredDelta = probe.delta
        // 锚点确实动了、但幅度彼此不一致（典型：列表滚动同时伴着头部署开/收起）。
        // 这时只敢确认「内容变了」：再盲目补一次滚动就是双倍位移，方向未确认则要求观察一次。
        val partialMotion = probe.anchors >= 2 &&
            probe.movingAnchors >= 2 &&
            probe.movingAnchors * 2 >= probe.anchors
        val evidence = ScrollEvidenceContract.classify(
            direction = direction,
            delta = inferredDelta,
            movementSource = inferredDelta?.let { ScrollMovementSource.ANCHOR_MOTION },
            atBoundary = false,
            partialAnchorMotion = partialMotion,
        )
        val directionConfirmed = evidence != ScrollEvidence.PARTIAL_ANCHOR_MOTION
        val moved = evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION ||
            evidence == ScrollEvidence.PARTIAL_ANCHOR_MOTION
        val json = JSONObject()
            .put("ok", moved)
            .put("tool", tool)
            .put("display_id", displayId ?: JSONObject.NULL)
            .put("direction", direction.name.lowercase())
            .put("moved", moved)
            .put("at_boundary", JSONObject.NULL)
            .put("executor", "root")
            .put("method", "INPUT_SWIPE")
            .put("verified_by", if (moved) "ui_node_motion" else "none")
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
        if (!directionConfirmed) {
            json.put("verified_by", "anchor_motion_partial")
                .put("direction_confirmed", false)
                .put(
                    "note",
                    "内容确实移位了（${probe.movingAnchors}/${probe.anchors} 个锚点变化），但幅度不一致（例如头部此时正在展开/收起），" +
                        "方向未确认；需要确认方向时再 observe_screen 一次，不要直接重滚。",
                )
        }
        // 补发过几次、比对到几个锚点：这两项是「为什么没确认」的唯一证据。
        if (attempts > 1) json.put("swipes", attempts)
        if (!moved) {
            json.put(
                "verification",
                JSONObject()
                    .put("anchors", probe.anchors)
                    .put("moving_anchors", probe.movingAnchors),
            )
        }
        inferredDelta?.let { delta ->
            if (direction.axis == ScrollAxis.VERTICAL) {
                json.put("delta_y", delta)
            } else {
                json.put("delta_x", delta)
            }
        }
        if (targetIndex != null) json.put("target_index", targetIndex)
        if (!moved) {
            if (commandOutcome == ShellActionOutcomePolicy.Outcome.TIMED_OUT) {
                json
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put(
                        "message",
                        "Root 滚动命令超时，动作可能已经执行但位移无法确认；请先重新观察",
                    )
            } else if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
                json
                    .put("code", "DIRECTION_MISMATCH")
                    .put("message", "界面向请求方向的反方向移动")
            } else {
                json
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put(
                        "message",
                        "滚动手势已发出，但无法确认方向或位移；请先重新观察，禁止直接重试",
                    )
            }
        }
        return json.toString()
    }

    private fun resolveUiAutomatorNode(
        observation: ElementObservation,
        index: Int,
    ): ResolvedUiAutomatorNode? {
        if (observation.source != ElementSource.UIAUTOMATOR) return null
        val original = observation.nodes.firstOrNull { node -> node.index == index } ?: return null
        val currentNodes = dumpUiNodes(observation.maxNodes, null)
        if (uiTreeSignature(currentNodes) != observation.treeSignature) return null
        val current = currentNodes.firstOrNull { node -> node.index == index } ?: return null
        val matched = current.takeIf { candidate ->
            candidate.packageName == original.packageName &&
                candidate.className == original.className &&
                candidate.viewId == original.viewId &&
                candidate.text == original.text &&
                candidate.desc == original.desc &&
                candidate.bounds == original.bounds &&
                candidate.clickable == original.clickable &&
                candidate.longClickable == original.longClickable &&
                candidate.scrollable == original.scrollable &&
                candidate.editable == original.editable &&
                candidate.password == original.password &&
                candidate.enabled == original.enabled
        }
        return matched?.let { node -> ResolvedUiAutomatorNode(node, currentNodes) }
    }

    /**
     * Root 滚动只能用滚动前后都唯一存在的稳定节点证明方向；任意树变化不足以证明滚动。
     * 屏幕内容向上移动代表滚动位置向下，因此最后要反转节点位移符号。
     */
    /** 一次「坐标滑动后」的位移证据：换算出的位移、配上的锚点数、真正动了的锚点数。 */
    private data class ScrollMotionProbe(
        val delta: Int?,
        val anchors: Int,
        val movingAnchors: Int,
    )

    private fun probeRootScrollMotion(
        beforeNodes: List<UiNode>,
        afterNodes: List<UiNode>,
        region: Rect,
        direction: ScrollDirection,
    ): ScrollMotionProbe {
        val deltas = scrollAnchorDeltas(beforeNodes, afterNodes, region, direction)
        if (deltas.isEmpty()) return ScrollMotionProbe(delta = null, anchors = 0, movingAnchors = 0)
        return ScrollMotionProbe(
            delta = RootScrollMotionContract.inferScrollDelta(deltas),
            anchors = deltas.size,
            movingAnchors = deltas.count { delta -> kotlin.math.abs(delta) >= 2 },
        )
    }

    private fun scrollAnchorDeltas(
        beforeNodes: List<UiNode>,
        afterNodes: List<UiNode>,
        region: Rect,
        direction: ScrollDirection,
    ): List<Int> {
        if (beforeNodes.isEmpty() || afterNodes.isEmpty()) return emptyList()

        fun UiNode.motionKey(): String? {
            if (viewId.isBlank() && text.isBlank() && desc.isBlank()) return null
            return "$packageName|$className|$viewId|$text|$desc"
        }

        fun uniqueNodes(nodes: List<UiNode>): Map<String, UiNode> = nodes
            .asSequence()
            .filter { node -> Rect.intersects(node.bounds, region) }
            .mapNotNull { node -> node.motionKey()?.let { key -> key to node } }
            .groupBy({ pair -> pair.first }, { pair -> pair.second })
            .mapNotNull { (key, matches) ->
                matches.singleOrNull()?.let { node -> key to node }
            }
            .toMap()

        val before = uniqueNodes(beforeNodes)
        val after = uniqueNodes(afterNodes)
        val contentDeltas = before.mapNotNull { (key, oldNode) ->
            val newNode = after[key] ?: return@mapNotNull null
            val oldAxis = if (direction.axis == ScrollAxis.VERTICAL) oldNode.centerY else oldNode.centerX
            val newAxis = if (direction.axis == ScrollAxis.VERTICAL) newNode.centerY else newNode.centerX
            newAxis - oldAxis
        }
        return contentDeltas
    }

    private fun uiTreeSignature(nodes: List<UiNode>, region: Rect? = null): String =
        nodes.asSequence()
            .filter { node -> region == null || Rect.intersects(node.bounds, region) }
            .joinToString(separator = "\u001f") { node ->
            "${node.packageName}|${node.className}|${node.viewId}|${node.text}|" +
                "${node.desc}|${node.bounds.toShortString()}"
        }

    private fun inputCommand(command: String, tool: String, displayId: Int? = null): String {
        if (!rootAvailable()) return rootRequired()
        val scoped = if (displayId == null) {
            command
        } else {
            "input -d $displayId " + command.removePrefix("input ")
        }
        // 虚拟屏上点击/长按可能命中输入框：动作期间切到无 UI 输入法，
        // 系统就不会把键盘弹到主屏（打游戏时尤其致命）。
        // 两个会话可能同时操作：用全局锁串行化 IME 切换，避免恢复错乱。
        val guardIme = displayId != null && (tool == "tap" || tool == "long_press")
        var guardError: String? = null
        val result: ShellTextResult? = synchronized(IME_SWITCH_LOCK) {
            if (guardIme) guardError = ensureVirtualIme(displayId!!)
            if (guardError != null) null else runSuText(scoped, timeoutSeconds = 8)
        }
        guardError?.let { return it }
        val actionResult = result ?: return errorJson("IME_GUARD_UNAVAILABLE", "虚拟屏输入法保护未完成，动作未执行")
        if (actionResult.output == "ROOT_REQUIRED" && actionResult.exitCode == -1) return rootRequired()
        return when (ShellActionOutcomePolicy.classify(actionResult.exitCode)) {
            ShellActionOutcomePolicy.Outcome.SUCCEEDED -> {
                waitForUiSettle(tool)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .toString()
            }
            ShellActionOutcomePolicy.Outcome.TIMED_OUT -> errorJson(
                "ACTION_OUTCOME_UNKNOWN",
                "Root 动作命令超时，动作可能已经执行；请先重新观察，避免重复操作",
            )
            ShellActionOutcomePolicy.Outcome.FAILED ->
                errorJson("COMMAND_FAILED", actionResult.output.ifBlank { "exit=${actionResult.exitCode}" })
        }
    }

    /** 主屏 IME 正在显示时等待它收起；超时返回 false，调用方必须放弃这次虚拟屏点击。 */
    private fun waitForMainImeIdle(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VirtualDisplayImePolicy.MAIN_INPUT_WAIT_MS
        while (true) {
            val dump = runSuText("dumpsys input_method", 5).output
            if (!VirtualDisplayImePolicy.mainImeVisible(dump)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(VirtualDisplayImePolicy.MAIN_INPUT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** 首次进入虚拟屏输入时建立静默 IME 租约；后续点击复用，不再反复切换。 */
    private fun ensureVirtualIme(displayId: Int): String? {
        silentImeOriginal?.let {
            silentImeDisplayId = displayId
            return null
        }
        if (!waitForMainImeIdle()) return mainInputActiveError()
        val packageName = packageNameProvider()
        if (packageName.isBlank()) return errorJson(
            "IME_GUARD_UNAVAILABLE",
            "无法确定 Eta 包名，未执行虚拟屏点击（避免输入法落到主屏）",
        )
        val original = runSuText("settings get secure default_input_method", 5).output.trim()
        if (original.isEmpty()) return errorJson(
            "IME_GUARD_UNAVAILABLE",
            "无法读取当前输入法，未执行虚拟屏点击",
        )
        val silent = resolveSilentImeId(packageName)
        if (!switchToSilentIme(silent, original)) return errorJson(
            "IME_GUARD_UNAVAILABLE",
            "无 UI 输入法不可用（$silent），未执行虚拟屏点击（避免键盘弹到主屏）",
        )
        rememberImeForCrashRecovery(original)
        silentImeOriginal = original
        silentImeDisplayId = displayId
        silentImeStartedAtElapsed = SystemClock.elapsedRealtime()
        val token = ++silentImeLeaseToken
        imeLeaseExecutor.schedule({ checkSilentImeLease(token) }, 500, TimeUnit.MILLISECONDS)
        // 独立兜底：即使周期检查那条链断掉（异常、线程池异常），到点也必须归还输入法。
        imeLeaseExecutor.schedule(
            {
                runCatching {
                    synchronized(IME_SWITCH_LOCK) { releaseSilentImeLocked(token) }
                }
            },
            MAX_SILENT_IME_LEASE_MS + 5_000,
            TimeUnit.MILLISECONDS,
        )
        return null
    }

    /**
     * 静默 IME 在系统里的**规范 ID**。
     *
     * 不能直接拼 `"$packageName/全类名"`：Android 会把「类名以包名开头」的组件规范化成
     * `包名/.类名` 的短格式存进 IMMS，用全路径去 `ime enable` 会直接报
     * `Unknown input method ... cannot be enabled`。
     *
     * - 正式包 `io.github.mangi.eta` + `io.github.mangi.eta.agent.ime.EtaSilentImeService`
     *   → 规范 ID 是 `io.github.mangi.eta/.agent.ime.EtaSilentImeService`
     * - canary 包名带 `.canary`，类名不以包名开头 → 保留全路径
     *
     * 这条差异正是「canary 测试全绿、正式包虚拟屏点击全被拒」的根因：
     * 同一个常量拼接在两种包名下只有一种是对的。优先用 `ime list -a -s` 里系统给出的
     * 原文，拿不到再按上面的规则推断。
     */
    private fun resolveSilentImeId(packageName: String): String {
        val className = "io.github.mangi.eta.agent.ime.EtaSilentImeService"
        val listed = runSuText("ime list -a -s", 5).output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$packageName/") && it.endsWith(className) }
        if (!listed.isNullOrBlank()) return listed
        return canonicalImeId(packageName, className)
    }

    private fun switchToSilentIme(silent: String, original: String): Boolean {
        runSuText("ime enable $silent", 5)
        runSuText("ime set $silent", 5)
        if (runSuText("settings get secure default_input_method", 5).output.trim() == silent) return true
        // ime set 刚返回时设置可能还没刷新，给一次 100ms 的复核机会。
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (runSuText("settings get secure default_input_method", 5).output.trim() == silent) return true
        restoreIme(original)
        return false
    }

    /**
     * 静默 IME 只保留到虚拟屏输入会话结束：
     * - 仍聚焦在虚拟屏且存在输入会话 → 继续静默（主屏不会弹键盘）；
     * - 焦点回到主屏 / 虚拟输入框失焦 / 超时 → 恢复原输入法。
     */
    private fun checkSilentImeLease(token: Long) {
        try {
            synchronized(IME_SWITCH_LOCK) {
                if (token != silentImeLeaseToken || silentImeOriginal == null) return@synchronized
                val displayId = silentImeDisplayId
                val now = SystemClock.elapsedRealtime()
                if (displayId == null || now - silentImeStartedAtElapsed > MAX_SILENT_IME_LEASE_MS) {
                    releaseSilentImeLocked(token)
                    return@synchronized
                }
                val focused = VirtualDisplayImePolicy.focusedDisplayId(
                    runSuText("dumpsys input", 5).output,
                )
                val hasSession = VirtualDisplayImePolicy.virtualDisplayHasInputSession(
                    runSuText("dumpsys input_method", 5).output,
                    displayId,
                )
                if (focused == displayId && hasSession) {
                    imeLeaseExecutor.schedule({ checkSilentImeLease(token) }, 600, TimeUnit.MILLISECONDS)
                } else {
                    releaseSilentImeLocked(token)
                }
            }
        } catch (error: Throwable) {
            // 这条链一旦因异常中断，就再没人把原输入法还回去 —— 主屏从此没有键盘。
            // 真机踩过：dumpsys 取不到 / 解析异常后默认 IME 一直停在静默服务上。
            logger.warn("Silent IME lease check failed: ${error.javaClass.simpleName}; releasing")
            runCatching {
                synchronized(IME_SWITCH_LOCK) { releaseSilentImeLocked(token) }
            }.onFailure { restoreFailure ->
                logger.warn("Silent IME release failed: ${restoreFailure.javaClass.simpleName}")
            }
        }
    }

    private fun releaseSilentImeLocked(token: Long) {
        if (token != silentImeLeaseToken) return
        val original = silentImeOriginal ?: return
        silentImeLeaseToken++
        silentImeOriginal = null
        silentImeDisplayId = null
        silentImeStartedAtElapsed = 0L
        restoreIme(original)
    }

    /** 会话/控制器关闭时立即归还输入法，不把静默 IME 留给主屏。 */
    fun closeVirtualImeGuard() {
        synchronized(IME_SWITCH_LOCK) {
            releaseSilentImeLocked(silentImeLeaseToken)
        }
        imeLeaseExecutor.shutdownNow()
    }

    private val MAX_SILENT_IME_LEASE_MS = 120_000L

    /** 恢复失败时重试一次并留下日志；不能让静默输入法永久留在系统里。 */
    private fun restoreIme(original: String) {
        repeat(2) {
            runSuText("ime set $original", 5)
            if (runSuText("settings get secure default_input_method", 5).output.trim() == original) {
                runSuText("rm -f ${VirtualDisplayImeRestore.PATH}", 5)
                return
            }
        }
        logger.warn("Virtual display IME restore failed; default input method may remain silent")
    }

    /** 切换前落盘原 IME，进程在租约期间被杀时 App 下次启动可以恢复。 */
    private fun rememberImeForCrashRecovery(original: String) {
        if (!original.matches(Regex("[A-Za-z0-9_.$/:-]+"))) return
        val quoted = original.replace("'", "'\\''")
        runSuText("printf '%s' '$quoted' > ${VirtualDisplayImeRestore.PATH}", 5)
    }

    private fun mainInputActiveError(): String = JSONObject()
        .put("ok", false)
        .put("code", "MAIN_INPUT_ACTIVE")
        .put("message", "主屏正在使用输入法，已暂缓虚拟屏点击；请等输入结束后重试")
        .put("retry_after_ms", VirtualDisplayImePolicy.MAIN_INPUT_WAIT_MS)
        .toString()

    private fun waitForUiSettle(tool: String) {
        val delayMs = when (tool) {
            "tap", "long_press", "press_key" -> 350L
            "swipe" -> 650L
            "input_text" -> 500L
            else -> 250L
        }
        Thread.sleep(delayMs)
    }

    /**
     * 等到这屏的 UI 树连续两次一致（最多 [UI_STABLE_BUDGET_MS]）。
     *
     * 为什么需要：紧跟一次点击之后，**页面切换动画期间发出的手势常常打在过渡上** ——
     * 既不滚动也不报错，表现为「发了但没动」。虚拟屏上实测就是这样稳定失败的：
     * 同一探针 3/3 次得到 `ACTION_OUTCOME_UNKNOWN`，而 `verification` 显示 10 个锚点
     * 里 9 个位置变了（那是页面切换造成的整体位移，不是滚动）。
     * 等树稳定再发手势只多花几百毫秒，而且只在「节点动作被拒、需要坐标兜底」这条
     * 罕见路径上付。
     */
    private fun awaitStableTree(displayId: Int?, maxNodes: Int = 60) {
        val deadline = SystemClock.elapsedRealtime() + UI_STABLE_BUDGET_MS
        var previous = dumpUiNodes(maxNodes, displayId)
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(UI_STABLE_POLL_MS)
            val current = dumpUiNodes(maxNodes, displayId)
            if (uiTreeSignature(current) == uiTreeSignature(previous)) return
            previous = current
        }
    }

    private fun validatePoint(x: Int, y: Int, displayId: Int? = null) {
        val virtual = displayId?.let { resolveVirtualSession(it) }
        val (width, height) = if (virtual != null) virtual.width to virtual.height else screenSize()
        require(x in 0 until width && y in 0 until height) {
            "坐标超出屏幕范围：($x,$y) not in ${width}x$height"
        }
    }

    private fun XmlPullParser.attr(name: String): String =
        getAttributeValue(null, name).orEmpty()

    private fun String.toRectOrNull(): Rect? {
        val match = Regex("""\[(\-?\d+),(\-?\d+)]\[(\-?\d+),(\-?\d+)]""").find(this) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        if (!rootAvailable()) return ShellTextResult(-1, "ROOT_REQUIRED")
        val result = runRootProcess(command, timeoutSeconds)
        val output = listOf(result.output.decodeToString(), result.stderr.decodeToString())
            .filter(String::isNotBlank).joinToString("\n").trim()
        return ShellTextResult(result.exitCode, output)
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        if (!rootAvailable()) return ShellBytesResult(-1, ByteArray(0), "ROOT_REQUIRED")
        val result = runRootProcess(command, timeoutSeconds)
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString())
    }

    private fun runRootProcess(command: String, timeoutSeconds: Long): ProcessBytesResult {
        val envelope = RootCommandEnvelope(command)
        val result = runProcess(timeoutSeconds, "su", "-c", envelope.script)
        val rootOutput = envelope.inspect(result.stderr.decodeToString())
        val completed = result.exitCode >= 0 && result.stderrComplete
        if (rootOutput.denied(completed)) {
            RootAccess.markDenied()
            logger.warn("Agent root access outcome=denied code=ROOT_REQUIRED")
            return ProcessBytesResult(-1, ByteArray(0), "ROOT_REQUIRED".toByteArray())
        }
        return result.copy(stderr = rootOutput.stderr.toByteArray())
    }

    private fun runProcess(
        timeoutSeconds: Long,
        vararg command: String
    ): ProcessBytesResult {
        val process = runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            return ProcessBytesResult(-1, ByteArray(0), it.message.orEmpty().toByteArray())
        }

        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-root-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = thread(name = "agent-root-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outputThread.join(500)
            stderrThread.join(500)
            return ProcessBytesResult(
                ShellActionOutcomePolicy.PROCESS_TIMEOUT_EXIT_CODE,
                output.bytes(),
                "命令执行超时".toByteArray(),
            )
        }

        outputThread.join(500)
        stderrThread.join(500)
        return ProcessBytesResult(process.exitValue(), output.bytes(), stderr.bytes(), stderr.completed)
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(240))
            .toString()

    private fun accessibilityUnavailable(): String = errorJson(
        "ACCESSIBILITY_UNAVAILABLE",
        "Eta 无障碍服务未连接；请开启服务后重新观察屏幕",
    )

    private fun rootRequired(): String = errorJson("ROOT_REQUIRED", "此操作需要设备 Root 权限")

    private fun scrollErrorJson(
        tool: String,
        direction: ScrollDirection?,
        code: String,
        message: String,
    ): String = JSONObject()
        .put("ok", false)
        .put("tool", tool)
        .put("direction", direction?.name?.lowercase() ?: JSONObject.NULL)
        .put("moved", false)
        .put("at_boundary", JSONObject.NULL)
        .put("code", code)
        .put("message", message.take(240))
        .toString()

    private fun nodeActionJson(
        tool: String,
        result: AgentAccessibilityService.NodeActionResult,
    ): String {
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", tool)
            .put("executor", "accessibility")
        if (result.method.isNotBlank()) json.put("method", result.method)
        if (result.clipboardWritten) json.put("clipboard_written", true)
        result.verified?.let { verified -> json.put("verified", verified) }
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", result.message.take(240))
        }
        return json.toString()
    }

    private fun scrollActionJson(
        tool: String,
        result: AgentAccessibilityService.ScrollActionResult,
        displayId: Int? = null,
    ): String {
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", tool)
            .put("display_id", displayId ?: JSONObject.NULL)
            .put("direction", result.direction.name.lowercase())
            .put("moved", result.moved)
            .put("at_boundary", result.atBoundary ?: JSONObject.NULL)
            .put("executor", "accessibility")
            .put("method", result.method)
            .put("verified_by", result.verifiedBy)
            .put("elapsed_ms", result.elapsedMs)
        result.deltaX?.let { json.put("delta_x", it) }
        result.deltaY?.let { json.put("delta_y", it) }
        result.targetIndex?.let { json.put("target_index", it) }
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", result.message.take(240))
        }
        return json.toString()
    }

    private fun String.rewriteTool(tool: String): String =
        runCatching { JSONObject(this).put("tool", tool).toString() }.getOrDefault(this)

    private fun okJson(tool: String, executor: String): String =
        JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("executor", executor)
            .toString()

    private fun matches(value: String, needle: String, matchMode: String): Boolean =
        when (matchMode.lowercase()) {
            "exact" -> value == needle
            "prefix" -> value.startsWith(needle)
            "regex" -> runCatching { Regex(needle).containsMatchIn(value) }.getOrDefault(false)
            else -> value.contains(needle, ignoreCase = true)
        }

    private fun AgentAccessibilityService.UiNode.toUiNode(): UiNode =
        UiNode(
            index = index,
            text = text,
            desc = desc,
            className = className,
            packageName = packageName,
            viewId = viewId,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            focused = focused,
            editable = editable,
            password = password,
            enabled = enabled,
            checked = checked,
            selected = selected,
            hint = hint,
            nodeId = nodeId,
        )

    private data class ShellTextResult(val exitCode: Int, val output: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class ProcessBytesResult(
        val exitCode: Int,
        val output: ByteArray,
        val stderr: ByteArray,
        val stderrComplete: Boolean = false,
    )
    private data class ResolvedUiAutomatorNode(
        val node: UiNode,
        val currentNodes: List<UiNode>,
    )

    private data class ScreenCapture(
        val image: AgentModelClient.ModelImage?,
        val source: String,
        val complete: Boolean,
        val partial: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int> = emptyList(),
        val failureCodes: Map<Int, Int> = emptyMap(),
        val timedOut: Boolean = false,
        val criticalWindowMissing: Boolean = false,
        val requested: Boolean = true,
        val code: String? = null,
        /** 虚拟屏没有新帧、返回的是上一帧时为 true。 */
        val stale: Boolean = false,
    ) {
        fun toJson(): JSONObject {
            val failures = JSONArray()
            failureCodes.forEach { (windowId, errorCode) ->
                failures.put(
                    JSONObject()
                        .put("window_id", windowId)
                        .put("error_code", errorCode),
                )
            }
            return JSONObject()
                .put("requested", requested)
                .put("source", source)
                .put(
                    "quality",
                    ScreenshotOutcomePolicy.classify(
                        requested = requested,
                        hasImage = image != null,
                        complete = complete,
                    ).wireName,
                )
                .put("complete", complete)
                .put("partial", partial)
                .put("expected_windows", expectedWindows)
                .put("captured_windows", capturedWindows)
                .put("missing_window_ids", JSONArray(missingWindowIds))
                .put("failures", failures)
                .put("timed_out", timedOut)
                .put("critical_window_missing", criticalWindowMissing)
                .put("stale", stale)
                .also { result -> code?.let { result.put("code", it) } }
                .also { result ->
                    if (stale) {
                        result.put(
                            "note",
                            "虚拟屏没有新帧（屏幕静止），这是上一帧截图；" +
                                "需要最新画面时先触发一次界面变化（点击/滑动）再截图。",
                        )
                    }
                }
        }

        companion object {
            fun notRequested(): ScreenCapture = ScreenCapture(
                image = null,
                source = "none",
                complete = false,
                partial = false,
                expectedWindows = 0,
                capturedWindows = 0,
                requested = false,
            )

            fun failed(source: String, code: String? = null): ScreenCapture = ScreenCapture(
                image = null,
                source = source,
                complete = false,
                partial = false,
                expectedWindows = 0,
                capturedWindows = 0,
                code = code,
            )
        }
    }

    private class ByteArrayOutputCollector {
        private val output = java.io.ByteArrayOutputStream()
        @Volatile var completed: Boolean = false
            private set

        fun readFrom(input: java.io.InputStream) {
            runCatching {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        completed = true
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }.onFailure { throwable ->
                if (throwable !is IOException) throw throwable
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    companion object {
        private const val TASK_CLEANUP_SETTLE_MS = 2_500L
        private const val TASK_CLEANUP_POLL_MS = 120L

        /** IME 切换是全局状态；多个会话并发操作虚拟屏时串行化。 */
        private val IME_SWITCH_LOCK = Any()
        private const val MAX_INPUT_TEXT_CHARS = 1_000
        private const val MAX_REPLACE_TEXT_CHARS = 4_000
        private const val MAX_CLIPBOARD_TEXT_CHARS = 20_000

        /** 坐标滑动后等界面稳定再比对树；太短会把「还在惯性滚动」当成没动。 */
        private const val SCROLL_SETTLE_MS = 450L

        /** 发手势前等 UI 刷稳：轮询间隔与总预算（预算用尽就用最后一棵树硬着头皮发）。 */
        private const val UI_STABLE_POLL_MS = 150L
        private const val UI_STABLE_BUDGET_MS = 1_200L
        private val ROOT_OBSERVATION_IDS = AtomicLong(0)
    }
}


/**
 * 把「包名 + 全类名」折算成 IMMS 认的 IME ID。
 *
 * 规则来自 Android 的组件名规范化：类名以包名开头时可以省略包名前缀，写成
 * `包名/.类名`；否则必须写全路径。用错格式时 `ime enable` / `ime set` 会失败，
 * 而错误信息只是 `Unknown input method`，很难联想到是格式问题。
 *
 * 为什么单独抽出来：这是纯字符串规则，和 root 命令无关，必须能单测 ——
 * 同一个常量拼接在正式包（类名带包名前缀）与 canary 包（包名多一段）下结果不同，
 * 正是「canary 通过、正式包失败」的根源。
 */
internal fun canonicalImeId(packageName: String, className: String): String =
    if (className.startsWith("$packageName.")) {
        "$packageName/${className.removePrefix(packageName)}"
    } else {
        "$packageName/$className"
    }

/** 把 `Rect` 转成盲点安全判据用的纯数据盒子（见 [BlindTapSafetyPolicy]）。 */
private fun Rect.toBlindTapBox(): BlindTapSafetyPolicy.Box =
    BlindTapSafetyPolicy.Box(left = left, top = top, right = right, bottom = bottom)
