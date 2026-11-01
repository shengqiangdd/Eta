package io.github.mangi.eta.agent.terminal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续跑记忆：真机实测发现「卡片里的 remember 会在一次运行后丢」，这一段就是补上的防线。
 */
class BackgroundResumeMemoryTest {

    @After
    fun tearDown() {
        BackgroundResumeMemory.reset()
    }

    @Test
    fun marksAndRemembers() {
        assertFalse(BackgroundResumeMemory.resumed.contains("job_a"))
        BackgroundResumeMemory.markResumed("job_a")
        assertTrue(BackgroundResumeMemory.resumed.contains("job_a"))
    }

    @Test
    fun sameIdIsNotAddedTwice() {
        BackgroundResumeMemory.markResumed("job_a")
        BackgroundResumeMemory.markResumed("job_a")
        assertEquals(listOf("job_a"), BackgroundResumeMemory.resumed.toList())
    }

    @Test
    fun blankIdIsIgnored() {
        BackgroundResumeMemory.markResumed("")
        BackgroundResumeMemory.markResumed("   ")
        assertTrue(BackgroundResumeMemory.resumed.isEmpty())
    }

    @Test
    fun autoResumeCountsOnce() {
        BackgroundResumeMemory.markAutoResumed("job_a")
        assertEquals(1, BackgroundResumeMemory.autoResumed)
        // 同一条重复标记仍算一次自动续，但 id 不会重复
        BackgroundResumeMemory.markAutoResumed("job_a")
        assertEquals(2, BackgroundResumeMemory.autoResumed)
        assertEquals(listOf("job_a"), BackgroundResumeMemory.resumed.toList())
    }

    @Test
    fun remembersAreBounded() {
        repeat(80) { BackgroundResumeMemory.markResumed("job_$it") }
        assertEquals(64, BackgroundResumeMemory.resumed.size)
        // 超出后丢最旧的
        assertFalse(BackgroundResumeMemory.resumed.contains("job_0"))
        assertTrue(BackgroundResumeMemory.resumed.contains("job_79"))
    }

    @Test
    fun resetClearsBoth() {
        BackgroundResumeMemory.markAutoResumed("job_a")
        BackgroundResumeMemory.reset()
        assertTrue(BackgroundResumeMemory.resumed.isEmpty())
        assertEquals(0, BackgroundResumeMemory.autoResumed)
    }
}
