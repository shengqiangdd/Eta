package io.github.mangi.eta.agent.runtime

import android.app.ActivityManager
import androidx.core.content.edit
import android.app.ApplicationExitInfo
import android.content.Context

/** 上一次进程退出的性质。只区分用户真正需要知道的几种。 */
internal enum class AppExitCause {
    /** 与此前无异常：正常退出、用户主动停止、覆盖安装等。 */
    NONE,

    /** 系统内存不足回收（`REASON_LOW_MEMORY`）。 */
    LOW_MEMORY,

    /** 系统/ROM 主动清理，常见于厂商省电策略（`REASON_OTHER` / `REASON_SIGNALED`）。 */
    SYSTEM_KILL,

    /** 崩溃或 ANR。 */
    CRASH,

    /**
     * 启动超时：系统在首帧出现前就把进程杀了（`REASON_INITIALIZATION_FAILURE`）。
     *
     * 与崩溃分开：它不代表应用有 bug，最常见的原因是刚覆盖安装完、系统还在做 dex 优化，
     * 下一次启动通常就正常。文案要说清「再打开即可」，否则用户会以为安装把应用弄坏了。
     */
    START_TIMEOUT,
}

/**
 * 读取系统记录的「上一次进程为什么退出」。
 *
 * 为什么需要它：进程被系统杀掉时 **不会有任何回调**（SIGKILL 不走 onDestroy），
 * 界面重启后用户只看到「会话断了」，分不清是 Eta 的缺陷还是系统回收。
 * [ActivityManager.getHistoricalProcessExitReasons] 是唯一可靠的事后证据来源，
 * 这里把它翻译成一个可展示的结论。
 *
 * 只用「主进程 + 比上次已处理记录更新」的那一条：多进程应用里子进程退出极频繁，
 * 取错会把正常的 WebView 沙箱退出误报成「App 被杀」。
 */
internal object AppExitReporter {
    private const val PREFS = "eta-app-exit"
    private const val KEY_HANDLED_AT = "handled_at"

    fun detectAndMark(context: Context): AppExitCause {
        // getHistoricalProcessExitReasons 要 R(30)，而 minSdk 33 —— 不需要再判版本。
        val manager = context.getSystemService(ActivityManager::class.java) ?: return AppExitCause.NONE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val handledAt = prefs.getLong(KEY_HANDLED_AT, 0L)
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        }.getOrNull().orEmpty()
            .firstOrNull { it.timestamp > handledAt && it.processName == context.packageName }
            ?: return AppExitCause.NONE
        // 先落盘再返回：即使调用方当场崩溃也不会重复提示同一次退出。
        prefs.edit { putLong(KEY_HANDLED_AT, exit.timestamp) }
        return when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> AppExitCause.LOW_MEMORY
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_SIGNALED,
            -> AppExitCause.SYSTEM_KILL
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            -> AppExitCause.CRASH
            // 启动超时（10 秒内没完成首帧）。本机 canary 与正式包在覆盖安装后的首启都撞上过：
            // 87MB debug 包首次 dex 验证/类加载会超过这个上限，第二次启动只要 612ms。
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            -> AppExitCause.START_TIMEOUT
            else -> AppExitCause.NONE
        }
    }

    /** 单测用：清掉已处理游标，让下一次检测重新可见。 */
    internal fun resetForTest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }
}
