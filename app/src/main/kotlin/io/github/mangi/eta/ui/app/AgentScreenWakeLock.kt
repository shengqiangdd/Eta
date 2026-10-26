package io.github.mangi.eta.ui.app

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
 * - 12 小时上限是防泄漏兜底：条件成立期间不续期，超时后系统自动释放，
 *   避免逻辑漏洞把屏幕锁死一整天；正常任务远短于这个上限；
 * - 进程被杀时系统会清理唤醒锁，不会残留。
 */
internal class AgentScreenWakeLock(private val context: Context) {
    private var lock: PowerManager.WakeLock? = null

    fun setHeld(held: Boolean) {
        if (held) acquire() else release()
    }

    /**
     * 续期。
     *
     * [MAX_HOLD_MS] 只是防泄漏兜底：超时后系统会自己释放锁。长任务期间状态不再变化，
     * 也就没有新的 [setHeld] 调用，屏幕会悄悄恢复成按系统超时熄灭 —— 所以运行期间要定期
     * 重新 acquire 一次，把上限往后推。
     */
    fun renew() {
        val current = lock
        if (current == null || !current.isHeld) {
            acquire()
            return
        }
        try {
            current.acquire(MAX_HOLD_MS)
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Keep-screen wake lock renew failed: ${failure.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION") // 跨应用保屏只能靠屏幕唤醒锁；官方推荐的窗口 flag 只在前台生效。
    private fun acquire() {
        if (lock?.isHeld == true) return
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        try {
            val created = lock ?: manager
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG)
                .apply { setReferenceCounted(false) }
                .also { lock = it }
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
