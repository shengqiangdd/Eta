package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
    }

    @Test
    fun learningsPageStaysOutOfCoreAndIsInjectedSeparately() {
        val content = """
            # 核心记忆
            偏好
            ## 项目
            只应注入核心

            # 经验教训

            > 说明

            - 2026-10-29 · tool=terminal · code=EXIT_1 · hits=2 · sh: not found
        """.trimIndent()

        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertFalse(context.coreContent.contains("经验教训"))
        assertFalse(context.coreContent.contains("EXIT_1"))
        assertTrue(context.learnings.contains("EXIT_1"))
        assertEquals(1, context.learningsTotal)
        assertEquals(1, context.learningsShown)
    }

    @Test
    fun learningsInjectionIsBoundedByItsOwnBudget() {
        val entries = (1..60).joinToString("\n") { index ->
            "- 2026-10-29 · tool=tool$index · code=E$index · hits=1 · " + "x".repeat(120)
        }
        val content = "# 核心记忆\n偏好\n\n# 经验教训\n\n$entries"

        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(60, context.learningsTotal)
        assertTrue(context.learnings.length <= 1_800)
        assertTrue(context.learningsShown < context.learningsTotal)
    }

    @Test
    fun memoryWithoutLearningsPageReportsNothing() {
        val context = AgentMemoryContextBuilder.build(snapshot("# 核心记忆\n偏好"), 128_000)

        assertEquals("", context.learnings)
        assertEquals(0, context.learningsTotal)
        assertEquals(0, context.learningsShown)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
