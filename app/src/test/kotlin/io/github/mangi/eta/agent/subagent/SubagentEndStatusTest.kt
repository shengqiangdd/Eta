package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 子代理结束时台账里记什么状态。
 *
 * 这几种结束方式对模型的含义完全不同：
 * - `stalled`：卡住了，任务太大 → **该拆小重派**
 * - `cancelled`：用户叫停或父 run 取消 → **不该重试**
 * - `failed`：子代理自己出错 → 看错误决定
 *
 * 最容易写错的一处：停滞看门狗通过 `childController.cancel()` 生效，所以停滞后
 * `cancelled` 也是 true。判定顺序反了就会把「卡住」记成「用户叫停」，
 * 模型于是再也不会去重试一个本来可以救回来的任务。
 */
class SubagentEndStatusTest {

    @Test
    fun stalledWinsEvenThoughTheWatchdogAlsoCancelsTheChild() {
        // 真实调用点：stalled != null 时 cancelled 必然也是 true。
        assertEquals(
            AgentTaskStatus.STALLED,
            SubagentExecutor.endStatusFor(steered = false, cancelled = true, stalled = true),
        )
    }

    @Test
    fun userSteeringIsCancelledNotFailed() {
        assertEquals(
            AgentTaskStatus.CANCELLED,
            SubagentExecutor.endStatusFor(steered = true, cancelled = true, stalled = false),
        )
    }

    @Test
    fun parentCancellationIsCancelledNotFailed() {
        assertEquals(
            AgentTaskStatus.CANCELLED,
            SubagentExecutor.endStatusFor(steered = false, cancelled = true, stalled = false),
        )
    }

    @Test
    fun plainErrorIsFailed() {
        assertEquals(
            AgentTaskStatus.FAILED,
            SubagentExecutor.endStatusFor(steered = false, cancelled = false, stalled = false),
        )
    }
}
