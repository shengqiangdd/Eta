package io.github.mangi.eta.agent.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟屏控制器：让 Agent 在**独立屏幕**上操作应用，完全不抢主屏。
 *
 * 设计要点（对应 memory §159「虚拟屏不抢主屏工作」）：
 * 1. **不抢主屏**：虚拟屏带 `FLAG_OWN_FOCUS`（16384 = 1<<14，@hide 常量，
 *    值已在本机 Android 16 framework dex 实测确认：`VIRTUAL_DISPLAY_FLAG_OWN_FOCUS`），
 *    输入焦点只进入虚拟屏，主屏始终属于用户。再叠加 `FLAG_TOUCH_FEEDBACK_DISABLED`
 *    （8192，@hide）避免在主屏上出现触摸反馈浮层。
 * 2. **不镜像主屏**：带 `FLAG_OWN_CONTENT_ONLY`，只显示自己的内容，不会把主屏画面搬进来，
 *    Agent 也看不到用户正在做什么。
 * 3. **可截屏**：`ImageReader` 取帧，不经 `screencap`（screencap 只能截默认屏）。
 * 4. **输入走 display id**：`input -d <id> ...` 与 `am start --display <id>` 是系统命令。
 * 5. **有 UI 树（fix98 起）**：显示必须同时带 `FLAG_PUBLIC`。AOSP
 *    `AccessibilityManagerService.isValidDisplay()` 会把「TYPE_VIRTUAL + FLAG_PRIVATE +
 *    owner 不是 SystemUI」的屏直接排除在无障碍跟踪之外 —— 这是 fix87/92/93 误判
 *    「系统不向无障碍服务暴露虚拟屏」的真正原因。PUBLIC 后 `getWindowsOnAllDisplays()`
 *    会包含这块屏，`observe_screen(display_id=...)` 能拿到节点，`tap_element` 等可用。
 */
internal class VirtualDisplayController(
    private val context: Context,
    private val logger: AgentLogger,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    data class Session(
        val id: String,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val createdAtMs: Long,
        val purpose: String,
        /** 建这块屏的会话 id；空串表示没有会话（语音 / 一次性入口）。 */
        val ownerConversationId: String = "",
        /** 建这块屏的运行 id：没有会话时用它区分归属，也用于诊断。 */
        val ownerRunId: String = "",
        /**
         * 最后一次被真正使用的时间（list/launch/截图/坐标动作都会刷新）。
         * 空闲回收按它算，而不是创建时间：一直在用的屏不该被收走。
         */
        @Volatile var lastUsedAtMs: Long = createdAtMs,
    ) {
        val ageSeconds: Int get() = ((System.currentTimeMillis() - createdAtMs) / 1000).toInt()
        val idleSeconds: Int
            get() = (((System.currentTimeMillis() - lastUsedAtMs) / 1000).toInt()).coerceAtLeast(0)
        /** 估算的图形内存（ImageReader 双缓冲），与 VirtualDisplayBudget 用同一个公式。 */
        val estimatedBytes: Long get() = VirtualDisplayBudget.bufferBytes(width, height)
    }

    /** 准入被拒。带错误码，工具层据此回具体原因，而不是笼统的「创建失败」。 */
    class RejectedException(val code: String, override val message: String) : IllegalStateException(message)

    private val displayManager: DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val sessions = ConcurrentHashMap<String, Session>()
    private val displays = ConcurrentHashMap<String, VirtualDisplay>()
    private val readers = ConcurrentHashMap<String, ImageReader>()
    private val surfaces = ConcurrentHashMap<String, android.view.Surface>()
    private val callbackThreads = ConcurrentHashMap<String, HandlerThread>()
    private val lastObservedPackage = ConcurrentHashMap<String, String>()

    /**
     * 每块屏最后一次成功截到的 PNG。
     *
     * 为什么需要：ImageReader 只在**有新帧**时产出 buffer。取走一帧（close）之后，
     * 只要屏幕静止（没有动画、没有重绘），下一次 acquireLatestImage 就返回 null，
     * 接口只能报 VIRTUAL_FRAME_NOT_READY。而 Agent 的典型动作是「操作 → 截图 → 再截图」，
     * 静止场景下第二张必然失败。缓存上一帧，至少让重复观察有图可看，并明确标注"可能是旧的"。
     */
    private val lastFrames = ConcurrentHashMap<String, ByteArray>()
    private val captureLocks = ConcurrentHashMap<String, Any>()
    private val closed = AtomicBoolean(false)

    /**
     * 释放显示前的任务清理钩子：返回 false 表示屏上还有任务，不能安全释放。
     *
     * 直接 release 可能让系统把任务迁回默认屏，等于把虚拟屏里的应用顶到用户面前；
     * 由上层（持有 Root 的能力）先移除任务，再释放显示。
     */
    var taskCleaner: ((Int) -> Boolean)? = null

    /** 当前默认（主）屏 id，用于结果标注与防呆。 */
    val defaultDisplayId: Int
        get() = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.displayId ?: 0

    fun list(): List<Session> {
        sweepIdle()
        return sessions.values.sortedBy { it.createdAtMs }
    }

    /** 取会话（launch 等操作用）：取到即视为「有人在用」，刷新最后使用时间。 */
    fun find(id: String): Session? =
        sessions[id]?.also { it.lastUsedAtMs = System.currentTimeMillis() }

    /** 只读取会话，不刷新「最后使用时间」：list 这类只读场景不能用 find，否则会重算空闲时间。 */
    fun peek(id: String): Session? = sessions[id]

    fun findByDisplayId(displayId: Int): Session? =
        sessions.values.firstOrNull { it.displayId == displayId }

    /**
     * 创建虚拟屏。
     * @param width 宽度 px，1080 左右即可
     * @param height 高度 px
     * @param densityDpi 密度；不传则按主屏密度缩放（保证控件尺寸接近真实观感）
     * @param purpose 用途说明，写入日志便于回溯
     * @param ownerConversationId 建屏的会话 id：跨运行也要能回答「这块屏是哪个对话的」，
     *   否则新运行看不到旧会话，释放时只能静默假成功
     * @param ownerRunId 建屏的运行 id：没有会话（语音 / 一次性入口）时用它区分归属
     */
    fun create(
        width: Int,
        height: Int,
        densityDpi: Int? = null,
        purpose: String = "",
        ownerConversationId: String = "",
        ownerRunId: String = "",
    ): Session {
        require(width in 240..3840 && height in 240..3840) {
            "虚拟屏尺寸需在 240..3840 px 之间，收到 ${width}x$height"
        }
        val manager = displayManager ?: error("DISPLAY_SERVICE 不可用，无法创建虚拟屏")
        // 先回收空闲屏，再看还有没有余量 —— 顺序反过来会把「该收的没收」算成「满了」。
        sweepIdle()
        when (
            val admission = VirtualDisplayBudget.admit(
                existing = sessions.values.map {
                    VirtualDisplayBudget.Existing(it.id, it.width, it.height, it.lastUsedAtMs)
                },
                width = width,
                height = height,
            )
        ) {
            is VirtualDisplayBudget.Admission.Rejected ->
                throw RejectedException(admission.code, admission.message)

            VirtualDisplayBudget.Admission.Allowed -> Unit
        }
        val dpi = densityDpi?.coerceIn(120, 640)
            ?: defaultDensityFor(width, height)
        val id = "vd${System.currentTimeMillis().toString(36)}"
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        val surface = reader.surface
        val thread = HandlerThread("eta-vd-$id").apply { start() }
        val flags = buildFlags()
        // createVirtualDisplay 的名字会出现在 dumpsys display 里，便于人工核对。
        val display = manager.createVirtualDisplay(
            "eta-virtual-$id",
            width,
            height,
            dpi,
            surface,
            flags,
        )
        val session = Session(
            id = id,
            displayId = display.display.displayId,
            width = width,
            height = height,
            densityDpi = dpi,
            createdAtMs = System.currentTimeMillis(),
            purpose = purpose,
            ownerConversationId = ownerConversationId,
            ownerRunId = ownerRunId,
        )
        sessions[id] = session
        displays[id] = display
        readers[id] = reader
        surfaces[id] = surface
        callbackThreads[id] = thread
        logger.debug {
            "Agent virtual display action=create outcome=completed id=$id " +
                "displayId=${session.displayId} size=${width}x$height dpi=$dpi flags=$flags purpose=$purpose"
        }
        // 面板在 Eta 自己的界面里，跟随应用窗口；不做全局悬浮窗（会独立占屏）。
        VirtualDisplayHub.register(this, session)
        return session
    }

    /** 释放单个虚拟屏；不存在时静默成功（幂等，便于收尾脚本重复调用）。 */
    fun release(id: String): Boolean {
        val session = sessions[id] ?: return true
        // 先清任务，再销毁显示：否则系统可能把任务迁回默认屏（用户主屏）。
        val cleaned = taskCleaner?.invoke(session.displayId) ?: true
        if (!cleaned) {
            logger.warn(
                "Agent virtual display action=release outcome=blocked id=$id " +
                    "displayId=${session.displayId} reason=tasks-remain",
            )
            return false
        }
        sessions.remove(id) ?: return true
        displays.remove(id)?.release()
        surfaces.remove(id)?.release()
        readers.remove(id)?.close()
        callbackThreads.remove(id)?.let { thread ->
            thread.quitSafely()
        }
        lastObservedPackage.remove(id)
        lastFrames.remove(id)
        captureLocks.remove(id)
        VirtualDisplayHub.unregister(id)
        logger.debug {
            "Agent virtual display action=release outcome=completed id=$id " +
                "displayId=${session.displayId} ageSeconds=${session.ageSeconds}"
        }
        return true
    }

    fun releaseAll(): Int {
        var count = 0
        sessions.keys.toList().forEach { id -> if (release(id)) count++ }
        return count
    }

    /**
     * 截取虚拟屏内容为 PNG 字节。
     * 只读当前最新一帧；没有新帧时返回 null（调用方应提示"尚未渲染"而不是报错）。
     */
    /** 一帧截图。[cached] 为 true 表示屏幕静止、没有新帧，返回的是上一帧。 */
    data class Capture(val png: ByteArray, val cached: Boolean)

    /**
     * 已有缓存帧时等待新帧的上限。
     *
     * 取 250ms：普通界面切换在这个窗口内基本都会产出一帧；超过它就说明屏幕静止，
     * 继续等只是在浪费调用方的时间（1.2 秒的等待换来的是同一张图）。
     */
    private val cachedFrameWaitMs = 250L

    fun capture(id: String, timeoutMs: Long = 1_200): Capture? {
        // 预览窗与 Agent 截图都可能取帧；ImageReader 只有一条消费链，
        // 不加锁时两边会互相抢走对方刚取到的帧（表现为随机 VIRTUAL_FRAME_NOT_READY）。
        val lock = captureLocks.getOrPut(id) { Any() }
        return synchronized(lock) {
            // 屏幕静止时 ImageReader 不会有新帧，而 captureFresh 会一直重试到超时：
            // 也就是说一张完全没变化的截图，以前必然要等满 timeout（默认 1.2 秒）才返回缓存帧。
            // 已经有上一帧时只等一个「新帧可能出现的窗口」，到点立刻回缓存帧。
            val hasCachedFrame = lastFrames.containsKey(id)
            val budget = if (hasCachedFrame) minOf(timeoutMs, cachedFrameWaitMs) else timeoutMs
            val fresh = captureFresh(id, budget)
            if (fresh != null) {
                lastFrames[id] = fresh
                Capture(fresh, cached = false)
            } else {
                val cached = lastFrames[id]
                if (cached == null) {
                    null
                } else {
                    logger.debug { "Agent virtual display action=capture outcome=stale id=$id (no new frame)" }
                    Capture(cached, cached = true)
                }
            }
        }
    }

    fun capturePng(id: String, timeoutMs: Long = 1_200): ByteArray? = capture(id, timeoutMs)?.png

    private fun captureFresh(id: String, timeoutMs: Long): ByteArray? {
        sessions[id]?.lastUsedAtMs = System.currentTimeMillis()
        val reader = readers[id] ?: return null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: run {
            // 有些实现上首帧到达前 acquireLatestImage 返回 null；短暂等待后再取一次。
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val acquired = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (acquired != null) return renderPng(acquired, reader.width, reader.height)
                SystemClock.sleep(60)
            }
            null
        }
        return image?.let { renderPng(it, reader.width, reader.height) }
    }

    private fun renderPng(
        image: android.media.Image,
        width: Int,
        height: Int,
    ): ByteArray? {
        val planes = image.planes
        if (planes.isEmpty()) {
            image.close()
            return null
        }
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val rowBytes = plane.pixelStride * width
        val buffer = plane.buffer
        val bitmap = android.graphics.Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        val cropped = if (rowPadding == 0) {
            bitmap
        } else {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                if (it != bitmap) bitmap.recycle()
            }
        }
        val out = ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        cropped.recycle()
        return bytes
    }

    /** 记录在虚拟屏上看到的前台包名，供 Agent 判断应用是否真的起来了。 */
    fun noteForegroundPackage(id: String, packageName: String) {
        if (packageName.isNotBlank()) {
            lastObservedPackage[id] = packageName
            sessions[id]?.lastUsedAtMs = System.currentTimeMillis()
        }
    }

    fun foregroundPackage(id: String): String = lastObservedPackage[id].orEmpty()

    /** 释放空闲超时的会话，返回释放数量。list/create 前会自动调用一次。 */
    fun sweepIdle(): Int {
        if (sessions.isEmpty()) return 0
        val expired = VirtualDisplayBudget.sweep(
            existing = sessions.values.map {
                VirtualDisplayBudget.Existing(it.id, it.width, it.height, it.lastUsedAtMs)
            },
            nowMs = System.currentTimeMillis(),
        )
        var releasedCount = 0
        expired.forEach { id ->
            val session = sessions[id] ?: return@forEach
            logger.info(
                "Agent virtual display action=recycle reason=idle id=$id displayId=${session.displayId} " +
                    "idleSeconds=${session.idleSeconds}",
            )
            if (release(id)) {
                releasedCount++
            } else {
                // release(id) 返回 false 表示「任务没清干净，屏被故意留着」（不能把任务迁回主屏）。
                // 原来这里直接忽略返回值并按 expired.size 报告「已回收」：回收失败被记成成功，
                // 屏其实还在、下一轮又被 sweep 一次，日志里也看不出异常。
                logger.warn(
                    "Agent virtual display action=recycle outcome=skipped id=$id " +
                        "displayId=${session.displayId} reason=tasks-remain",
                )
            }
        }
        return releasedCount
    }

    /** 坐标/滑动这类输入走系统命令，控制器只能在此显式记一次「用过了」。 */
    fun touch(id: String) {
        sessions[id]?.lastUsedAtMs = System.currentTimeMillis()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseAll()
    }

    /**
     * 仅 debug 包：允许用系统设置覆盖建屏 flags。
     *
     * 存在的理由（不是为了"留个后门"，是为了能验证）：
     * 本机虚拟屏的 `DisplayInfo.canHostTasks=false`，WindowManager 因此会在任务变化后
     * 把应用迁回主屏（真机实测）。可能翻转它的候选 flag 是 OWN_DISPLAY_GROUP(0x800)
     * 与 DEVICE_DISPLAY_GROUP(0x8000)，但**哪一个有效、有没有副作用**必须实测 ——
     * 而每改一次常量就要重装一次 App，代价太高。
     *
     * 用法（debug 包）：
     *   adb shell settings put global eta_vd_extra_flags 8000
     * 然后新建一块虚拟屏，用 dumpsys display 看 canHostTasks。
     * 正式包（非 debuggable）读不到这个设置，行为与常量完全一致。
     */
    private fun debugFlagOverride(): Int {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return 0
        }
        return runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                "eta_vd_extra_flags",
            )?.trim()?.toIntOrNull(16) ?: 0
        }.getOrDefault(0)
    }

    private fun buildFlags(): Int {
        // 常量值来自本机（Android 16 / SM8750）framework.jar 的 dexdump 实测，
        // 不是从文档抄的；这两个都是 @hide / BLOCKED，公开 SDK 里取不到。
        // VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14 = 16384
        //   焦点只给虚拟屏，不会把主屏当前焦点抢走（"不抢主屏"的核心）。
        // VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13 = 8192
        //   注入的触摸不显示反馈圆点，用户主屏上完全无感知。
        val extra = debugFlagOverride()
        if (extra != 0) {
            logger.info(
                "Agent virtual display action=create flags_override extra=0x" +
                    Integer.toHexString(extra),
            )
        }
        // PUBLIC 是让无障碍系统跟踪这块屏的关键；OWN_CONTENT_ONLY 是创建 public
        // 虚拟屏时的硬性要求（否则系统按「屏幕共享」要求 ADD_MIRROR_DISPLAY 权限）。
        // 实测：PUBLIC|OWN_CONTENT_ONLY = 9，创建成功且 DisplayInfo 不再带 FLAG_PRIVATE。
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_OWN_FOCUS or FLAG_TOUCH_FEEDBACK_DISABLED or extra
    }

    private fun defaultDensityFor(width: Int, height: Int): Int {
        val metrics = context.resources.displayMetrics
        val reference = if (width > height) {
            minOf(metrics.widthPixels, metrics.heightPixels)
        } else {
            maxOf(metrics.widthPixels, metrics.heightPixels)
        }.toFloat()
        if (reference <= 0f) return DisplayMetrics.DENSITY_DEFAULT
        val longEdge = maxOf(width, height).toFloat()
        val scale = longEdge / reference
        return (metrics.densityDpi * scale).toInt().coerceIn(120, 640)
    }

    private companion object {
        /**
         * 见 [buildFlags] 的取值说明：这两个都是 @hide 常量，公开 SDK 编译不到，
         * 数值来自本机 framework dex 实测，跨大版本不会变（AOSP 自加入起未改过）。
         */
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    }
}
