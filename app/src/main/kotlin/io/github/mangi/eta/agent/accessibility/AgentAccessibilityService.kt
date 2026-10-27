package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.device.AgentAccessibilityTimeouts
import io.github.mangi.eta.agent.device.ReadbackWindow
import io.github.mangi.eta.agent.device.ScrollAxis
import io.github.mangi.eta.agent.device.ScrollAxisContract
import io.github.mangi.eta.agent.device.ScrollAmount
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.device.ScrollEvidence
import io.github.mangi.eta.agent.device.ScrollEvidenceContract
import io.github.mangi.eta.agent.device.ScrollMovementSource
import io.github.mangi.eta.agent.device.TextInputVerification
import io.github.mangi.eta.agent.device.TextWriteFailures
import io.github.mangi.eta.agent.device.UiNodeWire
import io.github.mangi.eta.agent.device.RootScrollMotionContract
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private data class ScreenshotWindow(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val active: Boolean,
        val focused: Boolean,
    )

    private data class NodeTraversalState(
        val maxVisitedNodes: Int,
        val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
        var visitedNodes: Int = 0,
        var truncated: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = SCREENSHOT_EXECUTOR
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private val scrollEventLock = ReentrantLock()
    private val scrollEventArrived = scrollEventLock.newCondition()
    private val scrollActionLock = ReentrantLock()
    private var scrollEventSequence = 0L
    private val recentScrollSignals = ArrayDeque<ScrollSignal>()
    private val scrollEventObservationGate = ScrollEventObservationGate(
        uptimeMillis = SystemClock::uptimeMillis,
    )
    // 远端节点查询可能占住线程数秒；只保留一个最新候选，禁止滚动事件形成积压。
    private val scrollEventExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SCROLL_EVENT_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "agent-scroll-event").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val windowContentGenerations = mutableMapOf<Int, Long>()
    private val serviceToken = SERVICE_TOKENS.incrementAndGet()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCurrentInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCurrentInstance()
        scrollEventExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun clearCurrentInstance() {
        if (instance === this) instance = null
        scrollEventObservationGate.clear()
        signalWindowChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 虚拟屏拿不到节点，但事件会到达 —— 拿它当「这个屏刚变过」的轻量信号：
        // 比截图 diff 便宜，能告诉模型当前截图是否可能已过期。
        event?.let { e ->
            if (e.displayId != Display.DEFAULT_DISPLAY) {
                lastDisplayEventAt[e.displayId] = SystemClock.elapsedRealtime()
                AndroidAgentLogger.debug {
                    "a11y-event: display=${e.displayId} type=${e.eventType} pkg=${e.packageName}"
                }
            }
        }
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pruneWindowContentGenerations()
                signalWindowChanged()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                bumpWindowContentGeneration(event.windowId)
                observeScrollEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                bumpWindowContentGeneration(event.windowId)
        }
    }

    /** 各 display 最后一次无障碍事件的时间（elapsedRealtime）；只记非默认屏。 */
    private val lastDisplayEventAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 指定 display 距上次事件多久（毫秒）；从未收到返回 null。 */
    fun displayEventAgeMs(displayId: Int): Long? {
        val at = lastDisplayEventAt[displayId] ?: return null
        return (SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
    }

    override fun onInterrupt() = Unit

    /**
     * 一次观察与其节点句柄组成不可变快照。调用方必须把同一实例传回节点动作，
     * 避免其他运行或 wait_for_text 的临时观察改写 index 含义。
     */
    fun captureNodeSnapshot(maxNodes: Int, displayId: Int? = null): NodeSnapshot? = runOnMainSync {
        val startedAt = SystemClock.elapsedRealtime()
        val root = if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
            virtualDisplayRoot(displayId)
        } else {
            // 主屏不能用 rootInActiveWindow：它是跨屏的活跃窗口（fix165）。
            mainDisplayRoot()
        } ?: return@runOnMainSync null
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val indexedNodes = mutableListOf<IndexedNode>()
        val traversal = NodeTraversalState(
            maxVisitedNodes = (nodeLimit * UI_TREE_VISIT_MULTIPLIER)
                .coerceIn(MIN_UI_TREE_VISITED_NODES, MAX_UI_TREE_VISITED_NODES),
        )
        collectNodes(
            node = root,
            out = indexedNodes,
            maxNodes = nodeLimit,
            depth = 0,
            traversal = traversal,
            path = "0",
        )
        NodeSnapshot(
            id = "o${SNAPSHOT_IDS.incrementAndGet()}",
            serviceToken = serviceToken,
            packageName = root.packageName?.toString().orEmpty(),
            windowId = root.windowId,
            displayId = displayId ?: Display.DEFAULT_DISPLAY,
            contentGeneration = windowContentGeneration(root.windowId),
            capturedAtElapsedMs = startedAt,
            truncated = traversal.truncated,
            indexedNodes = indexedNodes.toList(),
        ).also { snapshot ->
            AndroidAgentLogger.debug {
                "Agent accessibility action=observe_tree observation=${snapshot.id} " +
                    "nodes=${snapshot.nodes.size} visited=${traversal.visitedNodes} " +
                    "truncated=${traversal.truncated} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
            }
        }
    }

    /**
     * 主屏窗口根节点。
     *
     * `rootInActiveWindow` 是**跨屏的活跃窗口**：虚拟屏上有应用时（尤其刚在上面点过东西），
     * 它会变成虚拟屏那个窗口 —— 于是「观察主屏」返回的是虚拟屏界面，
     * `after` 反馈也跟着指向另一块屏（fix165 真机取证）。
     * 这里用 `windowsOnAllDisplays` 的主屏分组校正：活跃窗口不在主屏上时，
     * 改取主屏的应用窗口（没有应用窗口就用主屏任意有根节点的窗口）。
     *
     * 选择规则本身在 [AccessibleWindowSelection] 里，纯 JVM 单测覆盖。
     */
    private fun mainDisplayRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        // windowsOnAllDisplays 要 R(30)，而 minSdk 33 —— 这里不需要再判版本。
        val perDisplay = windowsOnAllDisplays ?: return active
        val mainWindows = perDisplay[Display.DEFAULT_DISPLAY].orEmpty().filter { it.root != null }
        if (mainWindows.isEmpty()) return active
        val choice = AccessibleWindowSelection.chooseMain(
            activeWindowId = active?.windowId ?: -1,
            mainDisplayWindowIds = mainWindows.map { it.id },
            mainDisplayApplicationWindowIds = mainWindows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { it.id },
        )
        val chosen = mainWindows.firstOrNull { it.id == choice.windowId }
        if (choice.replacedActive) {
            AndroidAgentLogger.debug {
                "main-display-tree: active=${active?.windowId ?: -1} 不在主屏上，" +
                    "改用 window=${choice.windowId}（${chosen?.root?.packageName}）"
            }
        }
        return chosen?.root ?: active
    }

    /**
     * 指定 display 的窗口根节点。
     *
     * `rootInActiveWindow` 只跟默认屏走 —— 在虚拟屏上操作时它返回的是主屏窗口，
     * 于是调用方此前只能放弃 UI 树、退化成「截图 + 猜坐标」。
     * **`windows`（getWindows）只覆盖默认屏**，按 displayId 过滤永远落空 —— 实测过。
     * `getWindowsOnAllDisplays()`（API 30+）才按 display 分组返回，可从中取出虚拟屏的窗口根节点。
     *
     * 拿不到就返回 null，让调用方回退到截图路径 —— 不假装成功。
     */
    private fun virtualDisplayRoot(displayId: Int): AccessibilityNodeInfo? {
        // minSdk 33 ≥ R(30)，这层 SDK 判断是死的 —— lint 的 ObsoleteSdkInt 就是报它。
        val perDisplay = windowsOnAllDisplays
        if (perDisplay == null) {
            AndroidAgentLogger.warn("virtual-display-tree: windowsOnAllDisplays=null target=$displayId")
            return null
        }
        val keys = buildList { for (i in 0 until perDisplay.size()) add(perDisplay.keyAt(i)) }
        val windows = perDisplay[displayId]
        val candidates = windows.orEmpty().filter { window -> window.root != null }
        // 虚拟屏上可能有输入法/系统窗口：优先应用窗口，其次才退回第一个有根节点的窗口。
        val chosen = candidates.firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: candidates.firstOrNull()
        AndroidAgentLogger.debug {
            "virtual-display-tree: target=$displayId displays=$keys " +
                "windows=${windows?.size ?: 0} roots=${candidates.size} " +
                "chosen=${chosen?.type}/${chosen?.root?.packageName}"
        }
        return chosen?.root
    }

    /** 临时查询不发布任何全局节点状态，适用于 wait_for_text。 */
    fun queryNodes(maxNodes: Int, displayId: Int? = null): List<UiNode> =
        captureNodeSnapshot(maxNodes, displayId)?.nodes.orEmpty()

    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun displaySize(): Pair<Int, Int>? = runCatching {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) point.x to point.y else null
    }.getOrNull()

    internal fun packageWindowVisibility(packageName: String): PackageWindowVisibility =
        runOnMainSync {
            val activeRoot = rootInActiveWindow
            if (activeRoot?.packageName?.toString() == packageName) {
                return@runOnMainSync PackageWindowVisibility.VISIBLE
            }
            var inspectedRoot = activeRoot != null
            var hasUnknownRelevantWindow = false
            for (window in windows.orEmpty()) {
                val root = window.root
                if (root == null) {
                    if (
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        window.isActive ||
                        window.isFocused
                    ) {
                        hasUnknownRelevantWindow = true
                    }
                    continue
                }
                inspectedRoot = true
                if (root.packageName?.toString() == packageName) {
                    return@runOnMainSync PackageWindowVisibility.VISIBLE
                }
            }
            if (inspectedRoot && !hasUnknownRelevantWindow) {
                PackageWindowVisibility.GONE
            } else {
                PackageWindowVisibility.UNKNOWN
            }
        } ?: PackageWindowVisibility.UNKNOWN

    /**
     * BACK 只表示系统接收了退出动作；浮窗通常还会执行退出动画。
     * 等待目标包窗口真正消失并稳定两个采样周期，避免下一步截图抢在 removeView 之前执行。
     */
    fun awaitPackageWindowGone(
        packageName: String,
        timeoutMillis: Long = 1_000L,
        minimumWaitMillis: Long = 160L,
        stableMillis: Long = 80L,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(200L, 2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            when (packageWindowVisibility(packageName)) {
                PackageWindowVisibility.VISIBLE,
                PackageWindowVisibility.UNKNOWN -> absentSince = 0L
                PackageWindowVisibility.GONE -> {
                    if (absentSince == 0L) absentSince = now
                    if (
                        now - startedAt >= minimumWaitMillis &&
                        now - absentSince >= stableMillis
                    ) {
                        return true
                    }
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun signalWindowChanged() {
        windowChangeLock.lock()
        try {
            windowChanged.signalAll()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun awaitWindowChanged(timeoutMillis: Long) {
        windowChangeLock.lock()
        try {
            windowChanged.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun observeScrollEvent(event: AccessibilityEvent) {
        scrollEventObservationGate.withMatchingObservation(
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            eventTimeMillis = event.eventTime,
        ) { observation ->
            // 系统可能在滚动后清掉节点缓存，source 必须复制事件后在后台解析。
            val eventCopy = try {
                AccessibilityEvent(event)
            } catch (error: RuntimeException) {
                AndroidAgentLogger.warnThrottled("scroll_event_copy") {
                    "Agent accessibility action=observe_scroll_event " +
                        "outcome=copy_failed error=${error.javaClass.simpleName}"
                }
                return@withMatchingObservation
            }
            scrollEventExecutor.execute {
                if (!scrollEventObservationGate.isActive(observation)) return@execute
                val signal = resolveScrollSignal(eventCopy) ?: return@execute
                if (scrollEventObservationGate.isActive(observation)) {
                    recordScrollSignal(signal)
                }
            }
        }
    }

    private fun resolveScrollSignal(event: AccessibilityEvent): ScrollSignal? {
        val source = try {
            event.getSource(0)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warnThrottled("scroll_event_source") {
                "Agent accessibility action=resolve_scroll_event " +
                    "outcome=source_failed error=${error.javaClass.simpleName}"
            }
            null
        } ?: return null
        return ScrollSignal(
            sequence = 0L,
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            sourceUniqueId = source.uniqueId.orEmpty(),
            sourceViewId = source.viewIdResourceName.orEmpty(),
            sourceClassName = source.className?.toString().orEmpty(),
            sourceBounds = source.bounds(),
        )
    }

    private fun recordScrollSignal(signal: ScrollSignal) {
        scrollEventLock.lock()
        try {
            scrollEventSequence++
            recentScrollSignals.addLast(signal.copy(sequence = scrollEventSequence))
            while (recentScrollSignals.size > MAX_SCROLL_SIGNALS) {
                recentScrollSignals.removeFirst()
            }
            scrollEventArrived.signalAll()
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun bumpWindowContentGeneration(windowId: Int) {
        if (windowId < 0) return
        windowContentGenerations[windowId] = windowContentGeneration(windowId) + 1L
    }

    private fun windowContentGeneration(windowId: Int): Long =
        windowContentGenerations[windowId] ?: 0L

    private fun pruneWindowContentGenerations() {
        if (windowContentGenerations.isEmpty()) return
        val liveWindowIds = windows.orEmpty().mapTo(hashSetOf()) { window -> window.id }
        windowContentGenerations.keys.retainAll(liveWindowIds)
    }

    private fun currentScrollEventSequence(): Long {
        scrollEventLock.lock()
        return try {
            scrollEventSequence
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun awaitScrollSignal(
        afterSequence: Long,
        packageName: String,
        windowId: Int,
        targetIdentity: ScrollTargetIdentity,
        timeoutMillis: Long,
    ): ScrollSignal? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        scrollEventLock.lock()
        try {
            while (true) {
                val signal = recentScrollSignals.firstOrNull { candidate ->
                    candidate.sequence > afterSequence &&
                        candidate.windowId == windowId &&
                        candidate.packageName == packageName &&
                        candidate.matchesTarget(targetIdentity)
                }
                if (
                    signal != null
                ) {
                    return signal
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    scrollEventArrived.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            scrollEventLock.unlock()
        }
    }

    fun clickNode(snapshot: NodeSnapshot, index: Int): NodeActionResult =
        withValidatedIndexedNode(snapshot, index) { indexed ->
            val node = indexed.node
            val actionable = indexed.clickTarget?.resolveFor(node)
            if (indexed.clickTarget != null && actionable == null) {
                NodeActionResult.failure(
                    "STALE_ACTION_TARGET",
                    "节点的可点击目标已经变化，请重新观察屏幕",
                )
            } else if (actionable != null) {
                when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK)) {
                    ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_CLICK")
                    ActionDispatch.REJECTED ->
                        NodeActionResult.failure("ACTION_FAILED", "目标节点拒绝点击动作")
                    ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
                }
            } else {
                if (snapshot.displayId != Display.DEFAULT_DISPLAY) {
                    // 无障碍手势只作用于默认屏；虚拟屏上兜底会点到用户主屏。
                    NodeActionResult.failure(
                        "GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                        "虚拟屏不支持无障碍手势兜底；请改用 tap_area 坐标动作",
                    )
                } else {
                    val bounds = clippedNodeBounds(node)
                    if (bounds.isEmpty) {
                        NodeActionResult.failure("INVALID_NODE_BOUNDS", "目标节点没有可点击区域")
                    } else gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
            }
        }

    fun longClickNode(
        snapshot: NodeSnapshot,
        index: Int,
        durationMs: Long,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        val node = indexed.node
        val actionable = indexed.longClickTarget?.resolveFor(node)
        if (indexed.longClickTarget != null && actionable == null) {
            NodeActionResult.failure(
                "STALE_ACTION_TARGET",
                "节点的可长按目标已经变化，请重新观察屏幕",
            )
        } else if (actionable != null) {
            when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_LONG_CLICK")
                ActionDispatch.REJECTED ->
                    NodeActionResult.failure("ACTION_FAILED", "目标节点拒绝长按动作")
                ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            }
        } else {
            if (snapshot.displayId != Display.DEFAULT_DISPLAY) {
                // 同 clickNode：手势兜底会落到主屏，虚拟屏只允许节点动作或坐标动作。
                NodeActionResult.failure(
                    "GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                    "虚拟屏不支持无障碍手势兜底；请改用 long_press 坐标动作",
                )
            } else {
                val bounds = clippedNodeBounds(node)
                if (bounds.isEmpty) {
                    NodeActionResult.failure("INVALID_NODE_BOUNDS", "目标节点没有可长按区域")
                } else {
                    gestureTap(
                        bounds.centerX().toFloat(),
                        bounds.centerY().toFloat(),
                        durationMs = durationMs.coerceIn(300L, 3_000L),
                    ).let { result ->
                        if (result.ok) result.copy(method = "GESTURE_LONG_PRESS") else result
                    }
                }
            }
        }
    }

    internal fun scrollNode(
        snapshot: NodeSnapshot,
        index: Int,
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.PAGE,
    ): ScrollActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "SERVICE_TIMEOUT",
                message = "无障碍服务主线程无响应",
                targetIndex = index,
            )
        val validationNode = when (validation) {
            is NodeValidation.Invalid -> return ScrollActionResult.failure(
                direction = direction,
                code = validation.result.code,
                message = validation.result.message,
                targetIndex = index,
            )
            is NodeValidation.Valid -> validation.indexedNode
        }
        val scrollable = validationNode.scrollTarget?.resolveFor(validationNode.node)
        if (validationNode.scrollTarget != null && scrollable == null) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_ACTION_TARGET",
                message = "节点的滚动容器已经变化，请重新观察屏幕",
                targetIndex = index,
            )
        }
        scrollable ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NOT_SCROLLABLE",
            message = "指定节点及其父节点不可滚动",
            targetIndex = index,
        )
        return executeScroll(
            scrollable,
            direction,
            targetIndex = index,
            amount = amount,
            allowGesture = snapshot.displayId == Display.DEFAULT_DISPLAY,
        )
    }

    internal fun scrollCurrent(
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.PAGE,
        displayId: Int? = null,
    ): ScrollActionResult {
        val target = runOnMainSync {
            val root = if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                virtualDisplayRoot(displayId)
            } else {
                rootInActiveWindow
            }
            root?.let { findBestScrollableNode(it, direction) }
        } ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NO_ACTIVE_WINDOW",
            message = "当前活动窗口不可访问",
        )
        return executeScroll(
            target,
            direction,
            targetIndex = null,
            amount = amount,
            allowGesture = displayId == null || displayId == Display.DEFAULT_DISPLAY,
        )
    }

    private fun executeScroll(
        target: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetIndex: Int?,
        amount: ScrollAmount,
        allowGesture: Boolean = true,
    ): ScrollActionResult = scrollActionLock.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val refreshed = runOnMainSync { target.refresh() } == true
        if (!refreshed || !target.isVisibleToUser || !target.isEnabled) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_NODE",
                message = "滚动目标已经失效，请重新观察屏幕",
                targetIndex = targetIndex,
            )
        }
        if (target.exposesOnlyOppositeAxis(direction)) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "AXIS_MISMATCH",
                message = "目标只支持另一滚动轴；为避免误触侧滑，本次未执行任何动作",
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val packageName = target.packageName?.toString().orEmpty()
        val windowId = target.windowId
        val beforeAnchors = scrollContentAnchors(target)
        val targetIdentity = ScrollTargetIdentity.from(target)
        val beforeSequence = currentScrollEventSequence()
        // 节点滚动动作固定翻约一屏；小幅滚动只能用手势控制位移。
        val method = if (amount == ScrollAmount.PAGE) chooseScrollMethod(target, direction) else null
        var methodName = method?.name.orEmpty()
        return withScrollEventObservation(packageName, windowId) {
            val nodeDispatch = method?.let { selected ->
                performNodeAction(target, selected.actionId, selected.args)
            }
            if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_OUTCOME_UNKNOWN",
                    message = "滚动动作可能已提交，但系统未在时限内返回结果；请先重新观察，避免重复滚动",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            var accepted = nodeDispatch == ActionDispatch.ACCEPTED

            if (!accepted && !allowGesture) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                    message = "虚拟屏不支持无障碍手势兜底；本次未发送任何手势，可改用坐标滑动",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            if (!accepted) {
                val boundaryBeforeGesture = runOnMainSync {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundaryBeforeGesture) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                val bounds = clippedNodeBounds(target)
                val gesture = direction.gestureWithin(bounds, amount)
                    ?: return ScrollActionResult.failure(
                        direction = direction,
                        code = "INVALID_NODE_BOUNDS",
                        message = "滚动目标没有足够的可用区域",
                        targetIndex = targetIndex,
                    )
                methodName = "GESTURE_SWIPE"
                val gestureResult = gestureSwipe(
                    gesture.start.x.toFloat(),
                    gesture.start.y.toFloat(),
                    gesture.end.x.toFloat(),
                    gesture.end.y.toFloat(),
                    SCROLL_GESTURE_DURATION_MS,
                )
                if (!gestureResult.ok) {
                    return ScrollActionResult.failure(
                        direction = direction,
                        code = gestureResult.code,
                        message = gestureResult.message,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                accepted = true
            }
            if (!accepted) {
                val boundary = runOnMainSync {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundary) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_FAILED",
                    message = "系统拒绝滚动动作",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }

            val signal = awaitScrollSignal(
                afterSequence = beforeSequence,
                packageName = packageName,
                windowId = windowId,
                targetIdentity = targetIdentity,
                timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
            )
            val afterAnchors = runOnMainSync {
                if (target.refresh()) scrollContentAnchors(target) else emptyList()
            }.orEmpty()
            val eventDelta = signal?.axisDelta(direction.axis)?.takeIf { delta -> delta != 0 }
            val anchorDelta = inferScrollAnchorDelta(beforeAnchors, afterAnchors, direction)
            val delta = eventDelta ?: anchorDelta
            val movementSource = when {
                eventDelta != null -> ScrollMovementSource.EVENT
                anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
                else -> null
            }
            val boundary = runOnMainSync {
                target.refresh() && isAtScrollBoundary(target, direction, signal)
            } == true
            val evidence = ScrollEvidenceContract.classify(
                direction = direction,
                delta = delta,
                movementSource = movementSource,
                atBoundary = boundary,
            )
            if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "DIRECTION_MISMATCH",
                    message = "界面向请求方向的反方向移动",
                    method = methodName,
                    targetIndex = targetIndex,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (
                evidence == ScrollEvidence.MOVED_BY_EVENT ||
                evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
            ) {
                return ScrollActionResult(
                    ok = true,
                    direction = direction,
                    moved = true,
                    atBoundary = boundary,
                    method = methodName,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    verifiedBy = if (evidence == ScrollEvidence.MOVED_BY_EVENT) {
                        "scroll_event"
                    } else {
                        "anchor_motion"
                    },
                    elapsedMs = elapsedMs,
                    targetIndex = targetIndex,
                )
            }
            if (evidence == ScrollEvidence.AT_BOUNDARY) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = elapsedMs,
                )
            }
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "滚动动作已经发出，但无法确认内容位移；请先重新观察，禁止直接重试",
                method = methodName,
                targetIndex = targetIndex,
                deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                elapsedMs = elapsedMs,
            )
        }
    }

    private inline fun <T> withScrollEventObservation(
        packageName: String,
        windowId: Int,
        block: () -> T,
    ): T {
        val observation = scrollEventObservationGate.begin(
            packageName = packageName,
            windowId = windowId,
            validForMillis = SCROLL_EVENT_OBSERVATION_TIMEOUT_MS,
        )
        return try {
            block()
        } finally {
            scrollEventObservationGate.end(observation)
        }
    }

    fun inputTextFocused(text: String): NodeActionResult = writeTextPrepared {
        val node = findFocusedEditableNode()
            ?: return@writeTextPrepared TextWriteTarget.Rejected(
                NodeActionResult.failure(
                    "NO_FOCUSED_EDITABLE",
                    "没有获得输入焦点的可编辑节点",
                ),
            )
        node.incrementalTextValidationError()?.let { error ->
            return@writeTextPrepared TextWriteTarget.Rejected(error)
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.realText(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@writeTextPrepared TextWriteTarget.Rejected(
            NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
            ),
        )
        TextWriteTarget.Ready(node, plan.text, plan.cursor)
    }

    fun setTextNode(
        snapshot: NodeSnapshot?,
        index: Int?,
        text: String,
    ): NodeActionResult {
        if (index != null) {
            val requiredSnapshot = snapshot
                ?: return NodeActionResult.failure("NO_OBSERVATION", "指定 index 需要有效观察快照")
            return withValidatedNode(requiredSnapshot, index) { node ->
                if (!node.isEditable) {
                    NodeActionResult.failure("NOT_EDITABLE", "指定节点不可编辑")
                } else {
                    setNodeText(node, text, text.length)
                }
            }
        }
        return writeTextPrepared {
            val node = findFocusedEditableNode()
                ?: return@writeTextPrepared TextWriteTarget.Rejected(
                    NodeActionResult.failure(
                        "NO_FOCUSED_EDITABLE",
                        "没有获得输入焦点的可编辑节点",
                    ),
                )
            TextWriteTarget.Ready(node, text, text.length)
        }
    }

    /**
     * 在指定 display 的窗口里直接写入文本。
     *
     * 为什么需要单独一条通路：`input text` 只接受 ASCII，虚拟屏上的中文过去只能拒绝；
     * 剪贴板粘贴会覆盖用户内容、还可能把主屏输入法拉起来。
     * `ACTION_SET_TEXT` 直接作用于该屏窗口里的节点：不点击、不聚焦、不唤醒任何输入法。
     *
     * 找不到唯一目标时明确失败，不猜：
     * - `NO_ACTIVE_WINDOW`：该屏没有可读窗口；
     * - `NO_EDITABLE_FIELD`：有窗口但没有可见可编辑节点（需要先点开输入框）；
     * - `AMBIGUOUS_EDITABLE_FIELD`：有多个输入框，无法判断该写哪个。
     */
    fun setTextOnDisplay(
        displayId: Int,
        text: String,
        replace: Boolean,
    ): NodeActionResult = runNodeActionOnMainSync {
        val root = if (displayId != Display.DEFAULT_DISPLAY) {
            virtualDisplayRoot(displayId)
        } else {
            rootInActiveWindow
        } ?: return@runNodeActionOnMainSync NodeActionResult.failure(
            "NO_ACTIVE_WINDOW",
            "display=$displayId 上没有可读窗口，请先启动应用并等待界面出现",
        )
        val focused = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()?.takeIf { node -> node.isUsableInputTarget() }
        val target = focused ?: run {
            val editable = ArrayList<AccessibilityNodeInfo>(2)
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > MAX_UI_TREE_DEPTH || visited >= MAX_SCROLL_SEARCH_NODES) return
                visited++
                if (node.isUsableInputTarget()) {
                    editable += node
                    if (editable.size > 1) return
                }
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            }
            visit(root, 0)
            when {
                editable.isEmpty() -> return@runNodeActionOnMainSync NodeActionResult.failure(
                    "NO_EDITABLE_FIELD",
                    "display=$displayId 的当前界面没有可编辑输入框；请先点开输入框再输入",
                )
                editable.size > 1 -> return@runNodeActionOnMainSync NodeActionResult.failure(
                    "AMBIGUOUS_EDITABLE_FIELD",
                    "display=$displayId 上有多个输入框，无法判断该写哪个；请先点一下目标输入框",
                )
                else -> editable.single()
            }
        }
        if (!replace) {
            val plan = TextEditPlanner.insertAtSelection(
                currentText = target.realText(),
                insertedText = text,
                selectionStart = target.textSelectionStart,
                selectionEnd = target.textSelectionEnd,
            ) ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "当前输入框未提供可靠光标或选区；请改用 replace 模式",
            )
            return@runNodeActionOnMainSync setNodeText(target, plan.text, plan.cursor)
        }
        setNodeText(target, text, text.length)
    }

    /**
     * 优先直接按选区写入，只有目标拒绝 SET_TEXT 时才回退系统粘贴。
     *
     * fix177：和 [writeTextPrepared] 同构 —— 找焦点节点留在主线程（纯读），
     * 写入 / 粘贴 / 读回放到调用线程。旧写法整段在无障碍主线程上：粘贴后立刻
     * `refresh()` 读节点，Compose 还没提交就判 `ACTION_OUTCOME_UNKNOWN`；
     * 而这里恰好是 `type_text` append 超过 200 字（[AgentLocalTools] 的
     * TYPE_TEXT_INCREMENTAL_CHARS）时唯一走得到的通路，属于 fix176 同一类缺陷。
     */
    fun pasteText(text: String): NodeActionResult {
        val prepared = callOnMainSync {
            val node = findFocusedEditableNode()
                ?: return@callOnMainSync TextWriteTarget.Rejected(
                    NodeActionResult.failure(
                        "NO_FOCUSED_EDITABLE",
                        "没有获得输入焦点的可编辑节点",
                    ),
                )
            node.incrementalTextValidationError()?.let { error ->
                return@callOnMainSync TextWriteTarget.Rejected(error)
            }
            val existingText = node.realText()
            val selectionStart = node.textSelectionStart
            val selectionEnd = node.textSelectionEnd
            val plan = TextEditPlanner.insertAtSelection(
                currentText = existingText,
                insertedText = text,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            )
            if (plan == null) {
                logTextSelectionUnavailable(node, existingText.length, selectionStart, selectionEnd)
                return@callOnMainSync TextWriteTarget.Rejected(
                    NodeActionResult.failure(
                        "TEXT_SELECTION_UNAVAILABLE",
                        "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
                    ),
                )
            }
            TextWriteTarget.Ready(node, plan.text, plan.cursor)
        }
        val target = when (prepared) {
            is MainThreadCallResult.Completed -> prepared.value
                ?: return NodeActionResult.failure("ACTION_FAILED", "无障碍动作执行异常")
            MainThreadCallResult.NOT_STARTED -> return NodeActionResult.failure(
                TextWriteFailures.CODE,
                TextWriteFailures.notStartedMessage(),
            )
            MainThreadCallResult.OUTCOME_UNKNOWN -> return NodeActionResult.failure(
                TextWriteFailures.CODE,
                TextWriteFailures.outcomeUnknownMessage(),
            )
        }
        val ready = when (target) {
            is TextWriteTarget.Ready -> target
            is TextWriteTarget.Rejected -> return target.result
        }
        val directResult = setNodeText(ready.node, ready.text, ready.cursor)
        if (directResult.ok) {
            return directResult.copy(method = "ACTION_SET_TEXT_PASTE")
        }
        if (directResult.code != "ACTION_FAILED") return directResult
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        val temporaryLabel = "$CLIP_LABEL:${CLIP_IDS.incrementAndGet()}"
        val temporaryClip = ClipData.newPlainText(temporaryLabel, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val copied = runCatching {
            clipboard.setPrimaryClip(temporaryClip)
        }.isSuccess
        if (!copied) {
            return NodeActionResult.failure(
                "CLIPBOARD_WRITE_FAILED",
                "写入剪贴板失败",
            )
        }
        val pasteResult = try {
            if (ready.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                // fix177：判定只看内容是否真的变成目标文本（再给一段有限读回窗口），
                // 不再把「refresh() 的返回值」当证据。
                if (textReadbackMatches(ready.node, ready.text)) {
                    NodeActionResult.success(method = "ACTION_PASTE", verified = true)
                } else {
                    NodeActionResult.outcomeUnknown()
                }
            } else {
                NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝粘贴动作")
            }
        } catch (_: Throwable) {
            NodeActionResult.outcomeUnknown()
        }
        val restored = restoreClipboardIfStillOwned(
            clipboard = clipboard,
            temporaryLabel = temporaryLabel,
            originalClip = originalClip,
        )
        return if (!restored) {
            NodeActionResult.outcomeUnknown().copy(clipboardWritten = true)
        } else {
            pasteResult.copy(clipboardWritten = true)
        }
    }

    private fun restoreClipboardIfStillOwned(
        clipboard: ClipboardManager,
        temporaryLabel: String,
        originalClip: ClipData?,
    ): Boolean {
        val currentClip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return false
        if (currentClip.description.label?.toString() != temporaryLabel) {
            // 用户或其他应用已经写入新内容，不能用旧快照覆盖它。
            return true
        }
        return runCatching {
            if (originalClip != null) {
                clipboard.setPrimaryClip(originalClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }.isSuccess
    }

    fun imeEnter(): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "没有获得输入焦点的可编辑节点",
            )
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            NodeActionResult.success(method = "ACTION_IME_ENTER")
        } else {
            NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝回车动作")
        }
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            },
            durationMs.coerceIn(1, 3_000),
            successMethod = "GESTURE_TAP",
        )

    fun gestureSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(100, 3_000),
            successMethod = "GESTURE_SWIPE",
        )

    fun globalActionResult(name: String): NodeActionResult {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return NodeActionResult.failure("INVALID_ARGUMENT", "不支持的系统动作")
        }
        return runNodeActionOnMainSync {
            if (performGlobalAction(action)) {
                NodeActionResult.success(method = "GLOBAL_ACTION_${name.uppercase()}")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "系统拒绝全局动作")
            }
        }
    }

    fun globalAction(name: String): Boolean = globalActionResult(name).ok

    fun copyToClipboard(text: String): NodeActionResult =
        runNodeActionOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            NodeActionResult.success(method = "CLIPBOARD_SET")
        }

    fun readClipboard(): ClipboardReadResult = runOnMainSync {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 2026-10-27（fix185）：原来两种情形共用一个 code「CLIPBOARD_UNAVAILABLE_OR_EMPTY」，
        // 用户和模型都分不清「系统拒绝读取」与「剪贴板确实是空的」——
        // 前者可以再试或换个办法，后者没必要重试。
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return@runOnMainSync ClipboardReadResult.failure(code = "CLIPBOARD_READ_FAILED")
        if (clip.itemCount <= 0) {
            return@runOnMainSync ClipboardReadResult.failure(code = "CLIPBOARD_EMPTY")
        }
        ClipboardReadResult(
            ok = true,
            text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty(),
        )
    } ?: ClipboardReadResult.failure(code = "SERVICE_TIMEOUT")

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * 截取当前屏幕，排除 TYPE_ACCESSIBILITY_OVERLAY 浮层（glow/orb/bubble/resultCard/GestureIndicator）。
     * 从 agent-runtime 子线程调用；takeScreenshotOfWindow 内部 post 到主线程，
     * callback 在有界后台线程执行位图复制，latch 只阻塞 agent-runtime 工作线程。
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
        onWindowsSubmitted: (() -> Unit)? = null,
    ): ScreenshotCaptureResult {
        val windowsSubmitted = AtomicBoolean(false)
        fun signalWindowsSubmitted() {
            if (windowsSubmitted.compareAndSet(false, true)) {
                runCatching { onWindowsSubmitted?.invoke() }
            }
        }
        // 旧系统没有逐窗口截图，不能用全屏截图绕过入口包与 Eta 浮层排除合同。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val allWindows = windows
            ?: return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenshotCaptureResult.unavailable().also {
                allWindows.forEach { window -> runCatching { window.recycle() } }
                signalWindowsSubmitted()
            }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y
        if (screenW <= 0 || screenH <= 0) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val screenBounds = Rect(0, 0, screenW, screenH)

        // 只过滤能确认属于 Eta 的无障碍 overlay；第三方 overlay 必须保留，
        // 否则截图与实际接收坐标手势的窗口会不一致。
        val windowPackages = allWindows.associate { window ->
            window.id to window.root?.packageName?.toString()
        }
        val windowDecisions = allWindows.associate { window ->
            window.id to ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay =
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                active = window.isActive,
                focused = window.isFocused,
                resolvedPackage = windowPackages[window.id],
                ownPackage = packageName,
                excludedPackages = excludedPackages,
            )
        }
        val unknownRelevantWindowIds = windowDecisions
            .filterValues { decision ->
                decision == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN
            }
            .keys
            .toList()
        if (unknownRelevantWindowIds.isNotEmpty()) {
            val expectedWindows = allWindows.size
            allWindows.forEach { window -> runCatching { window.recycle() } }
            AndroidAgentLogger.warn(
                "Agent accessibility action=capture_screenshot outcome=failed " +
                    "reason=unknown_relevant_window_during_exclusion " +
                    "windows=${unknownRelevantWindowIds.size}"
            )
            return ScreenshotCaptureResult.blockedByUnknownWindow(
                expectedWindows = expectedWindows,
                windowIds = unknownRelevantWindowIds,
            ).also { signalWindowsSubmitted() }
        }
        val captureWindows = allWindows.mapNotNull { window ->
            if (windowDecisions[window.id] == ScreenshotWindowPolicy.Decision.EXCLUDE) {
                return@mapNotNull null
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            if (
                bounds.width() <= 0 ||
                bounds.height() <= 0 ||
                !Rect.intersects(bounds, screenBounds)
            ) {
                return@mapNotNull null
            }
            ScreenshotWindow(
                id = window.id,
                layer = window.layer,
                type = window.type,
                bounds = bounds,
                active = window.isActive,
                focused = window.isFocused,
            )
        }.distinctBy { window -> window.id }.sortedBy { window -> window.layer }
        if (captureWindows.isEmpty()) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val topApplicationWindowId = captureWindows
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull(ScreenshotWindow::layer)
            ?.id
            ?: captureWindows.maxByOrNull(ScreenshotWindow::layer)?.id
        val criticalWindowIds = captureWindows
            .asSequence()
            .filter { window ->
                window.id == topApplicationWindowId || window.active || window.focused
            }
            .map(ScreenshotWindow::id)
            .toSet()

        val latch = CountDownLatch(captureWindows.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val failures = mutableMapOf<Int, Int>()
        val lock = Any()
        val acceptingResults = AtomicBoolean(true)

        for (window in captureWindows) {
            runCatching {
                takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val sw = convertToSoftwareBitmap(screenshot)
                                ?: throw IllegalStateException("screenshot bitmap unavailable")
                            var retained = false
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    screenshots[window.id] = sw to Rect(window.bounds)
                                    retained = true
                                }
                            }
                            if (!retained && !sw.isRecycled) sw.recycle()
                        } catch (_: Exception) {
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        synchronized(lock) {
                            if (acceptingResults.get()) failures[window.id] = errorCode
                        }
                        latch.countDown()
                    }
                })
            }.onFailure {
                synchronized(lock) {
                    if (acceptingResults.get()) {
                        failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    }
                }
                latch.countDown()
            }
        }
        // 窗口 ID 已固定并全部提交后即可显示 Eta；后续合并和编码不会再把入口浮层拍进去。
        signalWindowsSubmitted()
        val completed = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        acceptingResults.set(false)
        val captured = synchronized(lock) {
            screenshots.toMap().also { screenshots.clear() }
        }
        val capturedIds = captured.keys
        val missingIds = captureWindows.map(ScreenshotWindow::id).filterNot(capturedIds::contains)
        val criticalWindowMissing = missingIds.any(criticalWindowIds::contains)
        val merged = if (captured.isEmpty() || criticalWindowMissing) {
            null
        } else {
            mergeScreenshots(captured, captureWindows, screenW, screenH)
        }
        val failureCodes = synchronized(lock) { failures.toMap() }
        val complete = completed && missingIds.isEmpty() && merged != null
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=merged " +
                "allWindows=${allWindows.size} validWindows=${captureWindows.size} " +
                "excludedPackages=${excludedPackages.size} " +
                "completed=$completed screenshots=${captured.size} " +
                "complete=$complete criticalMissing=$criticalWindowMissing " +
                "screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }
        captured.values.forEach { (bitmap, _) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        allWindows.forEach { window -> runCatching { window.recycle() } }
        return ScreenshotCaptureResult(
            bitmap = merged,
            complete = complete,
            expectedWindows = captureWindows.size,
            capturedWindows = captured.size,
            missingWindowIds = missingIds,
            failureCodes = failureCodes,
            timedOut = !completed,
            criticalWindowMissing = criticalWindowMissing,
        )
    }

    private fun convertToSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? =
        screenshot.hardwareBuffer.use { hardwareBuffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@use null
            try {
                if (wrapped.config == Bitmap.Config.HARDWARE) {
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    wrapped
                }
            } finally {
                if (wrapped.config == Bitmap.Config.HARDWARE && !wrapped.isRecycled) {
                    wrapped.recycle()
                }
            }
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<ScreenshotWindow>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        var merged: Bitmap? = null
        return try {
            val output = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val occlusionPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            for (window in sortedWindows) {
                val pair = screenshots[window.id]
                if (pair == null) {
                    // 上层窗口抓取失败时必须遮住其区域，不能向模型暴露实际已被遮挡的底层界面。
                    canvas.drawRect(RectF(window.bounds), occlusionPaint)
                } else {
                    val (bmp, bounds) = pair
                    if (bmp.isRecycled) continue
                    // 把窗口 bitmap 缩放到其 bounds 尺寸绘制，处理 takeScreenshotOfWindow
                    // 返回尺寸与 bounds 不一致（逻辑像素 vs 物理像素）的情况。
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, RectF(bounds), null)
                }
            }
            output
        } catch (_: Exception) {
            merged?.takeUnless(Bitmap::isRecycled)?.recycle()
            null
        }
    }

    /**
     * 输入框的真实内容。空框显示提示文字时 text 返回的是提示语，若当作已有内容，
     * 追加输入会把提示拼进结果，清空后读回校验也会误报失败。
     */
    private fun AccessibilityNodeInfo.realText(): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    /** 「读不到正文」和「读不到选区」是两种完全不同的失败，日志必须能分开。 */
    private fun logTextContentUnavailable(node: AccessibilityNodeInfo, password: Boolean) {
        android.util.Log.w(
            "EtaTextWrite",
            "正文不可用 class=" + node.className +
                " viewId=" + node.viewIdResourceName +
                " password=" + password +
                " hasText=" + (runCatching { node.text != null }.getOrNull() ?: false) +
                " showingHint=" + node.isShowingHintText +
                " editable=" + node.isEditable,
        )
    }

    /**
     * 只在「读不到可靠选区」时留一行现场（不记文本内容，只记长度）。
     *
     * 为什么需要它（fix181）：夹具的空输入框在 View 层 selection 是 `0,0`，无障碍层却报
     * `TEXT_SELECTION_UNAVAILABLE`。错误码本身分不清三种原因 —— 文本被当成非空、
     * selection 是 `-1`、还是 `isShowingHintText` 没被系统置位（于是 hint 被当成正文）——
     * 少记任何一项都只能靠猜。和 [textReadbackMatches] 的失败现场同一风格：
     * 正常路径零日志，失败时一行。
     */
    private fun logTextSelectionUnavailable(
        node: AccessibilityNodeInfo,
        textLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        android.util.Log.w(
            "EtaTextWrite",
            "选区不可用 class=" + node.className +
                " viewId=" + node.viewIdResourceName +
                " textLen=" + textLength +
                " hintLen=" + (runCatching { node.hintText?.length }.getOrNull() ?: -1) +
                " showingHint=" + node.isShowingHintText +
                " sel=" + selectionStart + "," + selectionEnd,
        )
    }

    /**
     * fix176：写入后确认读回。
     *
     * `performAction(ACTION_SET_TEXT)` 返回 true 只说明动作被受理，不代表文本已经落地：
     * 2026-10-27 真机（PKX110 / Android 16）实测 —— 主屏 Compose 输入框写中文时，动作返回后
     * 立刻读节点仍是旧值，被报成 ACTION_OUTCOME_UNKNOWN；同一份报告里稍后重新观察，文本已经
     * 是「中文种子后缀」。模型拿到 ok=false 会重试，内容就变成两串重复。
     *
     * 为什么不在这里无条件等一会儿再读：调用方本身可能就在无障碍主线程上
     * （`runNodeActionOnMainSync` 的 block 内部），阻塞主线程会让 Compose 的提交一起排队，
     * 等多久都读不到新值。所以只有非主线程才等待，主线程保持原来的「立即读」。
     */
    private fun textReadbackMatches(node: AccessibilityNodeInfo, expected: String): Boolean {
        fun matches(): Boolean = runCatching { node.realText() }.getOrNull() == expected
        // 2026-10-27（fix185）：等待策略抽成 ReadbackWindow（可注入时钟），由单测覆盖；
        // 语义未变：非主线程在窗口内重试，主线程只试一次（主线程上等待只会把界面卡住）。
        val matched = TEXT_READBACK_WINDOW.await(
            isMainThread = Looper.myLooper() == Looper.getMainLooper(),
        ) {
            // refresh 失败不代表内容没更新（节点对象会被原地更新），所以不看它的返回值，
            // 只看读到的内容。
            runCatching { node.refresh() }
            matches()
        }
        if (matched) return true
        // 只在失败时留一行现场：哪个可编辑节点、期望与实际长度各多少（不记文本内容）。
        // 正常路径零日志 —— 文本写入是高频动作，每条都打会淹没 logcat。
        android.util.Log.w(
            "EtaTextWrite",
            "读回不匹配 class=" + node.className +
                " viewId=" + node.viewIdResourceName +
                " mainThread=" + (Looper.myLooper() == Looper.getMainLooper()) +
                " want=" + expected.length +
                " got=" + (runCatching { node.realText() }.getOrNull()?.length ?: -1),
        )
        return false
    }

    private sealed interface TextWriteTarget {
        data class Ready(
            val node: AccessibilityNodeInfo,
            val text: String,
            val cursor: Int,
        ) : TextWriteTarget

        data class Rejected(val result: NodeActionResult) : TextWriteTarget
    }

    /**
     * fix176：文本写入不再整段跑在无障碍主线程上。
     *
     * 真机证据（PKX110 / A16，主屏 Compose 输入框 replace）：调用方等主线程超过
     * [MAIN_SYNC_TIMEOUT_SECONDS] 秒，工具层于是拿到 `ACTION_OUTCOME_UNKNOWN`
     * 「系统未在时限内返回结果」——而文本其实已经写进去了。
     * 节点动作（performAction / refresh）本身是 Binder 调用，不要求主线程；
     * 只有「从窗口树里找焦点节点」这一步留给主线程（index 通路早就是这个结构）。
     */
    private fun writeTextPrepared(block: () -> TextWriteTarget): NodeActionResult =
        when (val prepared = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> when (val target = prepared.value) {
                null -> NodeActionResult.failure("ACTION_FAILED", "无障碍动作执行异常")
                is TextWriteTarget.Ready -> setNodeText(target.node, target.text, target.cursor)
                is TextWriteTarget.Rejected -> target.result
            }
            // 两种失败分开说：下面第一种连「找节点」都没开始，第二种是主线程里抛了错。
            // 两者都没有写过文本（block 只读不写），所以都可以安全重试 —— 但不能都说成
            // 「主线程无响应」，那会让“为什么没写成”在现场丢掉。
            MainThreadCallResult.NOT_STARTED -> NodeActionResult.failure(
                TextWriteFailures.CODE,
                TextWriteFailures.notStartedMessage(),
            )
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.failure(
                TextWriteFailures.CODE,
                TextWriteFailures.outcomeUnknownMessage(),
            )
        }

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
    ): NodeActionResult {
        val password = runCatching { node.isPassword }.getOrDefault(true)
        // 密码框读不到内容，但通常能读到长度（掩码字符数）。必须在写之前先记一次：
        // 写后长度恰好等于目标长度时，没有这个基准就分不清「这次写进去的」与「原本就这么长」。
        val beforePasswordLength = if (password) {
            runCatching { node.realText().length }.getOrNull()
        } else {
            null
        }
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            return NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝文本修改动作")
        }
        val safeCursor = cursor.coerceIn(0, text.length)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, safeCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, safeCursor)
        }
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        // fix176：旧写法是 `if (!refreshed || realText() != text) -> outcomeUnknown`，把
        // 「refresh 失败」和「内容不对」混成一个结果；而 refresh()=false 时节点对象自己可能
        // 已经带着新文本（AccessibilityNodeInfo 会被原地更新），于是写入成功也被报成
        // ACTION_OUTCOME_UNKNOWN（ok=false）。判定因此只看**内容相等**：内容相符就是证据，
        // 内容还没落地时再给一段有限的读回窗口（见 [textReadbackMatches]）。
        if (!password && !textReadbackMatches(node, text)) {
            return NodeActionResult.outcomeUnknown()
        }
        val selectionRestored = refreshed && node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArgs,
            )
        // 2026-10-27（fix185）：密码框原来一律 `verified = false` —— 等于**从不校验**，
        // 工具层永远回 unknown。掩码长度通常就是字符数，能用长度确认就应该确认；
        // 确认不了（长度没变、读不到）仍然回未确认，不猜。
        val verified = if (password) {
            TextInputVerification.passwordWriteConfirmed(
                beforeLength = beforePasswordLength,
                afterLength = readbackPasswordLength(node),
                typedLength = text.length,
            )
        } else {
            true
        }
        return NodeActionResult.success(
            method = if (selectionRestored) {
                "ACTION_SET_TEXT_AND_SELECTION"
            } else {
                "ACTION_SET_TEXT"
            },
            verified = verified,
        )
    }

    /**
     * 密码框写后读长度（在 [ReadbackWindow] 里等它出现）。
     * 读不到返回 null —— 调用方据此给「未确认」，不猜。
     */
    private fun readbackPasswordLength(node: AccessibilityNodeInfo): Int? {
        var last: Int? = null
        TEXT_READBACK_WINDOW.await(
            isMainThread = Looper.myLooper() == Looper.getMainLooper(),
        ) {
            runCatching { node.refresh() }
            val length = runCatching { node.realText().length }.getOrNull()
            last = length
            length != null && length > 0
        }
        return last
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocus = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (inputFocus?.isUsableInputTarget() == true) return inputFocus
        return findNode(root) { node -> node.isFocused && node.isUsableInputTarget() }
    }

    private fun AccessibilityNodeInfo.isUsableInputTarget(): Boolean =
        isEditable && isEnabled && isVisibleToUser

    private fun AccessibilityNodeInfo.incrementalTextValidationError(): NodeActionResult? {
        val currentText = text
        if (isPassword || currentText == null) {
            logTextContentUnavailable(this, isPassword)
            return NodeActionResult.failure(
                "TEXT_CONTENT_UNAVAILABLE",
                "当前输入框不允许可靠读取已有文本；请用 replace_text 提供完整值",
            )
        }
        if (
            !TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = currentText.length,
                selectionStart = textSelectionStart,
                selectionEnd = textSelectionEnd,
            )
        ) {
            logTextSelectionUnavailable(this, currentText.length, textSelectionStart, textSelectionEnd)
            return NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
            )
        }
        return null
    }

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): AccessibilityNodeInfo {
        val candidates = mutableListOf<ScrollCandidate>()
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                visitedNodes >= MAX_SCROLL_SEARCH_NODES ||
                !activePath.add(node)
            ) return
            visitedNodes++
            try {
                if (!node.isVisibleToUser) return
                if (node.isEnabled && node.hasScrollCapability()) {
                    val bounds = clippedNodeBounds(node)
                    val supportRank = node.directionSupportRank(direction)
                    if (
                        !node.exposesOnlyOppositeAxis(direction) &&
                        !bounds.isEmpty &&
                        direction.gestureWithin(bounds) != null
                    ) {
                        candidates += ScrollCandidate(
                            node = node,
                            supportRank = supportRank,
                            depth = depth,
                            area = bounds.width().toLong() * bounds.height().toLong(),
                        )
                    }
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return candidates.maxWithOrNull(
            compareBy<ScrollCandidate> { it.area }
                .thenBy { it.supportRank }
                .thenBy { -it.depth },
        )?.node ?: root
    }

    private fun AccessibilityNodeInfo.directionSupportRank(direction: ScrollDirection): Int {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return when {
            direction.exactScrollActionId() in actions -> 4
            direction.pageActionId() in actions -> 3
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            ) && (
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
                ) -> 2
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id in actions -> 1
            else -> 0
        }
    }

    private fun AccessibilityNodeInfo.exposesOnlyOppositeAxis(
        direction: ScrollDirection,
    ): Boolean {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return ScrollAxisContract.exposesOnlyOppositeAxis(
            requestedAxis = direction.axis,
            hasVerticalActions = hasVertical,
            hasHorizontalActions = hasHorizontal,
        )
    }

    private fun AccessibilityNodeInfo.hasScrollCapability(): Boolean {
        if (isScrollable) return true
        val actions = supportedActionIds()
        return SCROLL_ACTION_IDS.any(actions::contains)
    }

    private fun AccessibilityNodeInfo.supportedActionIds(): Set<Int> =
        actionList.mapTo(hashSetOf()) { action -> action.id }

    private fun chooseScrollMethod(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): ScrollMethod? {
        if (node.exposesOnlyOppositeAxis(direction)) return null
        val actionIds = node.supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val exactAction = direction.exactScrollActionId()
        if (exactAction in actionIds) {
            return ScrollMethod(exactAction, "ACTION_SCROLL_${direction.name}")
        }
        val pageAction = direction.pageActionId()
        if (pageAction in actionIds) {
            return ScrollMethod(pageAction, "ACTION_PAGE_${direction.name}")
        }
        if (
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            val fallback = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (fallback in actionIds) {
                return ScrollMethod(
                    actionId = fallback,
                    name = if (direction == ScrollDirection.DOWN) {
                        "ACTION_SCROLL_FORWARD"
                    } else {
                        "ACTION_SCROLL_BACKWARD"
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val inDirection = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
            if (inDirection in actionIds) {
                val args = Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT,
                        direction.focusDirection(),
                    )
                }
                return ScrollMethod(inDirection, "ACTION_SCROLL_IN_DIRECTION", args)
            }
        }
        return null
    }

    private fun isAtScrollBoundary(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signal: ScrollSignal? = null,
    ): Boolean {
        if (signal != null && signal.axisDelta(direction.axis) != null) {
            when (direction) {
                ScrollDirection.UP -> if (
                    signal.maxScrollY > 0 && signal.scrollY <= 0
                ) return true
                ScrollDirection.DOWN -> if (
                    signal.maxScrollY > 0 && signal.scrollY >= signal.maxScrollY
                ) return true
                ScrollDirection.LEFT -> if (
                    signal.maxScrollX > 0 && signal.scrollX <= 0
                ) return true
                ScrollDirection.RIGHT -> if (
                    signal.maxScrollX > 0 && signal.scrollX >= signal.maxScrollX
                ) return true
            }
        }
        if (chooseScrollMethod(node, direction) != null) return false
        return chooseScrollMethod(node, direction.opposite()) != null
    }

    private fun clippedNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = node.bounds()
        val size = displaySize()
        if (size != null) {
            if (!bounds.intersect(0, 0, size.first, size.second)) bounds.setEmpty()
        }
        return bounds
    }

    private fun scrollContentAnchors(root: AccessibilityNodeInfo): List<ScrollAnchor> {
        val anchors = ArrayList<ScrollAnchor>(SCROLL_ANCHOR_NODE_LIMIT)
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        val rootBounds = root.bounds()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                anchors.size >= SCROLL_ANCHOR_NODE_LIMIT ||
                !activePath.add(node)
            ) return
            try {
                if (!node.isVisibleToUser) return
                val bounds = node.bounds()
                val key = buildString {
                    append(node.uniqueId.orEmpty())
                    append('|')
                    append(node.className?.toString().orEmpty())
                    append('|')
                    append(node.viewIdResourceName.orEmpty())
                    append('|')
                    append(node.text?.toString().orEmpty().take(80))
                    append('|')
                    append(node.contentDescription?.toString().orEmpty().take(80))
                }
                if (
                    node.uniqueId?.isNotBlank() == true ||
                    node.viewIdResourceName?.isNotBlank() == true ||
                    node.text?.isNotBlank() == true ||
                    node.contentDescription?.isNotBlank() == true
                ) {
                    anchors += ScrollAnchor(
                        key = key,
                        centerX = bounds.centerX() - rootBounds.left,
                        centerY = bounds.centerY() - rootBounds.top,
                    )
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return anchors
    }

    private fun inferScrollAnchorDelta(
        before: List<ScrollAnchor>,
        after: List<ScrollAnchor>,
        direction: ScrollDirection,
    ): Int? {
        fun unique(anchors: List<ScrollAnchor>): Map<String, ScrollAnchor> = anchors
            .groupBy(ScrollAnchor::key)
            .mapNotNull { (key, matches) -> matches.singleOrNull()?.let { anchor -> key to anchor } }
            .toMap()
        val beforeUnique = unique(before)
        val afterUnique = unique(after)
        val contentDeltas = beforeUnique.mapNotNull { (key, oldAnchor) ->
            val newAnchor = afterUnique[key] ?: return@mapNotNull null
            if (direction.axis == ScrollAxis.VERTICAL) {
                newAnchor.centerY - oldAnchor.centerY
            } else {
                newAnchor.centerX - oldAnchor.centerX
            }
        }
        return RootScrollMotionContract.inferScrollDelta(contentDeltas)
    }

    private fun ScrollDirection.exactScrollActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
    }

    private fun ScrollDirection.pageActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
    }

    private fun ScrollDirection.focusDirection(): Int = when (this) {
        ScrollDirection.UP -> View.FOCUS_UP
        ScrollDirection.DOWN -> View.FOCUS_DOWN
        ScrollDirection.LEFT -> View.FOCUS_LEFT
        ScrollDirection.RIGHT -> View.FOCUS_RIGHT
    }

    private fun ScrollDirection.opposite(): ScrollDirection = when (this) {
        ScrollDirection.UP -> ScrollDirection.DOWN
        ScrollDirection.DOWN -> ScrollDirection.UP
        ScrollDirection.LEFT -> ScrollDirection.RIGHT
        ScrollDirection.RIGHT -> ScrollDirection.LEFT
    }

    private fun withValidatedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (AccessibilityNodeInfo) -> NodeActionResult,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        block(indexed.node)
    }

    private fun withValidatedIndexedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (IndexedNode) -> NodeActionResult,
    ): NodeActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return NodeActionResult.failure("SERVICE_TIMEOUT", "无障碍服务主线程无响应")
        return when (validation) {
            is NodeValidation.Invalid -> validation.result
            is NodeValidation.Valid -> block(validation.indexedNode)
        }
    }

    private fun validateNode(snapshot: NodeSnapshot, index: Int): NodeValidation {
        if (snapshot.serviceToken != serviceToken) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("SERVICE_RECONNECTED", "无障碍服务已重连，请重新观察屏幕"),
            )
        }
        val indexed = snapshot.indexedNodes.firstOrNull { it.index == index }
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index"),
            )
        // rootInActiveWindow 永远只指向默认屏；虚拟屏必须用同一 display 的窗口根节点，
        // 否则每次节点动作都会被误判成 STALE_WINDOW（fix98 前 tap_element 在虚拟屏必失败）。
        val activeRoot = if (snapshot.displayId != Display.DEFAULT_DISPLAY) {
            virtualDisplayRoot(snapshot.displayId)
        } else {
            rootInActiveWindow
        } ?: return NodeValidation.Invalid(
            NodeActionResult.failure("STALE_WINDOW", "当前活动窗口不可访问，请重新观察屏幕"),
        )
        val activePackage = activeRoot.packageName?.toString().orEmpty()
        if (activeRoot.windowId != snapshot.windowId || activePackage != snapshot.packageName) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "活动窗口已经变化，请重新观察屏幕"),
            )
        }
        val node = indexed.node
        // fix171：先 refresh 再判「内容变化」——空输入框的兜底判定要看刷新后的几何指纹。
        if (!runCatching { node.refresh() }.getOrDefault(false)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_NODE", "目标节点已经失效，请重新观察屏幕"),
            )
        }
        if (
            windowContentGeneration(snapshot.windowId) != snapshot.contentGeneration &&
            !snapshot.hasUnambiguousIdentity(indexed) &&
            !snapshot.canActOnInputDespiteContentChange(indexed, node)
        ) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_CONTENT", "窗口内容已经变化，请重新观察屏幕"),
            )
        }
        if (!indexed.identityMatches(node)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("IDENTITY_CHANGED", "目标节点内容或身份已经变化，请重新观察屏幕"),
            )
        }
        if (!node.isVisibleToUser || !node.isEnabled) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("NODE_NOT_ACTIONABLE", "目标节点当前不可见或不可用"),
            )
        }
        return NodeValidation.Valid(indexed)
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = hashSetOf<AccessibilityNodeInfo>()
        queue.addLast(node to 0)
        while (queue.isNotEmpty() && visited.size < MAX_FOCUS_SEARCH_NODES) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return current
            if (depth >= MAX_UI_TREE_DEPTH) continue
            for (childIndex in 0 until current.childCount) {
                val child = runCatching { current.getChild(childIndex) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<IndexedNode>,
        maxNodes: Int,
        depth: Int,
        traversal: NodeTraversalState,
        path: String,
        clickableAncestor: NodeActionTarget? = null,
        longClickableAncestor: NodeActionTarget? = null,
        scrollableAncestor: NodeActionTarget? = null,
    ) {
        if (out.size >= maxNodes) {
            traversal.truncated = true
            return
        }
        if (depth > MAX_UI_TREE_DEPTH || traversal.visitedNodes >= traversal.maxVisitedNodes) {
            traversal.truncated = true
            return
        }
        if (!traversal.activePath.add(node)) {
            traversal.truncated = true
            return
        }
        traversal.visitedNodes++
        try {
            // 不可见父节点的后代不会成为可操作目标，尽早裁掉这类大分支。
            val visible = node.isVisibleToUser
            if (depth > 0 && !visible) return

            val bounds = node.bounds()
            // fix178：这里原来写死 take(120) —— 线格式那边承诺「超过 200 字才截断并标 text_clipped」，
            // 可上游先砍到 120，线格式就再没机会出现超长，标记永远不会出现。真机实测：243 字的消息
            // 在 ui_nodes 里只有 120 字、整份报告 text_clipped 出现 0 次，模型把片段当成了全文。
            // 现在上限与线格式共用同一个常量，原始长度带进 IndexedNode / UiNode。
            val fullText = node.realText()
            val fullDesc = node.contentDescription?.toString().orEmpty()
            val text = fullText.take(UiNodeWire.MAX_NODE_TEXT_CHARS)
            // fix179：hint 原来写死 take(60)，而线格式上限是 MAX_HINT_CHARS=120 —— 和 fix178
            // 是同一个病：采集端砍在线格式上限之下，超长提示语就被静默截断（hint_clipped 永不出现）。
            // 现在共用同一个上限，原始长度带进 IndexedNode / UiNode。
            val fullHint = node.hintText?.toString().orEmpty()
            val hint = fullHint.take(UiNodeWire.MAX_HINT_CHARS)
            val desc = fullDesc.take(UiNodeWire.MAX_NODE_TEXT_CHARS)
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val scrollable = node.isScrollable
            val focused = node.isFocused
            val editable = node.isEditable
            val password = node.isPassword
            val enabled = node.isEnabled
            val clickTarget = if (clickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                clickableAncestor
            }
            val longClickTarget = if (longClickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                longClickableAncestor
            }
            val hasScrollCapability = enabled && node.hasScrollCapability()
            val scrollTarget = if (hasScrollCapability) {
                NodeActionTarget.capture(node)
            } else {
                scrollableAncestor
            }
            val useful = text.isNotBlank() ||
                desc.isNotBlank() ||
                clickable ||
                longClickable ||
                hasScrollCapability ||
                focused ||
                editable
            if (visible && bounds.width() > 2 && bounds.height() > 2 && useful) {
                val stableId = node.uniqueId?.takeIf { it.isNotBlank() }
                    ?: "w${node.windowId}:$path"
                out += IndexedNode(
                    index = out.size,
                    node = node,
                    uniqueId = node.uniqueId.orEmpty(),
                    stableId = stableId,
                    windowId = node.windowId,
                    text = text,
                    desc = desc,
                    textFullLength = fullText.length,
                    descFullLength = fullDesc.length,
                    className = node.className?.toString().orEmpty(),
                    packageName = node.packageName?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    bounds = Rect(bounds),
                    clickable = clickable,
                    longClickable = longClickable,
                    // 只表示"这个节点自己可滚动"；祖先滚动容器由 scrollTarget 单独承载，
                    // 否则每个子项都会被标成 scrollable，模型会误判整棵树都能滚。
                    scrollable = hasScrollCapability,
                    focused = focused,
                    editable = editable,
                    password = password,
                    enabled = enabled,
                    checked = node.isCheckable.takeIf { it }?.let { node.isChecked },
                    selected = node.isSelected,
                    hint = hint,
                    hintFullLength = fullHint.length,
                    clickTarget = clickTarget,
                    longClickTarget = longClickTarget,
                    scrollTarget = scrollTarget,
                )
            }
            for (index in 0 until node.childCount) {
                if (
                    out.size >= maxNodes ||
                    traversal.visitedNodes >= traversal.maxVisitedNodes
                ) {
                    traversal.truncated = true
                    return
                }
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                collectNodes(
                    node = child,
                    out = out,
                    maxNodes = maxNodes,
                    depth = depth + 1,
                    traversal = traversal,
                    path = "$path.$index",
                    clickableAncestor = clickTarget,
                    longClickableAncestor = longClickTarget,
                    scrollableAncestor = scrollTarget,
                )
            }
        } finally {
            traversal.activePath.remove(node)
        }
    }

    private fun AccessibilityNodeInfo.bounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null
    ): ActionDispatch =
        when (val result = callOnMainSync {
            if (args == null) node.performAction(action) else node.performAction(action, args)
        }) {
            is MainThreadCallResult.Completed -> {
                if (result.value == true) ActionDispatch.ACCEPTED else ActionDispatch.REJECTED
            }
            MainThreadCallResult.NOT_STARTED -> ActionDispatch.REJECTED
            MainThreadCallResult.OUTCOME_UNKNOWN -> ActionDispatch.OUTCOME_UNKNOWN
        }

    private fun dispatchGestureResult(
        path: Path,
        durationMs: Long,
        successMethod: String,
    ): NodeActionResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return NodeActionResult.failure(
                "GESTURE_NOT_DISPATCHED",
                "不能在无障碍主线程同步等待手势",
            )
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        val outcome = java.util.concurrent.atomic.AtomicReference<GestureDispatch>()
        val posted = mainHandler.post {
            if (!gate.tryStart()) {
                latch.countDown()
                return@post
            }
            val gesture = runCatching {
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            }.getOrElse {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
                return@post
            }
            val dispatched = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.COMPLETED)
                            gate.finish()
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.CANCELLED)
                            gate.finish()
                            latch.countDown()
                        }
                    },
                    GESTURE_CALLBACK_HANDLER,
                )
            } catch (_: Throwable) {
                // Binder 事务可能已经送达；异常不能证明手势未执行，禁止 Root 重放。
                outcome.set(GestureDispatch.OUTCOME_UNKNOWN)
                gate.finish()
                latch.countDown()
                return@post
            }
            if (!dispatched) {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
            }
        }
        if (!posted) {
            return NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "无障碍主线程拒绝手势任务")
        }
        val finishedInTime = try {
            latch.await(durationMs + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finishedInTime) {
            if (gate.cancelIfPending()) {
                return NodeActionResult.failure(
                    "GESTURE_NOT_DISPATCHED",
                    "无障碍主线程繁忙，手势已在执行前取消",
                )
            }
            return NodeActionResult.outcomeUnknown()
        }
        return when (outcome.get()) {
            GestureDispatch.COMPLETED -> NodeActionResult.success(successMethod)
            GestureDispatch.CANCELLED -> NodeActionResult.outcomeUnknown()
            GestureDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            GestureDispatch.NOT_DISPATCHED,
            null -> NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "系统拒绝手势任务")
        }
    }

    private fun runNodeActionOnMainSync(block: () -> NodeActionResult): NodeActionResult =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
                ?: NodeActionResult.failure("ACTION_FAILED", "无障碍动作执行异常")
            MainThreadCallResult.NOT_STARTED ->
                NodeActionResult.failure("SERVICE_TIMEOUT", "无障碍服务主线程无响应，动作未执行")
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
        }

    private fun <T> runOnMainSync(block: () -> T): T? =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
            MainThreadCallResult.NOT_STARTED,
            MainThreadCallResult.OUTCOME_UNKNOWN -> null
        }

    private fun <T> callOnMainSync(block: () -> T): MainThreadCallResult<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                MainThreadCallResult.Completed(block())
            } catch (error: Throwable) {
                // 抛错时调用方只会拿到「结果未知」，不留原因的话现场就彻底丢了。
                android.util.Log.w(
                    "EtaA11y",
                    "main-thread throwable=" + error.javaClass.name + ": " + error.message,
                )
                // 框架调用抛错时无法证明副作用没有发生，禁止调用方回退重放。
                MainThreadCallResult.OUTCOME_UNKNOWN
            }
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        var value: T? = null
        var failed = false
        val posted = mainHandler.post {
            if (gate.tryStart()) {
                try {
                    value = block()
                } catch (error: Throwable) {
                    android.util.Log.w(
                        "EtaA11y",
                        "posted throwable=" + error.javaClass.name + ": " + error.message,
                    )
                    failed = true
                } finally {
                    gate.finish()
                }
            }
            latch.countDown()
        }
        if (!posted) return MainThreadCallResult.NOT_STARTED
        val completed = try {
            latch.await(MAIN_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (gate.cancelIfPending()) return MainThreadCallResult.NOT_STARTED
            // 已开始的副作用不得由调用方回退重做；返回未知结果，让模型先重新观察。
            return MainThreadCallResult.OUTCOME_UNKNOWN
        }
        if (failed) return MainThreadCallResult.OUTCOME_UNKNOWN
        return MainThreadCallResult.Completed(value)
    }

    data class NodeActionResult(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val clipboardWritten: Boolean = false,
        val verified: Boolean? = null,
    ) {
        companion object {
            fun success(method: String, verified: Boolean? = null): NodeActionResult =
                NodeActionResult(ok = true, method = method, verified = verified)

            fun failure(code: String, message: String): NodeActionResult =
                NodeActionResult(ok = false, code = code, message = message)

            fun outcomeUnknown(): NodeActionResult = failure(
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "动作可能已执行，但系统未在时限内返回结果；请先重新观察，避免重复操作",
            )
        }
    }

    private enum class ActionDispatch {
        ACCEPTED,
        REJECTED,
        OUTCOME_UNKNOWN,
    }

    private enum class GestureDispatch {
        COMPLETED,
        CANCELLED,
        NOT_DISPATCHED,
        OUTCOME_UNKNOWN,
    }

    private sealed interface MainThreadCallResult<out T> {
        data class Completed<T>(val value: T?) : MainThreadCallResult<T>
        data object NOT_STARTED : MainThreadCallResult<Nothing>
        data object OUTCOME_UNKNOWN : MainThreadCallResult<Nothing>
    }

    data class ScreenshotCaptureResult(
        val bitmap: Bitmap?,
        val complete: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int>,
        val failureCodes: Map<Int, Int>,
        val timedOut: Boolean,
        val criticalWindowMissing: Boolean,
    ) {
        val partial: Boolean get() = bitmap != null && !complete

        companion object {
            fun unavailable(): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = 0,
                capturedWindows = 0,
                missingWindowIds = emptyList(),
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = false,
            )

            fun blockedByUnknownWindow(
                expectedWindows: Int,
                windowIds: List<Int>,
            ): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = expectedWindows,
                capturedWindows = 0,
                missingWindowIds = windowIds,
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = true,
            )
        }
    }

    data class ClipboardReadResult(
        val ok: Boolean,
        val text: String = "",
        val code: String = "",
    ) {
        companion object {
            fun failure(code: String = "CLIPBOARD_UNAVAILABLE_OR_EMPTY"): ClipboardReadResult =
                ClipboardReadResult(ok = false, code = code)
        }
    }

    internal data class ScrollActionResult(
        val ok: Boolean,
        val direction: ScrollDirection,
        val moved: Boolean,
        val atBoundary: Boolean?,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val deltaX: Int? = null,
        val deltaY: Int? = null,
        val verifiedBy: String = "",
        val elapsedMs: Long = 0L,
        val targetIndex: Int? = null,
    ) {
        companion object {
            fun boundary(
                direction: ScrollDirection,
                method: String,
                targetIndex: Int?,
                elapsedMs: Long,
            ): ScrollActionResult = ScrollActionResult(
                ok = true,
                direction = direction,
                moved = false,
                atBoundary = true,
                method = method,
                verifiedBy = "action_list",
                elapsedMs = elapsedMs,
                targetIndex = targetIndex,
            )

            fun failure(
                direction: ScrollDirection,
                code: String,
                message: String,
                method: String = "",
                targetIndex: Int? = null,
                deltaX: Int? = null,
                deltaY: Int? = null,
                elapsedMs: Long = 0L,
            ): ScrollActionResult = ScrollActionResult(
                ok = false,
                direction = direction,
                moved = false,
                atBoundary = null,
                code = code,
                message = message,
                method = method,
                targetIndex = targetIndex,
                deltaX = deltaX,
                deltaY = deltaY,
                elapsedMs = elapsedMs,
            )
        }
    }

    class NodeSnapshot internal constructor(
        val id: String,
        internal val serviceToken: Long,
        val packageName: String,
        val windowId: Int,
        /** 节点所属 display；主屏为 0，虚拟屏为实际 displayId。 */
        val displayId: Int = Display.DEFAULT_DISPLAY,
        internal val contentGeneration: Long,
        val capturedAtElapsedMs: Long,
        val truncated: Boolean,
        internal val indexedNodes: List<IndexedNode>,
    ) {
        val nodes: List<UiNode> = indexedNodes.map(IndexedNode::toUiNode)

        internal fun hasUnambiguousIdentity(target: IndexedNode): Boolean {
            if (!target.hasStrongIdentity()) return false
            val hasUniqueId = target.uniqueId.isNotBlank()
            val matches = if (hasUniqueId) {
                indexedNodes.count { candidate -> candidate.uniqueId == target.uniqueId }
            } else {
                indexedNodes.count(target::sameSemanticIdentity)
            }
            return AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = hasUniqueId,
                snapshotTruncated = truncated,
                identityMatchCount = matches,
            )
        }

        /**
         * fix171：输入框在内容变化窗口里的兜底（见 `canBypassContentChangeForInputField`）。
         *
         * 为什么不能用空框条件：输入框一旦有了内容，文本指纹在截断快照里仍然不算唯一；
         * 真机 TEXT3（同框清空）就是因此拿到 IDENTITY_CHANGED，文本留在框里没清掉。
         */
        internal fun canActOnInputDespiteContentChange(
            target: IndexedNode,
            refreshed: AccessibilityNodeInfo,
        ): Boolean {
            if (!target.editable) return false
            val structuralMatches = indexedNodes.count(target::sameStructuralIdentity)
            return AccessibilityIdentityFreshnessPolicy.canBypassContentChangeForInputField(
                structuralMatchCount = structuralMatches,
                refreshedStructureUnchanged = target.structurallyUnchanged(refreshed),
            )
        }
    }

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
        /** 开关、勾选框等的状态；null 表示节点不可勾选。 */
        val checked: Boolean? = null,
        val selected: Boolean = false,
        /** 输入框提示语，只在内容为空时有意义，用于识别"这是哪个输入框"。 */
        val hint: String = "",
        /** AccessibilityNodeInfo.uniqueId；用于稳定身份与 diff。 */
        val nodeId: String = "",
        /** `text` 被 200 字上限砍之前的原始长度；0 = 未知（不标截断）。 */
        val textFullLength: Int = 0,
        /** `desc` 的原始长度；0 = 未知。 */
        val descFullLength: Int = 0,
        /** `hint` 的原始长度；0 = 未知。 */
        val hintFullLength: Int = 0,
    )

    internal data class IndexedNode(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val uniqueId: String,
        /** 输出给模型/用于 diff 的稳定 ID：优先无障碍 uniqueId，否则用窗口内结构路径。 */
        val stableId: String,
        val windowId: Int,
        val text: String,
        val desc: String,
        /** `text` 的原始长度（未被 200 字上限砍之前）；0 表示未知。 */
        val textFullLength: Int = 0,
        /** `desc` 的原始长度；0 表示未知。 */
        val descFullLength: Int = 0,
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
        val checked: Boolean?,
        val selected: Boolean,
        val hint: String,
        /** `hint` 的原始长度（未被 MAX_HINT_CHARS 砍之前）；0 表示未知。 */
        val hintFullLength: Int = 0,
        val clickTarget: NodeActionTarget?,
        val longClickTarget: NodeActionTarget?,
        val scrollTarget: NodeActionTarget?,
    ) {
        fun hasStrongIdentity(): Boolean = identity().strong

        /** 空输入框的几何身份：类名 + viewId + 提示语 + 屏幕矩形（不含 text/desc）。 */
        private fun structuralIdentityKey(): String =
            listOf(
                windowId.toString(),
                packageName,
                className,
                viewId,
                hint,
                "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            ).joinToString("\u0000")

        fun sameStructuralIdentity(other: IndexedNode): Boolean =
            editable && other.editable && structuralIdentityKey() == other.structuralIdentityKey()

        /** 刷新后仍是同一个位置的同一类控件（矩形、类名、viewId 都没变）。 */
        fun structurallyUnchanged(refreshed: AccessibilityNodeInfo): Boolean {
            val rect = Rect()
            refreshed.getBoundsInScreen(rect)
            return bounds == rect &&
                className == refreshed.className?.toString().orEmpty() &&
                viewId == refreshed.viewIdResourceName.orEmpty()
        }

        fun sameSemanticIdentity(other: IndexedNode): Boolean =
            windowId == other.windowId &&
                packageName == other.packageName &&
                className == other.className &&
                viewId == other.viewId &&
                text == other.text &&
                desc == other.desc

        fun identityMatches(refreshed: AccessibilityNodeInfo): Boolean =
            identity().matches(refreshed.toIdentity())

        private fun identity(): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
            uniqueId = uniqueId,
            windowId = windowId,
            packageName = packageName,
            className = className,
            viewId = viewId,
            text = text,
            description = desc,
            password = password,
        )

        private fun AccessibilityNodeInfo.toIdentity(): AccessibilityNodeIdentity =
            AccessibilityNodeIdentity(
                uniqueId = uniqueId.orEmpty(),
                windowId = windowId,
                packageName = packageName?.toString().orEmpty(),
                className = className?.toString().orEmpty(),
                viewId = viewIdResourceName.orEmpty(),
                text = (if (isShowingHintText) "" else text?.toString().orEmpty()).take(UiNodeWire.MAX_TEXT_CHARS),
                description = contentDescription?.toString().orEmpty().take(UiNodeWire.MAX_TEXT_CHARS),
                password = isPassword,
            )

        fun toUiNode(): UiNode =
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
                nodeId = stableId,
                textFullLength = textFullLength,
                descFullLength = descFullLength,
                hintFullLength = hintFullLength,
            )
    }

    internal data class NodeActionTarget(
        val node: AccessibilityNodeInfo,
        val identity: AccessibilityNodeIdentity,
    ) {
        fun resolveFor(descendant: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!runCatching { node.refresh() }.getOrDefault(false)) return null
            if (!node.isVisibleToUser || !node.isEnabled) return null
            val refreshedIdentity = AccessibilityNodeIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                windowId = node.windowId,
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                text = node.text?.toString().orEmpty().take(UiNodeWire.MAX_TEXT_CHARS),
                description = node.contentDescription?.toString().orEmpty().take(UiNodeWire.MAX_TEXT_CHARS),
                password = node.isPassword,
            )
            if (!identity.matches(refreshedIdentity)) return null
            var current: AccessibilityNodeInfo? = descendant
            var depth = 0
            while (current != null && depth <= MAX_UI_TREE_DEPTH) {
                if (current == node) return node
                current = current.parent
                depth++
            }
            return null
        }

        companion object {
            fun capture(node: AccessibilityNodeInfo): NodeActionTarget = NodeActionTarget(
                node = node,
                identity = AccessibilityNodeIdentity(
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    packageName = node.packageName?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty().take(UiNodeWire.MAX_TEXT_CHARS),
                    description = node.contentDescription?.toString().orEmpty().take(UiNodeWire.MAX_TEXT_CHARS),
                    password = node.isPassword,
                ),
            )
        }
    }

    private data class ScrollCandidate(
        val node: AccessibilityNodeInfo,
        val supportRank: Int,
        val depth: Int,
        val area: Long,
    )

    private data class ScrollAnchor(
        val key: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class ScrollMethod(
        val actionId: Int,
        val name: String,
        val args: Bundle? = null,
    )

    private data class ScrollSignal(
        val sequence: Long,
        val packageName: String,
        val windowId: Int,
        val deltaX: Int,
        val deltaY: Int,
        val scrollX: Int,
        val scrollY: Int,
        val maxScrollX: Int,
        val maxScrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val sourceUniqueId: String,
        val sourceViewId: String,
        val sourceClassName: String,
        val sourceBounds: Rect,
    ) {
        fun axisDelta(axis: ScrollAxis): Int? = ScrollEvidenceContract.normalizeAccessibilityDelta(
            when (axis) {
            ScrollAxis.HORIZONTAL -> deltaX
            ScrollAxis.VERTICAL -> deltaY
            },
        )

        fun matchesTarget(target: ScrollTargetIdentity): Boolean {
            if (target.uniqueId.isNotBlank() && sourceUniqueId.isNotBlank()) {
                return target.uniqueId == sourceUniqueId
            }
            if (target.viewId.isNotBlank() && sourceViewId.isNotBlank()) {
                return target.viewId == sourceViewId && target.className == sourceClassName
            }
            return target.className == sourceClassName && target.bounds == sourceBounds
        }
    }

    private data class ScrollTargetIdentity(
        val uniqueId: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
    ) {
        companion object {
            fun from(node: AccessibilityNodeInfo): ScrollTargetIdentity = ScrollTargetIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Rect().also(node::getBoundsInScreen),
            )
        }
    }

    companion object {
        private const val CLIP_LABEL = "eta_agent"
        private const val WINDOW_POLL_FALLBACK_MS = 80L
        private const val MAX_UI_TREE_DEPTH = 24
        private const val UI_TREE_VISIT_MULTIPLIER = 8
        private const val MIN_UI_TREE_VISITED_NODES = 128
        private const val MAX_UI_TREE_VISITED_NODES = 960
        private const val MAX_FOCUS_SEARCH_NODES = 512
        private const val MAX_SCROLL_SEARCH_NODES = 512
        private const val SCROLL_ANCHOR_NODE_LIMIT = 48
        private const val SCROLL_GESTURE_DURATION_MS = 300L
        private const val SCROLL_VERIFY_TIMEOUT_MS = 520L
        private const val GESTURE_CALLBACK_GRACE_MS = 1_500L
        private const val MAIN_SYNC_TIMEOUT_SECONDS = AgentAccessibilityTimeouts.MAIN_SYNC_SECONDS

        // 2026-10-27（fix185）：读回窗口与轮询间隔挪到 AgentAccessibilityTimeouts（纯 Kotlin），
        // 这样「观察窗口必须覆盖主线程等待」这类不变量能被单测守住。
        private val TEXT_READBACK_WINDOW = ReadbackWindow(
            timeoutMs = AgentAccessibilityTimeouts.TEXT_READBACK_MS,
            intervalMs = AgentAccessibilityTimeouts.TEXT_READBACK_INTERVAL_MS,
            clock = { SystemClock.uptimeMillis() },
            sleeper = { ms -> SystemClock.sleep(ms) },
        )
        private const val SCROLL_EVENT_OBSERVATION_TIMEOUT_MS =
            AgentAccessibilityTimeouts.SCROLL_EVENT_OBSERVATION_MS
        private const val SCROLL_EVENT_QUEUE_CAPACITY = 1
        private const val MAX_SCROLL_SIGNALS = 64

        private val GESTURE_CALLBACK_THREAD = HandlerThread("agent-gesture-callback").apply {
            isDaemon = true
            start()
        }
        private val GESTURE_CALLBACK_HANDLER = Handler(GESTURE_CALLBACK_THREAD.looper)

        /**
         * 进程级 executor 不在 service 重连时 shutdownNow；否则已经由框架创建、
         * 尚在队列中的 ScreenshotResult 无法进入回调释放 HardwareBuffer。
         */
        private val SCREENSHOT_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "agent-screenshot-callback").apply { isDaemon = true }
            }

        private val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        ).let { actions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                actions + AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
            } else {
                actions
            }
        }
        private val VERTICAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        )
        private val HORIZONTAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )

        private val SERVICE_TOKENS = AtomicLong(0)
        private val SNAPSHOT_IDS = AtomicLong(0)
        private val CLIP_IDS = AtomicLong(0)

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }

    private sealed interface NodeValidation {
        data class Valid(val indexedNode: IndexedNode) : NodeValidation
        data class Invalid(val result: NodeActionResult) : NodeValidation
    }
}
