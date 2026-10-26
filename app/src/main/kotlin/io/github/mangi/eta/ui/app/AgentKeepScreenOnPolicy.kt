package io.github.mangi.eta.ui.app

/**
 * 屏幕唤醒只由「用户开关」「是否有会话在运行（全局，不限于当前选中）」「是否被暂停」共同决定。
 *
 * 单独拆出来是为了把边界钉死：关着开关绝不影响续航；运行结束立即释放；
 * 暂停时也释放——用户在等一个不会动的进度没有意义，不应该靠常亮烧电；
 * 前台窗口用 FLAG_KEEP_SCREEN_ON，切到其他应用后由屏幕唤醒锁（AgentScreenWakeLock）继续保屏；
 * 两者共用同一判断，条件不成立时立即释放。
 */
internal object AgentKeepScreenOnPolicy {
    fun shouldKeep(enabled: Boolean, streaming: Boolean, paused: Boolean = false): Boolean =
        enabled && streaming && !paused
}
