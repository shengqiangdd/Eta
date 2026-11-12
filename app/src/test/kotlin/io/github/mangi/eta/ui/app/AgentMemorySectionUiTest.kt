package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.repository.AgentMemoryDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节目录（[toSectionUi]）是记忆界面唯一的数据来源，之前零测试覆盖。
 *
 * 它不是纯展示：目录项里的 startLine 会原样回传给 OpenSection / ReplaceSection
 * 用来定位章节，所以「行号是否是 1-based」「endLine 是否排他」这类细节一旦错了，
 * 点开的会是别的章节，或者保存时改错位置。
 */
class AgentMemorySectionUiTest {
    @Test
    fun `sections keep 1-based start line and exclusive end line`() {
        val doc = "# 核心记忆\n甲\n\n## 用户偏好\n乙\n"
        val items = doc.toSectionUi()

        assertEquals(2, items.size)
        assertEquals("核心记忆", items[0].title)
        assertEquals(1, items[0].startLine)
        // endLine 是排他上界（replaceSection 拿它当 subList 的 to）。
        // 本例行号：1=# 核心记忆 2=甲 3=空行 4=## 用户偏好，
        // 所以核心记忆覆盖第 1..2 行，endLine=3。
        assertEquals(3, items[0].endLine)
        assertEquals("用户偏好", items[1].title)
        assertEquals(4, items[1].startLine)
        assertEquals(2, items[1].level)
    }

    @Test
    fun `duplicate titles stay addressable because each item carries its own line`() {
        // 真实 MEMORY.md 里确实有重名标题（「踩坑记录」「门禁与产物」各两处）。
        // 目录项靠 startLine 区分，所以点第二项能打开第二项。
        val doc = "# 备注\n甲\n\n# 备注\n乙\n"
        val items = doc.toSectionUi()

        assertEquals(2, items.size)
        assertEquals(listOf(1, 4), items.map { it.startLine })
        assertEquals(setOf("备注"), items.map { it.title }.toSet())
    }

    @Test
    fun `chars counts the section body for the outline badge`() {
        val doc = "# 标题\n甲乙丙\n"
        val items = doc.toSectionUi()

        assertEquals(1, items.size)
        // 至少要把正文三个字算进去；具体是否含标题行由实现决定，这里只锁下界，
        // 避免把「字数」的语义绑死在一个实现细节上。
        assertTrue("chars 应把正文算进来，实际=${items[0].chars}", items[0].chars >= 3)
    }

    @Test
    fun `malformed content degrades to empty outline instead of throwing`() {
        // 整页编辑时用户可能删到只剩一半标题，此时目录不能崩，也不能挡住整页保存。
        assertTrue("".toSectionUi().isEmpty())
        assertTrue("没有标题的正文".toSectionUi().isEmpty())
        // 标题为空（只有 # 号）也不应产生条目
        assertTrue(AgentMemoryDocument.sections("# \n正文\n").isEmpty())
    }
}
