package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 事件触发的判定。
 *
 * 这里守的是「不会自己跑飞」：历史事件不重放、续过的不再续、链长上限到了就明确放弃，
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

    private fun tick(
        records: List<DetachedTask>,
        scopeId: String = "conv-a",
        handled: Set<String> = emptySet(),
        seenUntil: Long? = 500L,
        chain: Int = 0,
        nowMs: Long = 1_000L,
        maxChain: Int = AutoResumeMaxPerChain,
    ) = planAutomationTick(
        records = records,
        scopeId = scopeId,
        handled = handled,
        seenUntil = seenUntil,
        chain = chain,
        nowMs = nowMs,
        maxChain = maxChain,
    )

    @Test
    fun noRecordsMeansNothingToDo() {
        assertEquals(AutomationTick.Idle, tick(emptyList()))
    }

    /** 作用域第一次出现：只立水位线，不能把此前所有已结束任务当成新事件续一遍。 */
    @Test
    fun firstSightInitializesWatermarkWithoutReplay() {
        val plan = tick(listOf(task("job_old", 100L, 300L)), seenUntil = null, nowMs = 1_000L)
        assertEquals(AutomationTick.Initialize(1_000L), plan)
    }

    /** 只有运行中的任务时也要立水位线：随后结束的那条才会严格晚于它。 */
    @Test
    fun firstSightWithRunningTaskStillInitializes() {
        val plan = tick(listOf(task("job_running", 100L, null)), seenUntil = null)
        assertEquals(AutomationTick.Initialize(1_000L), plan)
    }

    @Test
    fun runningTaskIsNotACandidate() {
        assertEquals(AutomationTick.Idle, tick(listOf(task("job_a", 100L, null))))
    }

    @Test
    fun taskFinishedAtOrBeforeWatermarkIsIgnored() {
        // 水位线是 500：499 结束的属于历史，不能被自动续
        assertEquals(AutomationTick.Idle, tick(listOf(task("job_a", 100L, 499L))))
        // 恰好等于边界也不行：判据是「严格晚于」
        assertEquals(AutomationTick.Idle, tick(listOf(task("job_a", 100L, 500L))))
    }

    @Test
    fun handledTaskIsNotResumedAgain() {
        val plan = tick(
            records = listOf(task("job_a", 100L, 600L)),
            handled = setOf("job_a"),
        )
        assertEquals(AutomationTick.Idle, plan)
    }

    @Test
    fun oldestFinishedWins() {
        val plan = tick(
            records = listOf(
                task("job_late", 100L, 900L),
                task("job_early", 100L, 600L),
                task("job_mid", 100L, 700L),
            ),
        )
        assertEquals("job_early", (plan as AutomationTick.Dispatch).task.id)
    }

    /** 链满：明确放弃这一批，并给出最晚的结束时刻（调用方据此推进水位线）。 */
    @Test
    fun chainExhaustedDiscardsThroughLatestCandidate() {
        val plan = tick(
            records = listOf(task("job_a", 100L, 600L), task("job_b", 100L, 700L)),
            chain = AutoResumeMaxPerChain,
        )
        assertEquals(AutomationTick.Discard(through = 700L, skipped = 2), plan)
    }

    @Test
    fun overChainDoesNotGoNegative() {
        val plan = tick(
            records = listOf(task("job_a", 100L, 600L)),
            chain = AutoResumeMaxPerChain + 5,
        )
        assertEquals(AutomationTick.Discard(through = 600L, skipped = 1), plan)
    }

    @Test
    fun zeroCapNeverSends() {
        val plan = tick(
            records = listOf(task("job_a", 100L, 600L)),
            chain = 0,
            maxChain = 0,
        )
        assertEquals(AutomationTick.Discard(through = 600L, skipped = 1), plan)
    }

    /**
     * 别的会话派出的任务不算本会话的候选。
     *
     * 这条守的是真缺陷：记录原先没有归属，当前打开着的会话会把任何刚结束的任务当成
     * 「我的任务」自动续，用户没看着的那个会话的额度就这样被花掉。
     */
    @Test
    fun taskFromAnotherConversationIsIgnored() {
        val plan = tick(listOf(task("job_b", 100L, 600L, sessionScope = "conv-b")))
        assertEquals(AutomationTick.Idle, plan)
    }

    @Test
    fun recordsWithoutOwnerAreNeverAutoResumed() {
        val plan = tick(listOf(task("job_legacy", 100L, 600L, sessionScope = null)))
        assertEquals(AutomationTick.Idle, plan)
    }

    /** 没有会话键（语音 / 一次性入口的卡片）时，连水位线都不该立。 */
    @Test
    fun blankScopeNeverMatches() {
        val plan = tick(
            records = listOf(task("job_a", 100L, 600L, sessionScope = "")),
            scopeId = "",
            seenUntil = null,
        )
        assertEquals(AutomationTick.Idle, plan)
    }

    /** 混合场景：只在本会话的候选里挑最早的一条，别的会话不参与排序也不占位。 */
    @Test
    fun onlyOwnTasksTakePartInOrdering() {
        val plan = tick(
            records = listOf(
                task("job_other_early", 100L, 550L, sessionScope = "conv-b"),
                task("job_mine_late", 100L, 800L, sessionScope = "conv-a"),
                task("job_mine_early", 100L, 650L, sessionScope = "conv-a"),
            ),
        )
        assertEquals("job_mine_early", (plan as AutomationTick.Dispatch).task.id)
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
