package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.PowerManager
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 跨应用保屏。
 *
 * 窗口的 `FLAG_KEEP_SCREEN_ON` 只对**当前可见窗口**生效：任务跑着切到别的应用，
 * 本应用窗口不再可见，flag 就失效，屏幕照常按系统超时熄灭。要整机层面保屏，
 * 只能持有屏幕唤醒锁。
 *
 * 取舍：
 * - 用 `SCREEN_BRIGHT_WAKE_LOCK`（保持屏幕常亮），不用 `FULL_WAKE_LOCK`（会顺带点亮键盘灯）；
 * - 不申请 `ACQUIRE_CAUSES_WAKEUP`：用户按电源键主动息屏后不会被强行点亮；
 * - 12 小时上限是防泄漏兜底：调用方定期 [setHeld] 续期把上限往后推，
 *   调用方漏了也不会锁死一整天；
 * - 进程被杀时系统会清理唤醒锁，不会残留。
 */
internal class AgentScreenWakeLock(private val context: Context) {
    private var lock: PowerManager.WakeLock? = null

    /**
     * 把锁切到 [held] 指定的状态。
     *
     * [held] = true 时**每次调用都会重新 acquire**：`acquire(timeout)` 从此刻重新计时，
     * 前台服务靠这个定期续期，把 [MAX_HOLD_MS] 上限往后推。
     */
    fun setHeld(held: Boolean) {
        if (held) acquire() else release()
    }

    @Suppress("DEPRECATION") // 跨应用保屏只能靠屏幕唤醒锁；官方推荐的窗口 flag 只在前台生效。
    private fun acquire() {
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        try {
            val created = lock ?: manager
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG)
                .apply { setReferenceCounted(false) }
                .also { lock = it }
            /*
             * 已持有时也要再 acquire 一次：`acquire(timeout)` 会把超时从**现在**重新计时。
             * 旧实现在这里 `if (isHeld) return`，于是续期完全失效——锁只会在第一次 acquire
             * 后的 12 小时到期释放，长任务跑到上限就默默熄屏。
             */
            created.acquire(MAX_HOLD_MS)
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Keep-screen wake lock failed: ${failure.javaClass.simpleName}")
        }
    }

    fun release() {
        val current = lock ?: return
        try {
            if (current.isHeld) current.release()
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Keep-screen wake lock release failed: ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Eta:agent-keep-screen"
        const val MAX_HOLD_MS = 12L * 60L * 60L * 1000L
    }
}
