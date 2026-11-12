package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryDocumentTest {

    private val document = """
        # 核心记忆
        - 正文已归档。

        ## 用户偏好
        - 中文交流。
        - 重视诚实。

        ## 进行中项目
        ### 子项目
        细节一
        ### 另一个子项目
        细节二

        ## 空的
    """.trimIndent()

    @Test
    fun `sections stop at next heading of same or higher level`() {
        val sections = AgentMemoryDocument.sections(document)
        assertEquals(listOf("核心记忆", "用户偏好", "进行中项目", "子项目", "另一个子项目", "空的"), sections.map { it.title })
        val project = sections.first { it.title == "进行中项目" }
        assertTrue(project.body.contains("子项目"))
        assertTrue(project.body.contains("细节二"))
        val child = sections.first { it.title == "子项目" }
        assertTrue(child.body.contains("细节一"))
        assertTrue(!child.body.contains("细节二"))
    }

    @Test
    fun `core section keeps its body and first line numbers`() {
        val sections = AgentMemoryDocument.sections(document)
        val core = sections.first { it.title == "核心记忆" }
        assertEquals(1, core.startLine)
        assertEquals(3, core.endLine)
        assertTrue(core.body.contains("正文已归档"))
        assertEquals("# 核心记忆\n\n- 正文已归档。", core.text)
    }

    @Test
    fun `endLine is exclusive so outline display must subtract one`() {
        // 固定住这个容易被「修正」坏的语义：endLine 是 replaceSection 里 subList 的
        // 上界，不含。章节目录展示行范围时减一，否则会多报一行。
        val content = "# 一\n甲\n\n# 二\n乙\n"
        val first = AgentMemoryDocument.sections(content).first { it.title == "一" }
        assertEquals(1, first.startLine)
        assertEquals(3, first.endLine)
        // 实际属于“一”的内容（1-based，含）是 startLine..endLine-1
        val lines = content.split('\n')
        assertEquals(listOf("# 一", "甲", ""), lines.subList(first.startLine - 1, first.endLine))
    }

    @Test
    fun `find prefers exact then case-insensitive then unique prefix`() {
        val sections = AgentMemoryDocument.sections(document)
        assertNotNull(AgentMemoryDocument.find(sections, "用户偏好"))
        assertNotNull(AgentMemoryDocument.find(sections, "## 用户偏好"))
        assertNotNull(AgentMemoryDocument.find(sections, "用户"))
        // 没有任何标题以“项目”开头：前缀匹配必须返回 null，而不是随便挑一个。
        assertNull(AgentMemoryDocument.find(sections, "项目"))
        assertNull(AgentMemoryDocument.find(sections, ""))
    }

    @Test
    fun `replace section keeps heading and other sections untouched`() {
        val sections = AgentMemoryDocument.sections(document)
        val target = sections.first { it.title == "用户偏好" }
        val updated = AgentMemoryDocument.replaceSection(document, target, "- 只用中文。")

        val updatedSections = AgentMemoryDocument.sections(updated)
        assertEquals("- 只用中文。", updatedSections.first { it.title == "用户偏好" }.body)
        assertTrue(updated.contains("## 进行中项目"))
        assertTrue(updated.contains("细节二"))
        assertTrue(!updated.contains("重视诚实"))
    }

    @Test
    fun `empty body keeps only the heading`() {
        val sections = AgentMemoryDocument.sections(document)
        val target = sections.first { it.title == "空的" }
        val updated = AgentMemoryDocument.replaceSection(document, target, "")
        val updatedSections = AgentMemoryDocument.sections(updated)
        val empty = updatedSections.first { it.title == "空的" }
        assertEquals("", empty.body)
        assertEquals(empty.startLine, empty.endLine)
    }

    @Test
    fun `blank document has no sections`() {
        assertTrue(AgentMemoryDocument.sections("").isEmpty())
        assertTrue(AgentMemoryDocument.sections("no headings here").isEmpty())
    }
}
