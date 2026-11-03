package io.github.mangi.eta.agent.device

import android.annotation.SuppressLint
import android.os.IBinder
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏点击与主屏输入法的隔离策略。
 *
 * 首选隔离点是**每块虚拟屏的 IME policy = HIDE(2)**：系统不会为这块屏建立
 * IME 连接，也不会把输入请求回退到主屏。`EtaSilentImeService` 的全局默认
 * 输入法切换只保留给「拿不到 per-display policy」的旧设备兜底，不再是正常路径。
 *
 * 下面两个边界仍然保留：
 * - 主屏已经在显示键盘时，不再切 IME / 不再执行虚拟点击（用户正在输入，不能抢）；
 * - 点击成功后保留一小段静默窗口，晚到的 showSoftInput 也不会落到主屏。
 */
internal object VirtualDisplayImePolicy {
    /** `WindowManager.DISPLAY_IME_POLICY_HIDE` 的数值；系统会彻底禁止该屏连接 IME。 */
    const val IME_POLICY_HIDE = 2

    /** 不依赖 Android Context 的纯判断，方便单测与 root 桥复用。 */
    fun isHidePolicy(policy: Int?): Boolean = policy == IME_POLICY_HIDE

    /**
     * Hook 成功设置 policy 后写入 `Settings.Global` 的确认键。
     *
     * 为什么不只看 Hook 是否存在：Hook 可用不等于 policy 一定设置成功；没有确认键时
     * App 仍会走静默 IME 兜底，避免「以为隔离了、结果键盘弹到主屏」。
     */
    fun imePolicySettingKey(displayId: Int): String = "eta_vd_ime_policy_$displayId"

    /**
     * 以调用者身份设置某块显示屏的 IME policy。
     *
     * 普通 App 没有 `INTERNAL_SYSTEM_WINDOW`，调用会抛 SecurityException 并返回 false；
     * 只有 system_server Hook 或 UID 1000 的 root 桥接进程能成功。调用方必须检查返回值，
     * 不能把「尝试过」当成「已经隔离」。
     */
    @SuppressLint("PrivateApi")
    fun setDisplayImePolicyViaBinder(displayId: Int, policy: Int = IME_POLICY_HIDE): Boolean =
        runCatching {
            val windowManager = windowManagerService() ?: return@runCatching false
            windowManager.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(windowManager, displayId, policy)
            true
        }.getOrDefault(false)

    /**
     * 读取某块显示屏的 IME policy。与 set 一样需要系统权限；普通 App 返回 null。
     * 仅供 system_server Hook / root 桥接诊断使用，不能据此在 App 内假装已隔离。
     */
    @SuppressLint("PrivateApi")
    fun getDisplayImePolicyViaBinder(displayId: Int): Int? = runCatching {
        val windowManager = windowManagerService() ?: return@runCatching null
        windowManager.javaClass.getMethod(
            "getDisplayImePolicy",
            Int::class.javaPrimitiveType,
        ).invoke(windowManager, displayId) as? Int
    }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun windowManagerService(): Any? = runCatching {
        // IWindowManager / ServiceManager 都是 @hide；Hook 进程与 root 桥接进程可用，
        // 普通 App 即使能拿到代理，也会在 WindowManagerService 的权限检查处被拒。
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/", "Landroid/os/")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as? IBinder
            ?: return@runCatching null
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    /** 主屏 IME 可见时最多等待它收起的时间；超时返回 MAIN_INPUT_ACTIVE。 */
    const val MAIN_INPUT_WAIT_MS = 1_200L
    const val MAIN_INPUT_POLL_MS = 200L

    /** 点击后继续静默的时间，覆盖输入框动画/延迟 showSoftInput。 */
    const val SILENT_GRACE_MS = 800L

    fun focusedDisplayId(inputDump: String): Int? =
        Regex("FocusedDisplayId:\\s*(-?\\d+)")
            .find(inputDump)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    /**
     * 某个虚拟屏上是否真的有输入会话（输入框已连接 IME）。
     *
     * ClientState 的 header 之后到下一个 ClientState 之前都属于该客户端；
     * `curSession=SessionState` / `sessionRequested=true` 表示该屏正在等输入法。
     */
    fun virtualDisplayHasInputSession(inputMethodDump: String, displayId: Int): Boolean {
        if (inputMethodDump.isBlank()) return false
        val marker = "mSelfReportedDisplayId=$displayId"
        return inputMethodDump.split("ClientState{").drop(1).any { part ->
            val header = part.substringBefore('}')
            if (!header.contains(marker)) return@any false
            val body = part.substringAfter('}', "")
            body.contains("sessionRequested=true") || body.contains("curSession=SessionState")
        }
    }

    fun mainImeVisible(inputMethodDump: String): Boolean {
        if (inputMethodDump.isBlank()) return false
        val shown = Regex("mInputShown=(true|false)")
            .findAll(inputMethodDump)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toBooleanStrictOrNull()
        if (shown == true) {
            // 显示时还要看输入法客户端属于哪个 display。
            // 实测死锁：虚拟屏自己的输入框聚焦后 mInputShown=true（mSelfReportedDisplayId=80），
            // 旧逻辑把它当「主屏正在输入」，于是虚拟屏后续每一次点击都被 MAIN_INPUT_ACTIVE 挡掉。
            val displayId = Regex("mSelfReportedDisplayId=(\\d+)")
                .findAll(inputMethodDump)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            return displayId == null || displayId == Display.DEFAULT_DISPLAY
        }
        val visibility = Regex("mImeWindowVis=(0x[0-9a-fA-F]+)")
            .findAll(inputMethodDump)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.removePrefix("0x")
            ?.toLongOrNull(16)
        return visibility != null && visibility != 0L
    }
}
