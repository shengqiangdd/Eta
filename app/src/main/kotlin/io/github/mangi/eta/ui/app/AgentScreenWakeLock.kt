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

    /**
     * 把锁切到 [held] 指定的状态。幂等：重复调用不会多持一把。
     *
     * 加 [wantedHeld] 记录：续期协程是异步的，而 [setHeld] 是决策直接调用的。
     * 记住「上一次决策想要什么」，[renew] 就能自查「现在不应该保屏了」并主动让位——
     * 不靠定时器碰运气。这也是旧实现最危险的地方：它只看向锁对象自己，
     * 完全不知道“决策层早就说不用保屏了”。
     */
    fun setHeld(held: Boolean) {
        wantedHeld = held
        if (held) acquire() else release()
    }

    /** 上一次决策希望的状态；[renew] 用它防止在“已决定释放”后又把锁抢回来。 */
    private var wantedHeld = false

    /**
     * 续期。
     *
     * [MAX_HOLD_MS] 只是防泄漏兜底：超时后系统会自己释放锁。长任务期间状态不再变化，
     * 也就没有新的 [setHeld] 调用，屏幕会悄悄恢复成按系统超时熄灭 —— 所以运行期间要定期
     * 重新 acquire 一次，把上限往后推。
     *
     * **不得在锁未持有时重新 acquire**（旧实现就是这么写的，是个真缺陷）：
     * 续期协程与释放是两件事，用户停止 / 会话结束时 [setHeld] 会先释放锁并取消续期协程，
     * 但协程可能刚好已经跑进这里 —— 取消是协作式的，打断不了正在执行的 acquire。
     * 于是那一瞬间会把刚释放的锁重新抢回来，而此后不再有任何状态变化，
     * 锁就一直挂到 12 小时上限：**会话结束、屏幕还亮着**。
     * 续期只对“仍然持有的锁”有意义；没持有时交给 [setHeld] 重新决策。
     */
    fun renew() {
        // 决策层已经说不该保屏了，续期就只该让位。场景：用户点停止的那一刻
        // 续期协程刚好跑到这里，而 release + cancel 无法从中间打断它。
        val current = lock
        if (!AgentKeepScreenOnPolicy.shouldRenew(
                wantedHeld = wantedHeld,
                hasLock = current != null,
                isHeld = current?.isHeld == true,
            )
        ) {
            if (!wantedHeld) release()
            return
        }
        try {
            // 走到这里 current 必然非空（上面 hasLock 已经卡过）。
            current?.acquire(MAX_HOLD_MS)
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
