package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentGoal
import io.github.mangi.eta.agent.model.AgentGoalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentGoalStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentGoalStore(temporaryFolder.root)

    @Test
    fun savesAndReloadsOneGoalPerScope() {
        val store = store()
        val goal = AgentGoal.create("交付 v3.3.1", now = 42L)
        assertTrue(store.save("conversation-1", goal))
        assertEquals(goal, store.load("conversation-1"))
        // 不同作用域互不可见，也不会因为哈希前缀相同而串门。
        assertNull(store.load("conversation-2"))
        val other = goal.copy(id = "other", objective = "另一个目标")
        assertTrue(store.save("conversation-2", other))
        assertEquals(goal, store.load("conversation-1"))
        assertEquals(other, store.load("conversation-2"))
    }

    @Test
    fun overwriteKeepsLatestRevisionAndLeavesNoTempFile() {
        val store = store()
        val goal = AgentGoal.create("交付 v3.3.1", now = 42L)
        store.save("c", goal)
        val updated = goal.copy(revision = 4, phase = AgentGoalPhase.COMPLETED)
        assertTrue(store.save("c", updated))
        assertEquals(updated, store.load("c"))
        val dir = store.fileFor("c")!!.parentFile!!
        assertFalse(
            "原子替换后不应留下临时文件",
            dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun clearRemovesOnlyThatScope() {
        val store = store()
        store.save("a", AgentGoal.create("甲", now = 1L))
        store.save("b", AgentGoal.create("乙", now = 1L))
        assertTrue(store.clear("a"))
        assertNull(store.load("a"))
        assertEquals("乙", store.load("b")?.objective)
    }

    @Test
    fun blankScopeIsUnavailableInsteadOfSilentlyShared() {
        val store = store()
        assertNull(store.fileFor(""))
        assertNull(store.fileFor("   "))
        assertFalse(store.save("", AgentGoal.create("甲", now = 1L)))
        assertFalse(store.clear(""))
    }

    @Test
    fun unreadableFilesAreTreatedAsNoGoal() {
        val store = store()
        val goal = AgentGoal.create("交付 v3.3.1", now = 42L)
        store.save("c", goal)
        val file = store.fileFor("c")!!
        file.writeText("not json", Charsets.UTF_8)
        assertNull(store.load("c"))
        // 超大文件不解析：宁可当成没有目标，也不把异常带进工具调用。
        file.writeText("x".repeat(AgentGoalStore.MAX_BYTES + 1), Charsets.UTF_8)
        assertNull(store.load("c"))
    }

    @Test
    fun changesFlowEmitsOnWriteAndClear() {
        val before = AgentGoalStore.changes.value
        val store = store()
        store.save("c", AgentGoal.create("甲", now = 1L))
        assertTrue(AgentGoalStore.changes.value > before)
        val afterSave = AgentGoalStore.changes.value
        store.clear("c")
        assertTrue(AgentGoalStore.changes.value > afterSave)
    }

    @Test
    fun mutateBumpsRevisionAndPersistsInOneReadModifyWrite() {
        val store = store()
        val goal = AgentGoal.create("甲", now = 1L)
        store.save("c", goal)
        assertEquals(
            GoalMutation.SAVED,
            store.mutate("c", goal.revision) { it.copy(phase = AgentGoalPhase.PAUSED) },
        )
        val reloaded = store.load("c")!!
        assertEquals(AgentGoalPhase.PAUSED, reloaded.phase)
        assertEquals(goal.revision + 1, reloaded.revision)
        assertEquals("甲", reloaded.objective)
    }

    @Test
    fun mutateRejectsStaleRevisionInsteadOfOverwriting() {
        val store = store()
        val goal = AgentGoal.create("甲", now = 1L)
        store.save("c", goal)
        // 模型刚用 goal update 改过：revision 已加一，目标也换了。
        store.save("c", goal.copy(revision = goal.revision + 1, objective = "乙"))
        assertEquals(
            GoalMutation.STALE,
            store.mutate("c", goal.revision) { it.copy(phase = AgentGoalPhase.PAUSED) },
        )
        // 被拒的界面写不得改动模型写的那一版。
        assertEquals("乙", store.load("c")?.objective)
        assertEquals(AgentGoalPhase.ACTIVE, store.load("c")?.phase)
    }

    @Test
    fun mutateReportsMissingAndInvalidAndTrimsObjective() {
        val store = store()
        assertEquals(GoalMutation.MISSING, store.mutate("c", 1) { it })
        val goal = AgentGoal.create("甲", now = 1L)
        store.save("c", goal)
        assertEquals(
            GoalMutation.INVALID,
            store.mutate("c", goal.revision) { it.copy(objective = "   ") },
        )
        assertEquals(
            GoalMutation.INVALID,
            store.mutate("c", goal.revision) {
                it.copy(objective = "字".repeat(AgentGoal.MAX_OBJECTIVE_CHARS + 1))
            },
        )
        assertEquals("甲", store.load("c")?.objective)
        assertEquals(
            GoalMutation.SAVED,
            store.mutate("c", goal.revision) { it.copy(objective = "  乙  ") },
        )
        assertEquals("乙", store.load("c")?.objective)
    }

    @Test
    fun concurrentMutatesFromSameRevisionOnlyOneWins() {
        val store = store()
        val goal = AgentGoal.create("甲", now = 1L)
        store.save("c", goal)
        val results = java.util.Collections.synchronizedList(mutableListOf<GoalMutation>())
        val gate = java.util.concurrent.CountDownLatch(1)
        val threads = (1..2).map {
            Thread {
                gate.await()
                results += store.mutate("c", goal.revision) {
                    it.copy(phase = AgentGoalPhase.PAUSED)
                }
            }
        }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue("线程没在 5 秒内结束（卡在锁里？）", threads.none { it.isAlive })
        // 读-改-写在同一把锁里：两个线程从同一 revision 出发，只能有一个写成功。
        assertEquals(1, results.count { it == GoalMutation.SAVED })
        assertEquals(1, results.count { it == GoalMutation.STALE })
        assertEquals(goal.revision + 1, store.load("c")?.revision)
    }
}
