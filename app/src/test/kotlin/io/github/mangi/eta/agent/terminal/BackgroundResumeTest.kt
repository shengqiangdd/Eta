package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动续跑的判定。
 *
 * 这里守的是「不会自己跑飞」：旧任务不自动续、续过的不再续、次数上限到了就停，
 * 以及**只续本会话派出去的任务**（记录从第十批起带会话归属）。
 * 真正发消息那一步（启动 run + 本地化文案）本机测不了，只能在真机上看。
 */
class BackgroundResumeTest {

    private fun task(
        id: String,
        startedAt: Long,
        completedAt: Long?,
        sessionScope: String? = "conv-a",
    ) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = "sleep 1",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = startedAt,
        completedAt = completedAt,
        sessionScope = sessionScope,
    )

    @Test
    fun noRecordsMeansNoResume() {
        val plan = planAutoResume(emptyList(), "conv-a", emptySet(), 0L, alreadyResumed = 0)
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun runningTaskIsNotACandidate() {
        val plan = planAutoResume(listOf(task("job_a", 100L, null)), "conv-a", emptySet(), 0L, 0)
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun taskFinishedBeforeCardOpenedIsIgnored() {
        // 卡片打开的那一刻是 500：这条 499 结束的属于历史，不能被自动续
        val plan = planAutoResume(listOf(task("job_a", 100L, 499L)), "conv-a", emptySet(), 500L, 0)
        assertNull(plan.next)
        // 恰好等于边界也不行：判据是「严格晚于」
        val edge = planAutoResume(listOf(task("job_a", 100L, 500L)), "conv-a", emptySet(), 500L, 0)
        assertNull(edge.next)
    }

    @Test
    fun handledTaskIsNotResumedAgain() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L)),
            scopeId = "conv-a",
            handled = setOf("job_a"),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun oldestFinishedWins() {
        val plan = planAutoResume(
            records = listOf(
                task("job_late", 100L, 900L),
                task("job_early", 100L, 600L),
                task("job_mid", 100L, 700L),
            ),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertEquals("job_early", plan.next?.id)
        assertEquals(2, plan.skipped)
    }

    @Test
    fun budgetExhaustedStops() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L), task("job_b", 100L, 700L)),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = AutoResumeMaxPerSession,
        )
        assertNull(plan.next)
        assertEquals(2, plan.skipped)
    }

    @Test
    fun overBudgetDoesNotGoNegative() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L)),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = AutoResumeMaxPerSession + 5,
        )
        assertNull(plan.next)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun zeroCapNeverSends() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L)),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
            maxCount = 0,
        )
        assertNull(plan.next)
        assertEquals(1, plan.skipped)
    }

    /**
     * 别的会话派出的任务不算本会话的候选。
     *
     * 这条守的是真缺陷：记录原先没有归属，当前打开着的会话会把任何刚结束的任务当成
     * 「我的任务」自动续，用户没看着的那个会话的额度就这样被花掉。
     */
    @Test
    fun taskFromAnotherConversationIsIgnored() {
        val plan = planAutoResume(
            records = listOf(task("job_b", 100L, 600L, sessionScope = "conv-b")),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertNull(plan.next)
        // 不是候选，也不该计入「因预算没排上」
        assertEquals(0, plan.skipped)
    }

    @Test
    fun recordsWithoutOwnerAreNeverAutoResumed() {
        val plan = planAutoResume(
            records = listOf(task("job_legacy", 100L, 600L, sessionScope = null)),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    /** 没有会话键（语音 / 一次性入口的卡片）时，任何任务都不自动续。 */
    @Test
    fun blankScopeNeverMatches() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L, sessionScope = "")),
            scopeId = "",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertNull(plan.next)
    }

    /** 混合场景：只在本会话的候选里挑最早的一条，别的会话不参与排序也不占位。 */
    @Test
    fun onlyOwnTasksTakePartInOrdering() {
        val plan = planAutoResume(
            records = listOf(
                task("job_other_early", 100L, 550L, sessionScope = "conv-b"),
                task("job_mine_late", 100L, 800L, sessionScope = "conv-a"),
                task("job_mine_early", 100L, 650L, sessionScope = "conv-a"),
            ),
            scopeId = "conv-a",
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
        )
        assertEquals("job_mine_early", plan.next?.id)
        assertEquals(1, plan.skipped)
    }

    /**
     * 手动「继续」的显示条件。真机 2026-11-01 抓到的缺陷就是这里：
     * 自动路径已经按 `sessionScope` 过滤，卡片却只判「没续过」，于是别的会话
     * （和第九批之前没有归属的旧记录）会在当前会话里长出「继续」。
     */
    @Test
    fun manualResumeOnlyForOwnFinishedTask() {
        val mine = task("job_mine", 100L, 600L, sessionScope = "conv-a")
        assertEquals(true, canOfferManualResume(mine, "conv-a", true, true, emptyList()))
    }

    @Test
    fun manualResumeHiddenForOtherSessionTask() {
        val other = task("job_other", 100L, 600L, sessionScope = "conv-b")
        assertEquals(false, canOfferManualResume(other, "conv-a", true, true, emptyList()))
    }

    /** 第九批之前的记录没有 `session_scope`：卡片里也不该冒出「继续」。 */
    @Test
    fun manualResumeHiddenForLegacyRecordWithoutOwner() {
        val legacy = task("job_legacy", 100L, 600L, sessionScope = null)
        assertEquals(false, canOfferManualResume(legacy, "conv-a", true, true, emptyList()))
    }

    @Test
    fun manualResumeHiddenWhenAlreadyResumedOrDisabled() {
        val mine = task("job_mine", 100L, 600L, sessionScope = "conv-a")
        // 本作用域已续过（手动或自动都记在同一个集合里）
        assertEquals(false, canOfferManualResume(mine, "conv-a", true, true, listOf("job_mine")))
        // 设置里的手动开关关掉
        assertEquals(false, canOfferManualResume(mine, "conv-a", false, true, emptyList()))
        // 会话正在跑，发不出去
        assertEquals(false, canOfferManualResume(mine, "conv-a", true, false, emptyList()))
        // 没有会话键（语音 / 一次性入口）
        assertEquals(false, canOfferManualResume(mine, "", true, true, emptyList()))
    }
}
