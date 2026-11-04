package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskRecord
import io.github.mangi.eta.agent.model.AgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 台账卡收起行的计数。
 *
 * 界面自己算错（把已取消算成运行中、总数漏一条）用户完全看不出来，只会照着错数字判断
 * 「还剩几个在跑」；卡片本身要真机截图才能看，本机跑不了。所以把映射抽成纯函数在这里守。
 */
class AgentTaskLedgerSummaryTest {

    private fun record(id: String, status: AgentTaskStatus, startedAt: Long = 1_000L) = AgentTaskRecord(
        id = id,
        kind = AgentTaskRecord.KIND_SUBAGENT,
        title = "任务 $id",
        status = status,
        ref = null,
        summary = null,
        startedAt = startedAt,
        endedAt = null,
    )

    @Test
    fun eachStatusLandsInItsOwnBucket() {
        val ledger = AgentTaskLedger(
            listOf(
                record("a", AgentTaskStatus.RUNNING, startedAt = 4_000L),
                record("b", AgentTaskStatus.RUNNING, startedAt = 3_000L),
                record("c", AgentTaskStatus.COMPLETED, startedAt = 2_000L),
                record("d", AgentTaskStatus.FAILED, startedAt = 1_500L),
                record("e", AgentTaskStatus.CANCELLED, startedAt = 1_000L),
            ),
        )

        val summary = ledger.cardSummary()

        assertEquals(5, summary.total)
        assertEquals(2, summary.running)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.cancelled)
    }

    @Test
    fun everyBucketIsAccountedForInTheTotal() {
        // 将来加了新状态却忘了在 cardSummary 里归类时，差额会立刻暴露出来；
        // 否则那一类任务会从卡片上静默消失，只剩「总数 - 已完成」对不上。
        val ledger = AgentTaskLedger(
            AgentTaskStatus.entries.mapIndexed { index, status ->
                record("sub-$index", status, startedAt = 10_000L - index)
            },
        )

        val summary = ledger.cardSummary()

        assertEquals(
            summary.total,
            summary.completed + summary.running + summary.failed +
                summary.cancelled + summary.stalled,
        )
    }

    @Test
    fun emptyLedgerCountsAsNothing() {
        val summary = AgentTaskLedger().cardSummary()

        assertEquals(0, summary.total)
        assertEquals(0, summary.completed)
        assertEquals(0, summary.running)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.cancelled)
        assertEquals(0, summary.stalled)
    }

    /**
     * 停滞收尾必须单独一桶：并进 cancelled 的话，卡片会把「卡住自动放弃」
     * 显示成「用户叫停」，而两者对模型下一步的含义正好相反。
     */
    @Test
    fun stalledIsItsOwnBucketNotCancelled() {
        val ledger = AgentTaskLedger(
            listOf(
                record("a", AgentTaskStatus.STALLED, startedAt = 3_000L),
                record("b", AgentTaskStatus.CANCELLED, startedAt = 2_000L),
                record("c", AgentTaskStatus.STALLED, startedAt = 1_000L),
            ),
        )

        val summary = ledger.cardSummary()

        assertEquals(3, summary.total)
        assertEquals(2, summary.stalled)
        assertEquals(1, summary.cancelled)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.running)
    }

    @Test
    fun orphanedRunningEntriesShowAsCancelledNotRunning() {
        // 台账里最坏的情况：上个进程留下的 running 其实早就不存在了（§32.52 会把它改成 cancelled）。
        // 卡片必须跟着变，否则用户会一直看到「运行中 2」，去等两个永远不回来的子代理。
        val before = AgentTaskLedger(
            listOf(
                record("a", AgentTaskStatus.RUNNING, startedAt = 3_000L),
                record("b", AgentTaskStatus.RUNNING, startedAt = 2_000L),
                record("c", AgentTaskStatus.COMPLETED, startedAt = 1_000L),
            ),
        )

        val summary = before.abandonRunning()!!.cardSummary()

        assertEquals(3, summary.total)
        assertEquals(0, summary.running)
        assertEquals(2, summary.cancelled)
        assertEquals(1, summary.completed)
    }

    @Test
    fun totalFollowsTheBoundedLedgerNotEveryDispatch() {
        // 台账有上界（32 条），卡片显示的是「还在台账里的」，不是「一共派过多少」。
        val ledger = (1..40).fold(AgentTaskLedger()) { acc, index ->
            acc.upsert(record("sub-$index", AgentTaskStatus.COMPLETED, startedAt = index.toLong() * 1_000L))
        }

        val summary = ledger.cardSummary()

        assertEquals(AgentTaskLedger.MAX_ENTRIES, summary.total)
        assertEquals(AgentTaskLedger.MAX_ENTRIES, summary.completed)
    }
}
