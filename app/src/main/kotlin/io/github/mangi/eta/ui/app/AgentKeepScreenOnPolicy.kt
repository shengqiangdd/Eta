package io.github.mangi.eta.ui.app

/**
 * UI 侧的保屏策略。
 *
 * 「让不让屏幕亮」的决策**不在这里**——它由
 * `io.github.mangi.eta.agent.runtime.AgentKeepScreenLockPolicy` 单点决定，
 * 前台窗口 flag 与跨应用唤醒锁共用同一个判断，避免两套判据各说一套话。
 *
 * 这里只保留与 UI 自身有关的一条：本地运行态镜像什么时候该自愈。
 */
internal object AgentKeepScreenOnPolicy {
    /**
     * 自愈时该不该把本地镜像改回「已结束」。
     *
     * 三个条件：
     * 1. 服务端**确实**回答了（`null` = 连不上/超时，不采信）；
     * 2. 服务端说一条 run 都没有了；
     * 3. 本地还有会话挂着 `isStreaming`。
     *
     * 只靠本地镜像永远自愈不了：事件流断了就再没有事件会来改它，
     * 会话结束后列表会一直显示「运行中」，设置页的保屏文案也跟着错。
     */
    fun shouldReconcileStreaming(serverReportsRunning: Boolean?, localStreamingCount: Int): Boolean =
        serverReportsRunning == false && localStreamingCount > 0
}
