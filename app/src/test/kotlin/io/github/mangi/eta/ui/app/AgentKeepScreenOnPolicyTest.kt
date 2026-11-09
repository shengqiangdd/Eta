package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 侧只剩「本地镜像何时该自愈」这一个决策点。
 *
 * 保屏本身的判断已统一到
 * `io.github.mangi.eta.agent.runtime.AgentKeepScreenLockPolicy`（见那边的测试）：
 * 前台窗口 flag 与跨应用唤醒锁共用同一个判断，这里不再重复一套。
 */
class AgentKeepScreenOnPolicyTest {
    /**
     * 真机报的缺陷：会话结束后屏幕一直亮。
     *
     * 根因有两层：
     * 1. 自愈只重算本地镜像，而镜像本身没被纠正（事件流断了就不会再有事件改它）；
     * 2. 窗口的 KEEP_SCREEN_ON flag 在退到后台后仍然挂着（真机 7 小时 36 分）。
     *
     * 第 1 层靠服务端权威状态纠正；这里锁住那个纯函数。
     */
    @Test fun serverSaysIdleReconcilesStaleStreaming() {
        assertTrue(AgentKeepScreenOnPolicy.shouldReconcileStreaming(serverReportsRunning = false, localStreamingCount = 1))
    }

    @Test fun unknownServerStateNeverReconciles() {
        // null = 连不上/超时：不能当成「没跑」，否则会在任务中途把锁放掉（屏幕熄）。
        assertFalse(AgentKeepScreenOnPolicy.shouldReconcileStreaming(serverReportsRunning = null, localStreamingCount = 3))
    }

    @Test fun serverStillRunningNeverReconciles() {
        assertFalse(AgentKeepScreenOnPolicy.shouldReconcileStreaming(serverReportsRunning = true, localStreamingCount = 2))
    }

    @Test fun nothingStreamingNeedsNoReconcile() {
        assertFalse(AgentKeepScreenOnPolicy.shouldReconcileStreaming(serverReportsRunning = false, localStreamingCount = 0))
    }
}
