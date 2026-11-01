package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 续跑的「已经续过哪些」记忆。
 *
 * 真机实测（canary，2026-11-01）挖出的缺陷：状态放在卡片的 `remember` 里，一轮跑完就丢，
 * 按钮重现、「同一条不再续」与「最多 2 次」两条防线同时失效。这里守的就是那两条防线。
 */
class BackgroundResumeMemoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        BackgroundResumeMemory.reset()
    }

    private fun store() = BackgroundResumeStore(temporaryFolder.root)

    private fun scope(id: String = "conversation-1"): BackgroundResumeScope =
        BackgroundResumeMemory.scopeFor(store(), id)

    @Test
    fun marksAndRemembers() {
        val scope = scope()
        assertFalse(scope.resumed.contains("job_a"))
        assertTrue(scope.markResumed("job_a"))
        assertTrue(scope.resumed.contains("job_a"))
    }

    @Test
    fun sameIdIsNotAddedTwice() {
        val scope = scope()
        assertTrue(scope.markResumed("job_a"))
        assertFalse(scope.markResumed("job_a"))
        assertEquals(listOf("job_a"), scope.resumed.toList())
    }

    @Test
    fun blankIdIsIgnored() {
        val scope = scope()
        assertFalse(scope.markResumed(""))
        assertFalse(scope.markResumed("   "))
        assertTrue(scope.resumed.isEmpty())
    }

    @Test
    fun autoResumeCountsEvenWhenIdWasAlreadyRemembered() {
        val scope = scope()
        scope.markAutoResumed("job_a")
        assertEquals(1, scope.autoResumed)
        // 同一条重复标记：id 不会重复，但次数要算（预算是按次消耗的）。
        scope.markAutoResumed("job_a")
        assertEquals(2, scope.autoResumed)
        assertEquals(listOf("job_a"), scope.resumed.toList())
    }

    @Test
    fun remembersAreBounded() {
        val scope = scope()
        repeat(BackgroundResumeStore.MAX_IDS + 16) { scope.markResumed("job_$it") }
        assertEquals(BackgroundResumeStore.MAX_IDS, scope.resumed.size)
        assertFalse(scope.resumed.contains("job_0"))
        assertTrue(scope.resumed.contains("job_${BackgroundResumeStore.MAX_IDS + 15}"))
    }

    @Test
    fun scopesAreIsolated() {
        val first = scope("conversation-a")
        val second = scope("conversation-b")
        first.markResumed("job_a")
        assertTrue(second.resumed.isEmpty())
        // 同一个 id 出现在两个会话里互不影响 —— 记录本身没有会话归属，
        // 但「在 A 里续过」不该让 B 少一个按钮。
        assertTrue(second.markResumed("job_a"))
    }

    @Test
    fun sameScopeReturnsTheSameInstance() {
        val first = BackgroundResumeMemory.scopeFor(store(), "conversation-1")
        val second = BackgroundResumeMemory.scopeFor(store(), "conversation-1")
        assertTrue(first === second)
    }

    @Test
    fun persistedIdsComeBackAfterProcessReset() = runBlocking {
        val before = scope("conversation-1")
        before.markResumed("job_a")
        before.markResumed("job_b")
        before.persist()

        // 模拟进程被回收：内存表清空，只剩磁盘上的文件。
        BackgroundResumeMemory.reset()
        val after = scope("conversation-1")
        assertTrue(after.resumed.isEmpty())
        after.loadFromStore()
        assertEquals(listOf("job_a", "job_b"), after.resumed.toList())
        assertTrue(after.isLoaded)
    }

    @Test
    fun autoResumeCountDoesNotSurviveReset() = runBlocking {
        val before = scope("conversation-1")
        before.markAutoResumed("job_a")
        before.persist()

        BackgroundResumeMemory.reset()
        val after = scope("conversation-1")
        after.loadFromStore()
        // 只恢复「续过哪些」，不恢复预算：落盘会让「自动续了 2 次」永久生效。
        assertEquals(listOf("job_a"), after.resumed.toList())
        assertEquals(0, after.autoResumed)
    }

    @Test
    fun loadIsIdempotent() = runBlocking {
        val first = scope("conversation-1")
        first.markResumed("job_a")
        first.persist()

        BackgroundResumeMemory.reset()
        val after = scope("conversation-1")
        after.loadFromStore()
        after.loadFromStore()
        assertEquals(listOf("job_a"), after.resumed.toList())
        assertTrue(after.isLoaded)
    }

    @Test
    fun blankScopeWorksInMemoryOnly() = runBlocking {
        val scope = BackgroundResumeMemory.scopeFor(store = null, scopeId = "")
        assertTrue(scope.markResumed("job_a"))
        // 写盘被跳过，但内存状态照常生效，也不抛异常。
        scope.persist()
        assertEquals(listOf("job_a"), scope.resumed.toList())
    }

    @Test
    fun resetClearsEveryScope() {
        scope("conversation-a").markResumed("job_a")
        scope("conversation-b").markResumed("job_b")
        BackgroundResumeMemory.reset()
        assertTrue(scope("conversation-a").resumed.isEmpty())
        assertTrue(scope("conversation-b").resumed.isEmpty())
    }
}
