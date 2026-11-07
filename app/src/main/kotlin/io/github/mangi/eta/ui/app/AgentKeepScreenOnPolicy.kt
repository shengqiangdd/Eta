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

    /**
     * 续期时该不该真的去 acquire。
     *
     * 三个条件缺一不可，就是三个已经被真机验证过的缺陷：
     * 1. `wantedHeld` —— 决策层还希望保屏。**旧实现缺这一条**：它只看锁对象自己，
     *    于是用户点停止的瞬间，正在跑的续期协程会把刚释放的锁重新抢回来，
     *    此后不再有状态变化，锁挂到 12 小时上限。
     * 2. `isHeld` —— 锁确实还持着。没持有时续期一次 acquire 就是凭空多拿一把。
     * 3. 有锁对象。
     *
     * 单独提出来是因为这是纯函数：JVM 单测能直接盖住，不必去抢真的系统唤醒锁。
     */
    fun shouldRenew(wantedHeld: Boolean, hasLock: Boolean, isHeld: Boolean): Boolean =
        wantedHeld && hasLock && isHeld

    /**
     * 自愈时该不该把本地镜像改回「已结束」。
     *
     * 三个条件：
     * 1. 服务端**确实**回答了（`null` = 连不上/超时，不采信）；
     * 2. 服务端说一条 run 都没有了；
     * 3. 本地还有会话挂着 `isStreaming`。
     *
     * 只靠本地镜像永远自愈不了：事件流断了就再没有事件会来改它，
     * 于是锁一直续期。真机实测会话结束后 `Eta:agent-keep-screen` 仍在 `dumpsys power` 里。
     */
    fun shouldReconcileStreaming(serverReportsRunning: Boolean?, localStreamingCount: Int): Boolean =
        serverReportsRunning == false && localStreamingCount > 0
}
