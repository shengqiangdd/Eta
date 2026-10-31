package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentAttachRecoveryTest {

    @Test
    fun unavailableKeepsRunningState() {
        // 切后台放久一点再回来：服务还没绑定好，attach 超时。此时绝不能把「运行中」清掉。
        assertEquals(
            AgentAttachRecovery.Action.KEEP_RUNNING_AND_RETRY,
            AgentAttachRecovery.decide(AgentAttachRecovery.Outcome.UNAVAILABLE),
        )
    }

    @Test
    fun unavailableIsTheOnlyOutcomeThatKeepsRunningState() {
        // 防回归：只有「没问出来」才保留运行态；服务端明确说不在时必须收掉。
        val keeping = AgentAttachRecovery.Outcome.values()
            .filter { AgentAttachRecovery.decide(it) == AgentAttachRecovery.Action.KEEP_RUNNING_AND_RETRY }
        assertEquals(listOf(AgentAttachRecovery.Outcome.UNAVAILABLE), keeping)
    }

    @Test
    fun notActiveClearsAndReconciles() {
        assertEquals(
            AgentAttachRecovery.Action.CLEAR_AND_RECONCILE,
            AgentAttachRecovery.decide(AgentAttachRecovery.Outcome.NOT_ACTIVE),
        )
    }

    @Test
    fun completedAppliesResult() {
        assertEquals(
            AgentAttachRecovery.Action.APPLY_RESULT,
            AgentAttachRecovery.decide(AgentAttachRecovery.Outcome.COMPLETED),
        )
    }

    @Test
    fun clearingAndApplyingAreMutuallyExclusive() {
        AgentAttachRecovery.Outcome.values().forEach { outcome ->
            val action = AgentAttachRecovery.decide(outcome)
            if (outcome != AgentAttachRecovery.Outcome.NOT_ACTIVE) {
                assertNotEquals(
                    "只有 NOT_ACTIVE 能清运行态，收到 $outcome",
                    AgentAttachRecovery.Action.CLEAR_AND_RECONCILE,
                    action,
                )
            }
        }
    }
}
