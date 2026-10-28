package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryLearningsTest {

    @Test
    fun parsesRenderedEntryWithAllFields() {
        val body = "- 2026-10-29 · tool=terminal · code=EXIT_1 · hits=2 · sh: cd: no such file"

        val entry = AgentMemoryLearnings.parse(body).single()

        assertEquals("2026-10-29", entry.date)
        assertEquals("terminal", entry.tool)
        assertEquals("EXIT_1", entry.code)
        assertEquals(2, entry.hits)
        assertEquals("sh: cd: no such file", entry.message)
    }

    @Test
    fun ignoresNonEntryLinesSoUserNotesSurviveParsing() {
        val body = "> 说明行\n用户手写的一行\n- 2026-10-29 · tool=terminal · code=EXIT_1 · hits=1 · boom"

        val entries = AgentMemoryLearnings.parse(body)

        assertEquals(1, entries.size)
        assertEquals(2, entries.single().lineIndex)
    }

    @Test
    fun mergeAppendsNewSignatureAndKeepsUserLines() {
        val body = "> 说明行\n用户手写的一行"

        val merged = AgentMemoryLearnings.merge(
            body = body,
            tool = "terminal",
            code = "EXIT_1",
            message = "line1\nline2",
            date = "2026-10-29",
        )

        assertTrue(merged.changed)
        assertEquals(1, merged.entries)
        assertTrue(merged.body.contains("用户手写的一行"))
        // 错误首行被压平：一条记录永远只占一行。
        assertTrue(
            merged.body.contains(
                "- 2026-10-29 · tool=terminal · code=EXIT_1 · hits=1 · line1 line2",
            ),
        )
    }

    @Test
    fun mergeSameSignatureIncrementsHitsAndRefreshesMessageInPlace() {
        val body = "- 2026-10-28 · tool=terminal · code=EXIT_1 · hits=2 · old msg\n" +
            "- 2026-10-28 · tool=terminal · code=EXIT_9 · hits=1 · other"

        val merged = AgentMemoryLearnings.merge(
            body = body,
            tool = "terminal",
            code = "EXIT_1",
            message = "new msg",
            date = "2026-10-29",
        )

        assertEquals(2, merged.entries)
        val entries = AgentMemoryLearnings.parse(merged.body)
        assertEquals(2, entries.size)
        assertEquals(3, entries[0].hits)
        assertEquals("2026-10-29", entries[0].date)
        assertEquals("new msg", entries[0].message)
        // 不同错误码是不同签名，不能被合并。
        assertEquals(1, entries[1].hits)
        assertEquals("other", entries[1].message)
    }

    @Test
    fun mergeDropsOldestEntriesBeyondCap() {
        val body = (1..AgentMemoryLearnings.MAX_ENTRIES).joinToString("\n") { index ->
            "- 2026-10-01 · tool=t$index · code=EXIT_1 · hits=1 · m$index"
        }

        val merged = AgentMemoryLearnings.merge(
            body = body,
            tool = "terminal",
            code = "EXIT_1",
            message = "newest",
            date = "2026-10-29",
        )

        assertEquals(AgentMemoryLearnings.MAX_ENTRIES, merged.entries)
        val entries = AgentMemoryLearnings.parse(merged.body)
        assertEquals(AgentMemoryLearnings.MAX_ENTRIES, entries.size)
        assertFalse(merged.body.contains("tool=t1 "))
        assertTrue(entries.last().message == "newest")
    }

    @Test
    fun summarizePutsFrequentEntriesFirstThenRecentOnes() {
        val body = """
            - 2026-10-01 · tool=a · code=E1 · hits=1 · rare
            - 2026-10-02 · tool=b · code=E2 · hits=4 · frequent
            - 2026-10-03 · tool=c · code=E3 · hits=2 · twice
        """.trimIndent()

        val summary = AgentMemoryLearnings.summarize(body, 400)
        val lines = summary.lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("frequent"))
        assertTrue(lines[1].contains("twice"))
        assertTrue(lines[2].contains("rare"))
    }

    @Test
    fun summarizeNeverExceedsBudget() {
        val body = (1..5).joinToString("\n") { index ->
            "- 2026-10-0$index · tool=tool$index · code=E$index · hits=$index · " + "x".repeat(60)
        }

        val summary = AgentMemoryLearnings.summarize(body, 40)

        assertTrue(summary.length <= 40)
    }

    @Test
    fun emptyBodyMeansNoLearnings() {
        assertEquals(0, AgentMemoryLearnings.parse("").size)
        assertTrue(AgentMemoryLearnings.isEmptyBody("> 只有说明行"))
        assertFalse(AgentMemoryLearnings.isEmptyBody("- 2026-10-29 · tool=a · code=E · hits=1 · m"))
    }

    @Test
    fun memoryToolsAreExcludedFromRecording() {
        assertFalse(AgentMemoryLearnings.shouldRecord(""))
        assertFalse(AgentMemoryLearnings.shouldRecord("memory_get"))
        assertFalse(AgentMemoryLearnings.shouldRecord("memory_write"))
        assertFalse(AgentMemoryLearnings.shouldRecord("character_memory_get"))
        assertTrue(AgentMemoryLearnings.shouldRecord("terminal"))
    }

    @Test
    fun sanitizeMessageFlattensControlCharsAndCapsLength() {
        val flattened = AgentMemoryLearnings.sanitizeMessage("a\r\n\tb\u0000c   d")
        assertEquals("a b c d", flattened)

        val capped = AgentMemoryLearnings.sanitizeMessage("y".repeat(400))
        assertEquals(AgentMemoryLearnings.MAX_MESSAGE_CHARS, capped.length)
    }

    @Test
    fun sectionHeadingIsTopLevelSoCoreInjectionStopsBeforeIt() {
        // 这是本轮修掉的真实缺陷：写成 `## 经验教训` 时它会被算进 `# 核心记忆` 的正文，
        // 既挤占核心预算，又在 <memory_learnings> 里重复注入。
        assertTrue(AgentMemoryLearnings.SECTION_HEADING.startsWith("# "))
    }
}
