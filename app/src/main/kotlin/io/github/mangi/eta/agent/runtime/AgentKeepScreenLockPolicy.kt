package io.github.mangi.eta.agent.runtime

/**
 * 保屏锁的决策点，与 Android 运行时解耦以便 JVM 单测直接覆盖。
 *
 * 这里同时钉死两个真机缺陷，它们方向相反、必须一起满足：
 *
 * **一、任务跑着却熄屏**（保屏失效）
 *
 * 旧实现把锁与续期协程放在 `AgentAppState`（Activity 作用域的 ViewModel），
 * 用 `conversationsById[*].isStreaming` 这个**本地镜像**决策。运行态不入库，
 * Activity 被系统回收后重建时 `isStreaming` 恢复为 false，于是算出「没有任务在跑」
 * 并主动释放锁；前台服务其实还活着、任务仍在推进。
 *
 * 所以「是否保屏」不能看 UI 镜像，只能看**前台服务自己登记的活跃任务**。
 *
 * **二、会话结束后整夜亮屏**（无谓耗电）
 *
 * 反过来，只要服务在前台就持锁也是错的：租约清空到 `stopSelf()` 之间、
 * 以及服务因其它租约（终端命令等）继续存活时，已经没有 Agent 任务在推进了。
 * 用户反馈「一晚上屏幕开着没电」正是这一类。暂停同理——被暂停的 run 不会推进，
 * 亮着屏只是烧电。
 *
 * 因此三个条件必须同时成立：服务活着、确实有未暂停的活跃任务、用户开着开关。
 * 其中「未暂停」只认**服务端回传的状态**，不认 UI 镜像。
 */
internal object AgentKeepScreenLockPolicy {
    fun shouldHold(
        foregroundActive: Boolean,
        hasRunningTask: Boolean,
        paused: Boolean,
        enabled: Boolean,
    ): Boolean = foregroundActive && hasRunningTask && !paused && enabled
}
