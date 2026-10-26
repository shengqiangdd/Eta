package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentTodoItem
import io.github.mangi.eta.agent.model.AgentTodoPlan
import io.github.mangi.eta.agent.model.AgentTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTodoStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentTodoStore(temporaryFolder.root)

    @Test
    fun savesAndReloadsWholeList() {
        val store = store()
        val plan = AgentTodoPlan(
            listOf(
                AgentTodoItem("a", AgentTodoStatus.COMPLETED),
                AgentTodoItem("b", AgentTodoStatus.PENDING),
            ),
        )
        assertTrue(store.save("conversation-1", plan))
        assertEquals(plan, store.load("conversation-1"))
        assertNull(store.load("conversation-2"))
    }

    @Test
    fun lastWriteWins() {
        val store = store()
        store.save("c", AgentTodoPlan(listOf(AgentTodoItem("a", AgentTodoStatus.PENDING))))
        val second = AgentTodoPlan(listOf(AgentTodoItem("b", AgentTodoStatus.IN_PROGRESS)))
        assertTrue(store.save("c", second))
        assertEquals(second, store.load("c"))
    }

    @Test
    fun emptyPlanClearsTheFile() {
        val store = store()
        store.save("c", AgentTodoPlan(listOf(AgentTodoItem("a", AgentTodoStatus.PENDING))))
        val file = store.fileFor("c")!!
        assertTrue(file.isFile)
        assertTrue(store.save("c", AgentTodoPlan(emptyList())))
        assertFalse(file.exists())
        assertNull(store.load("c"))
    }

    @Test
    fun blankScopeIsNotPersisted() {
        val store = store()
        assertFalse(store.save("  ", AgentTodoPlan(listOf(AgentTodoItem("a", AgentTodoStatus.PENDING)))))
        assertNull(store.load(""))
    }

    @Test
    fun scopeIsHashedIntoASafeFileName() {
        val file = store().fileFor("../../etc/passwd")!!
        assertEquals(temporaryFolder.root, file.parentFile)
        assertTrue(file.name.endsWith(".json"))
        assertFalse(file.name.contains("/"))
    }

    @Test
    fun oversizedFileIsIgnored() {
        val store = store()
        val file = store.fileFor("c")!!
        file.parentFile!!.mkdirs()
        file.writeText("x".repeat(AgentTodoStore.MAX_BYTES + 1), Charsets.UTF_8)
        assertNull(store.load("c"))
    }

    @Test
    fun successfulWriteNotifiesObservers() {
        val store = store()
        val before = AgentTodoStore.changes.value
        store.save("c", AgentTodoPlan(listOf(AgentTodoItem("a", AgentTodoStatus.PENDING))))
        assertEquals(before + 1, AgentTodoStore.changes.value)
        store.clear("c")
        assertEquals(before + 2, AgentTodoStore.changes.value)
    }
}
