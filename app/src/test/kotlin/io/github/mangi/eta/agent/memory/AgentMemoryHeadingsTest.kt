package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMemoryHeadingsTest {
    private val document = listOf(
        "# 核心记忆",
        "- 偏好：中文",
        "## 项目",
        "- 阶段一",
        "### 细节",
        "- 线索",
        "",
        "## 其他",
        "- 杂项",
    )

    @Test
    fun `nearest heading walks upward from the hit line`() {
        assertEquals("细节", AgentMemoryHeadings.nearest(document, 5))
        assertEquals("项目", AgentMemoryHeadings.nearest(document, 3))
        assertEquals("核心记忆", AgentMemoryHeadings.nearest(document, 1))
    }

    @Test
    fun `heading line itself resolves to its own title`() {
        assertEquals("项目", AgentMemoryHeadings.nearest(document, 2))
    }

    @Test
    fun `no heading above yields null`() {
        assertNull(AgentMemoryHeadings.nearest(listOf("plain text", "more text"), 1))
        assertNull(AgentMemoryHeadings.nearest(emptyList(), 0))
    }

    @Test
    fun `out of range index is clamped`() {
        assertEquals("其他", AgentMemoryHeadings.nearest(document, 999))
    }

    @Test
    fun `collect dedupes and keeps document order`() {
        val headings = AgentMemoryHeadings.collect(document, listOf(1, 3, 5, 8))
        assertEquals(listOf("核心记忆", "项目", "细节", "其他"), headings)
    }

    @Test
    fun `collect respects the limit`() {
        assertEquals(2, AgentMemoryHeadings.collect(document, listOf(1, 3, 5, 8), limit = 2).size)
    }

    @Test
    fun `empty hits produce no headings`() {
        assertEquals(emptyList<String>(), AgentMemoryHeadings.collect(document, emptyList()))
    }

    @Test
    fun `trailing spaces and hashes are normalised`() {
        assertEquals("标题", AgentMemoryHeadings.nearest(listOf("## 标题   "), 0))
        assertEquals("深层", AgentMemoryHeadings.nearest(listOf("###### 深层"), 0))
    }
}
