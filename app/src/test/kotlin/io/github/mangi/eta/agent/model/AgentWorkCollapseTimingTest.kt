package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合结束后工作过程什么时候收（对齐 dsh 的 `collapseTiming`）。
 *
 * 这个设置的失败方式是「界面看着还行，但读的时候总要再点一次展开」：
 * 默认值错、未知值回退错、或者把正在跑的那一块也收起来，都不会报错，
 * 只会让每一步都多一次点击。所以默认值、回退、以及三条展开规则都用断言钉住。
 */
class AgentWorkCollapseTimingTest {

    @Test
    fun defaultIsCompletion() {
        assertEquals(AgentWorkCollapseTiming.COMPLETION, AgentWorkCollapseTiming.DEFAULT)
        assertEquals(AgentWorkCollapseTiming.COMPLETION, AgentWorkCollapseTiming.fromId(null))
        assertEquals(AgentWorkCollapseTiming.COMPLETION, AgentWorkCollapseTiming.fromId(""))
        assertEquals(AgentWorkCollapseTiming.COMPLETION, AgentWorkCollapseTiming.fromId("   "))
    }

    @Test
    fun knownIdsRoundTripAndKeepDshSpelling() {
        AgentWorkCollapseTiming.values().forEach { timing ->
            assertEquals(timing, AgentWorkCollapseTiming.fromId(timing.id))
            assertEquals(timing, AgentWorkCollapseTiming.fromId(" ${timing.id} "))
        }
        // 与 dsh 同名同值（连字符，不是下划线），否则文档与实现会各说一套。
        assertEquals("next-input", AgentWorkCollapseTiming.NEXT_INPUT.id)
        assertEquals("completion", AgentWorkCollapseTiming.COMPLETION.id)
    }

    @Test
    fun unknownValueFallsBackToCompletion() {
        assertEquals(
            AgentWorkCollapseTiming.COMPLETION,
            AgentWorkCollapseTiming.fromId("next_input"),
        )
        assertEquals(
            AgentWorkCollapseTiming.COMPLETION,
            AgentWorkCollapseTiming.fromId("NEXT-INPUT"),
        )
    }

    @Test
    fun completionModeCollapsesAsSoonAsTheTurnEnds() {
        assertTrue(
            AgentWorkCollapsePolicy.autoExpand(
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = true,
                hasUserMessageAfter = false,
            ),
        )
        assertFalse(
            AgentWorkCollapsePolicy.autoExpand(
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
    }

    @Test
    fun nextInputModeKeepsFinishedStepsOpenUntilTheUserSpeaksAgain() {
        assertTrue(
            AgentWorkCollapsePolicy.autoExpand(
                timing = AgentWorkCollapseTiming.NEXT_INPUT,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
        // 用户已经发出下一条消息：上一轮回到历史形态，和 dsh 的 next-input 一致。
        assertFalse(
            AgentWorkCollapsePolicy.autoExpand(
                timing = AgentWorkCollapseTiming.NEXT_INPUT,
                active = false,
                hasUserMessageAfter = true,
            ),
        )
    }

    @Test
    fun runningWorkProcessIsAlwaysOpenInBothModes() {
        AgentWorkCollapseTiming.values().forEach { timing ->
            assertTrue(
                "正在跑的这一块必须是展开的（$timing）",
                AgentWorkCollapsePolicy.autoExpand(
                    timing = timing,
                    active = true,
                    hasUserMessageAfter = false,
                ),
            )
        }
    }
}
