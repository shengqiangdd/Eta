package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKeepScreenOnPolicyTest {
    @Test fun disabledSwitchNeverKeepsScreenOn() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = false, streaming = false))
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = false, streaming = true))
    }

    @Test fun enabledOnlyDuringStreaming() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = false))
        assertTrue(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true))
    }

    @Test fun pausedRunDoesNotKeepScreenOn() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true, paused = true))
        assertTrue(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true, paused = false))
    }

    /**
     * 真机报的缺陷：「会话运行中保持亮屏」没问题，但**会话结束后屏幕一直常亮**。
     *
     * 根因：renew() 在锁未持有时会 acquire()。用户停止 / 会话结束的那一刻，
     * setHeld(false) 已经释放了锁并取消了续期协程，但协程可能刚好跑进 renew —— 
     * 取消是协作式的，打断不了正在执行的 acquire。那一瞬间锁被重新抢回来，
     * 而此后不再有任何状态变化，于是挂到 12 小时上限。
     */
    @Test fun renewMustNotReacquireAfterDecisionToRelease() {
        // 决策层已说不保屏：即便锁对象还在，也绝不能续期（这正是旧实现的 bug）。
        assertFalse(
            AgentKeepScreenOnPolicy.shouldRenew(wantedHeld = false, hasLock = true, isHeld = true),
        )
    }

    @Test fun renewRequiresHeldLock() {
        // 没持锁时续期一次就是凭空多拿一把。
        assertFalse(
            AgentKeepScreenOnPolicy.shouldRenew(wantedHeld = true, hasLock = true, isHeld = false),
        )
        assertFalse(
            AgentKeepScreenOnPolicy.shouldRenew(wantedHeld = true, hasLock = false, isHeld = false),
        )
        // 三者同时成立才真续期。
        assertTrue(
            AgentKeepScreenOnPolicy.shouldRenew(wantedHeld = true, hasLock = true, isHeld = true),
        )
    }

    /** 不变量：只要「不该保屏」，任何组合下都不允许续期。 */
    @Test fun renewIsImpossibleWheneverKeepIsNotWanted() {
        listOf(false, true).forEach { hasLock ->
            listOf(false, true).forEach { isHeld ->
                assertFalse(
                    AgentKeepScreenOnPolicy.shouldRenew(
                        wantedHeld = false,
                        hasLock = hasLock,
                        isHeld = isHeld,
                    ),
                )
            }
        }
    }

    /** 与 shouldKeep 对齐：决策层说不用保屏，续期也必须同步拒绝。 */
    @Test fun renewAgreesWithShouldKeepDecision() {
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { streaming ->
                listOf(false, true).forEach { paused ->
                    val keep = AgentKeepScreenOnPolicy.shouldKeep(enabled, streaming, paused)
                    assertFalse(
                        "shouldKeep=$keep 时不应该续期",
                        !keep && AgentKeepScreenOnPolicy.shouldRenew(
                            wantedHeld = keep,
                            hasLock = true,
                            isHeld = true,
                        ),
                    )
                }
            }
        }
    }
}
