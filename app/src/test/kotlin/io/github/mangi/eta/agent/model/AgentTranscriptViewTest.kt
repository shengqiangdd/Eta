package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作细节档位（对齐 dsh 的 `transcriptView`）。
 *
 * 这类设置的失败方式都很安静：档位读错、组合表写错、或者「收不收」与「有没有折叠行」
 * 互相打架，界面照样能跑，只是每次看工作步骤都多一次点击或该看的细节看不到。
 * 所以默认值、旧值兼容、四档组合、以及两条判定（展开 / 有无折叠行）都用断言钉住。
 */
class AgentTranscriptViewTest {

    @Test
    fun defaultIsDetailed() {
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.DEFAULT)
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId(null))
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId(""))
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId("   "))
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId("expanded-v2"))
    }

    @Test
    fun knownIdsRoundTripAndKeepDshSpelling() {
        AgentTranscriptViewMode.values().forEach { mode ->
            assertEquals(mode, AgentTranscriptViewMode.fromId(mode.id))
            assertEquals(mode, AgentTranscriptViewMode.fromId(" ${mode.id} "))
        }
        // 与 dsh 同名同值，否则文档与实现会各说一套。
        assertEquals("compact", AgentTranscriptViewMode.COMPACT.id)
        assertEquals("standard", AgentTranscriptViewMode.STANDARD.id)
        assertEquals("detailed", AgentTranscriptViewMode.DETAILED.id)
        assertEquals("verbose", AgentTranscriptViewMode.VERBOSE.id)
    }

    @Test
    fun legacyTwoModeValuesReadAsDetailed() {
        // dsh 上一代只有两档，旧值「接受但不提供」：读作 detailed，而不是掉回默认之外。
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId("normal"))
        assertEquals(AgentTranscriptViewMode.DETAILED, AgentTranscriptViewMode.fromId("expanded"))
    }

    @Test
    fun policiesMatchTheDshTable() {
        val compact = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.COMPACT)
        assertTrue(compact.foldCompletedTurns)
        assertEquals(AgentStepGrouping.COLLAPSED, compact.stepGrouping)
        assertFalse(compact.liveProcessDetail)

        val standard = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.STANDARD)
        assertTrue(standard.foldCompletedTurns)
        assertEquals(AgentStepGrouping.COLLAPSED, standard.stepGrouping)
        assertTrue(standard.liveProcessDetail)

        val detailed = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.DETAILED)
        assertTrue(detailed.foldCompletedTurns)
        assertEquals(AgentStepGrouping.HISTORY, detailed.stepGrouping)
        assertTrue(detailed.liveProcessDetail)

        val verbose = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.VERBOSE)
        assertFalse(verbose.foldCompletedTurns)
        assertEquals(AgentStepGrouping.NONE, verbose.stepGrouping)
        assertFalse(verbose.liveProcessDetail)
    }

    @Test
    fun runningBlockAlwaysExpandsForEveryMode() {
        AgentTranscriptViewMode.values().forEach { mode ->
            AgentWorkCollapseTiming.values().forEach { timing ->
                assertTrue(
                    "$mode/$timing 运行中必须展开",
                    AgentTranscriptPresentation.autoExpand(
                        policy = AgentTranscriptViewPolicy.forMode(mode),
                        timing = timing,
                        active = true,
                        hasUserMessageAfter = false,
                    ),
                )
            }
        }
    }

    @Test
    fun completionFoldsFinishedTurnExceptInVerbose() {
        val finished = { mode: AgentTranscriptViewMode ->
            AgentTranscriptPresentation.autoExpand(
                policy = AgentTranscriptViewPolicy.forMode(mode),
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = false,
                hasUserMessageAfter = false,
            )
        }
        // completion 档下 compact/standard/detailed 结束即收。
        assertFalse(finished(AgentTranscriptViewMode.COMPACT))
        assertFalse(finished(AgentTranscriptViewMode.STANDARD))
        assertFalse(finished(AgentTranscriptViewMode.DETAILED))
        // verbose 的 foldCompletedTurns=false：结束的轮不收。
        assertTrue(finished(AgentTranscriptViewMode.VERBOSE))
    }

    @Test
    fun verboseKeepsFinishedTurnOpenUnderEveryTiming() {
        val verbose = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.VERBOSE)
        AgentWorkCollapseTiming.values().forEach { timing ->
            listOf(false, true).forEach { later ->
                assertTrue(
                    "$timing/$later：完整档不该折叠已完成的轮",
                    AgentTranscriptPresentation.autoExpand(
                        policy = verbose,
                        timing = timing,
                        active = false,
                        hasUserMessageAfter = later,
                    ),
                )
            }
        }
    }

    @Test
    fun nextInputKeepsFinishedTurnOpenOnlyUntilNextUserMessage() {
        val detailed = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.DETAILED)
        assertTrue(
            AgentTranscriptPresentation.autoExpand(
                policy = detailed,
                timing = AgentWorkCollapseTiming.NEXT_INPUT,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
        assertFalse(
            AgentTranscriptPresentation.autoExpand(
                policy = detailed,
                timing = AgentWorkCollapseTiming.NEXT_INPUT,
                active = false,
                hasUserMessageAfter = true,
            ),
        )
        // 换成 completion 就立刻收：两个开关互相独立，不能被对方覆盖。
        assertFalse(
            AgentTranscriptPresentation.autoExpand(
                policy = detailed,
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
    }

    @Test
    fun collapsedAlwaysKeepsTheFoldRow() {
        AgentTranscriptViewMode.values()
            .map(AgentTranscriptViewPolicy::forMode)
            .filter { it.stepGrouping == AgentStepGrouping.COLLAPSED }
            .forEach { policy ->
                AgentWorkCollapseTiming.values().forEach { timing ->
                    listOf(false, true).forEach { active ->
                        listOf(false, true).forEach { later ->
                            assertTrue(
                                "${policy.mode}/$timing",
                                AgentTranscriptPresentation.grouped(
                                    policy = policy,
                                    timing = timing,
                                    active = active,
                                    hasUserMessageAfter = later,
                                ),
                            )
                        }
                    }
                }
            }
    }

    @Test
    fun verboseNeverDrawsTheFoldRow() {
        val verbose = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.VERBOSE)
        AgentWorkCollapseTiming.values().forEach { timing ->
            listOf(false, true).forEach { active ->
                listOf(false, true).forEach { later ->
                    assertFalse(
                        AgentTranscriptPresentation.grouped(
                            policy = verbose,
                            timing = timing,
                            active = active,
                            hasUserMessageAfter = later,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun detailedOnlyFoldsTurnsThatAreAlreadyHistory() {        val detailed = AgentTranscriptViewPolicy.forMode(AgentTranscriptViewMode.DETAILED)
        // 当前轮永远平铺：刚跑起来就把步骤收进折叠行，等于让用户点一次才看得到进度。
        assertFalse(
            AgentTranscriptPresentation.grouped(
                policy = detailed,
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = true,
                hasUserMessageAfter = false,
            ),
        )
        // 已结束且用户已经发了下一句：历史轮收束。
        assertTrue(
            AgentTranscriptPresentation.grouped(
                policy = detailed,
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = false,
                hasUserMessageAfter = true,
            ),
        )
        // completion 下刚结束的轮也收束（与 dsh 的 detail+completion 一致）。
        assertTrue(
            AgentTranscriptPresentation.grouped(
                policy = detailed,
                timing = AgentWorkCollapseTiming.COMPLETION,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
        // next-input 下刚结束的轮保持平铺：它此刻是展开态，再画个「已收起」的头就是自相矛盾。
        assertFalse(
            AgentTranscriptPresentation.grouped(
                policy = detailed,
                timing = AgentWorkCollapseTiming.NEXT_INPUT,
                active = false,
                hasUserMessageAfter = false,
            ),
        )
    }

}
