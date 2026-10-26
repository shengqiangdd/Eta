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
}
