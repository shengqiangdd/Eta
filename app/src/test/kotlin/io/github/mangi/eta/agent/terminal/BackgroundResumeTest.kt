package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动续跑的判定。
 *
 * 这里守的是「不会自己跑飞」：旧任务不自动续、续过的不再续、次数上限到了就停。
 * 真正发消息那一步（Compose 副作用 + 本地化文案）本机测不了，只能在真机上看。
 */
class BackgroundResumeTest {

    private fun task(id: String, startedAt: Long, completedAt: Long?) = DetachedTask(
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
    )

    @Test
    fun noRecordsMeansNoResume() {
        val plan = planAutoResume(emptyList(), emptySet(), 0L, alreadyResumed = 0)
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun runningTaskIsNotACandidate() {
        val plan = planAutoResume(listOf(task("job_a", 100L, null)), emptySet(), 0L, 0)
        assertNull(plan.next)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun taskFinishedBeforeCardOpenedIsIgnored() {
        // 卡片打开的那一刻是 500：这条 499 结束的属于历史，不能被自动续
        val plan = planAutoResume(listOf(task("job_a", 100L, 499L)), emptySet(), 500L, 0)
        assertNull(plan.next)
        // 恰好等于边界也不行：判据是「严格晚于」
        val edge = planAutoResume(listOf(task("job_a", 100L, 500L)), emptySet(), 500L, 0)
        assertNull(edge.next)
    }

    @Test
    fun handledTaskIsNotResumedAgain() {
        val plan = planAutoResume(
            records = listOf(task("job_a", 100L, 600L)),
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
            handled = emptySet(),
            finishedAfterMs = 500L,
            alreadyResumed = 0,
            maxCount = 0,
        )
        assertNull(plan.next)
        assertEquals(1, plan.skipped)
    }
}
