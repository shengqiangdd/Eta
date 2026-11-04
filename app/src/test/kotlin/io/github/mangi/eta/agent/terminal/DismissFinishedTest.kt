package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除已结束」的筛选边界。
 *
 * 删除不可逆，所以这里守的不是「删得掉」，而是**别删过头**：一旦把「结果还没通知过的命令任务」
 * 或「刚结束、自动续跑的兜底扇描还没看到的那条」删掉，用户就永远收不到那条结果了。
 *
 * 落盘与删日志文件的真实路径（要 shell）不在这一层测，界面也不在。
 */
class DismissFinishedTest {

    private val now = 1_700_000_000_000L

    private fun task(
        id: String,
        completedAt: Long? = null,
        commandTask: Boolean = false,
        completionNotified: Boolean = true,
        sessionScope: String? = null,
    ) = DetachedTask(
        id = id,
        pid = 42L,
        token = "token",
        command = "sleep 1",
        cwd = "/data/local/tmp",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/$id.log",
        startedAt = completedAt ?: now,
        commandTask = commandTask,
        description = "",
        completedAt = completedAt,
        completionNotified = completionNotified,
        sessionScope = sessionScope,
    )

    @Test
    fun runningTasksAreNeverDismissed() {
        val victims = pickDismissVictims(listOf(task("running")), now)
        assertTrue(victims.isEmpty())
    }

    @Test
    fun finishedBeyondGraceIsDismissed() {
        val victims = pickDismissVictims(
            listOf(task("old", completedAt = now - PruneGraceMs - 1)),
            now,
        )
        assertEquals(listOf("old"), victims.map { it.id })
    }

    @Test
    fun finishedInsideGraceStays() {
        // 兜底扇描 30 秒一次；刚结束的记录是自动续跑的事件源，删早了这次续跑等于没发生。
        val victims = pickDismissVictims(
            listOf(task("fresh", completedAt = now - PruneGraceMs + 1)),
            now,
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun unnotifiedCommandTaskStays() {
        val victims = pickDismissVictims(
            listOf(
                task(
                    "silent",
                    completedAt = now - PruneGraceMs - 1,
                    commandTask = true,
                    completionNotified = false,
                ),
            ),
            now,
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun notifiedCommandTaskIsDismissed() {
        val victims = pickDismissVictims(
            listOf(task("reported", completedAt = now - PruneGraceMs - 1, commandTask = true)),
            now,
        )
        assertEquals(listOf("reported"), victims.map { it.id })
    }

    @Test
    fun daemonNeedsNoCompletionNotification() {
        // 守护任务没有「跑完了」这条通知，结束即可清。
        val victims = pickDismissVictims(
            listOf(
                task(
                    "daemon",
                    completedAt = now - PruneGraceMs - 1,
                    completionNotified = false,
                ),
            ),
            now,
        )
        assertEquals(listOf("daemon"), victims.map { it.id })
    }

    @Test
    fun mixedListKeepsOnlyTheProtectedOnes() {
        val victims = pickDismissVictims(
            listOf(
                task("running"),
                task("fresh", completedAt = now - 1_000),
                task("old-job", completedAt = now - 600_000, commandTask = true),
                task("old-daemon", completedAt = now - 600_000),
            ),
            now,
        )
        assertEquals(setOf("old-job", "old-daemon"), victims.map { it.id }.toSet())
    }

    @Test
    fun dismissOnlyTouchesTheViewersOwnFinishedTasks() {
        // 按钮上写几条就只删几条：A 会话点一下不能把 B 会话的已结束记录连带清掉。
        // 那些记录同时是 B 的自动续跑事件源，删了对方就永远不会再被扇到。
        val victims = pickDismissVictims(
            listOf(
                task("mine", completedAt = now - 600_000, sessionScope = "scope-a"),
                task("theirs", completedAt = now - 600_000, sessionScope = "scope-b"),
            ),
            now,
            viewerScope = "scope-a",
        )

        assertEquals(listOf("mine"), victims.map { it.id })
    }

    @Test
    fun dismissWithoutViewerScopesClearsEverything() {
        // 没有会话概念的调用方（语音入口、外部直调）保持旧行为。
        val victims = pickDismissVictims(
            listOf(
                task("mine", completedAt = now - 600_000, sessionScope = "scope-a"),
                task("theirs", completedAt = now - 600_000, sessionScope = "scope-b"),
            ),
            now,
        )

        assertEquals(setOf("mine", "theirs"), victims.map { it.id }.toSet())
    }

    @Test
    fun pendingScopeClearsOnlyUnscopedLegacyRecords() {
        // 新对话在发出第一条消息前没有会话 id。若拿它当「没有会话概念」的老调用方，
        // 空对话里点一下「清除」就会把别人的记录删掉 —— 而卡片上根本没列出那几条。
        val victims = pickDismissVictims(
            listOf(
                task("legacy", completedAt = now - 600_000),
                task("theirs", completedAt = now - 600_000, sessionScope = "scope-b"),
            ),
            now,
            viewerScope = null,
            scopePending = true,
        )

        assertEquals(listOf("legacy"), victims.map { it.id })
    }
}
