package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        description: String = "",
        command: String = "sleep 1",
    ) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = command,
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = startedAt,
        completedAt = completedAt,
        description = description,
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

    /** 判定的诊断文本：真机日志就靠它回答「这一轮为什么发 / 为什么没发」。 */
    @Test
    fun planLogLineMentionsDecisionAndScope() {
        assertEquals("idle scope=conv-a", AutomationTick.Idle.logLine("conv-a"))
        assertEquals(
            "initialize scope=conv-a at=1000",
            AutomationTick.Initialize(1_000L).logLine("conv-a"),
        )
        assertEquals(
            "dispatch scope=conv-a task=job_a",
            AutomationTick.Dispatch(task("job_a", 100L, 600L)).logLine("conv-a"),
        )
        assertEquals(
            "discard scope=conv-a skipped=2 through=700",
            AutomationTick.Discard(through = 700L, skipped = 2).logLine("conv-a"),
        )
        assertEquals(
            "rebaseline scope=conv-a absorbed=2",
            AutomationTick.Rebaseline(at = 1_000L, absorb = listOf("a", "b")).logLine("conv-a"),
        )
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
     * 系统时间被往回拨：水位线比「现在」还晚。
     *
     * 这是第十二批留下的静默失效点：回拨之后结束的任务 `completed_at` 都落在水位线之前，
     * 按时间比较永远不会被选中 —— 外观上就是「自动续跑再也不工作了」，没有任何报错，
     * 直到真实时间重新追上旧水位线（可能是几小时、几天）。
     */
    @Test
    fun clockRolledBackRebaselinesTimeline() {
        val now = 1_700_000_000_000L
        val plan = tick(
            records = listOf(task("job_future", 100L, now + 3_000_000L)),
            seenUntil = now + 3_600_000L,
            nowMs = now,
        )
        // 回拨前的旧记录比「现在」还晚：不吸收就会变成未来事件被重放。
        assertEquals(AutomationTick.Rebaseline(at = now, absorb = listOf("job_future")), plan)
    }

    /** 秒级抖动（NTP 校正、手动校准）不该触发重建：否则每次校准都可能重放一批记录。 */
    @Test
    fun smallClockDriftDoesNotRebaseline() {
        val now = 1_700_000_000_000L
        val plan = tick(
            records = emptyList(),
            seenUntil = now + 30_000L,
            nowMs = now,
        )
        assertEquals(AutomationTick.Idle, plan)
    }

    /** 只吸收「比现在还晚」的记录：回拨之后结束的任务本来就该是历史，不能记成已续。 */
    @Test
    fun rebaselineAbsorbsOnlyFutureRecordsOfThisScope() {
        val now = 1_700_000_000_000L
        val plan = tick(
            records = listOf(
                task("job_future_mine", 100L, now + 3_000_000L),
                task("job_past_mine", 100L, now - 5_000L),
                task("job_future_other", 100L, now + 3_000_000L, sessionScope = "conv-b"),
                task("job_running_mine", 100L, null),
            ),
            seenUntil = now + 3_600_000L,
            nowMs = now,
        )
        assertEquals(AutomationTick.Rebaseline(at = now, absorb = listOf("job_future_mine")), plan)
    }

    /** 回拨时即使「未来记录」看起来像候选，也必须先重建时间轴，不能直接发。 */
    @Test
    fun rebaselineTakesPrecedenceOverCandidates() {
        val now = 1_700_000_000_000L
        val plan = tick(
            records = listOf(task("job_future", 100L, now + 4_000_000L)),
            seenUntil = now + 3_600_000L,
            nowMs = now,
        )
        assertEquals(true, plan is AutomationTick.Rebaseline)
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

    /**
     * 续跑提示词只有一份实现（第十九批）。
     *
     * 原来手动「继续」在卡片里自己拼：`%2$s` 填的是「job」/「daemon」类型词，自动续跑填的是任务描述。
     * 同一句话里同一个占位符的含义随入口变，用户在聊天里看到的就是
     * 「后台任务 job_ab12（job）已结束」—— 括号里本该是任务名，却是一个类型词。
     */
    @Test
    fun resumePromptCarriesTaskDescription() {
        val prompt = backgroundResumePrompt(
            "%1\$s / %2\$s",
            task("job_a", 100L, 600L, description = "下载模型"),
        )
        assertEquals("job_a / 下载模型", prompt)
    }

    /** 没写描述时退回命令：宁可长一点，也不能退成空或类型词。 */
    @Test
    fun resumePromptFallsBackToCommandWhenDescriptionBlank() {
        val prompt = backgroundResumePrompt(
            "%1\$s / %2\$s",
            task("dm_a", 100L, 600L, description = "", command = "python3 app.py"),
        )
        assertEquals("dm_a / python3 app.py", prompt)
    }

    /** 类型词不能出现在「任务原名」的位置（两个入口曾经各填一套）。 */
    @Test
    fun resumePromptNeverSubstitutesTaskType() {
        val rendered = listOf(
            backgroundResumePrompt("%2\$s", task("job_a", 100L, 600L, description = "打包")),
            backgroundResumePrompt("%2\$s", task("dm_a", 100L, 600L, description = "语音服务")),
        )
        rendered.forEach { text ->
            assertFalse("不能把类型词当任务原名：$text", text == "job" || text == "daemon")
        }
    }
}
