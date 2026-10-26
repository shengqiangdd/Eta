package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChangeSummaryTest {

    @Test
    fun `single line replacement reports line range and counts`() {
        val delta = FileChangeSummary.between("a\nb\nc\n", "a\nB\nc\n")
        assertEquals(1, delta.added)
        assertEquals(1, delta.removed)
        assertEquals(2, delta.line)
        assertEquals(2, delta.lineEnd)
        assertEquals(3, delta.lineCountBefore)
        assertEquals(3, delta.lineCountAfter)
        assertTrue(delta.diff.contains("-b"))
        assertTrue(delta.diff.contains("+B"))
        // 头部把旧/新坐标都写清楚，否则删行（旧号）与加行（新号）连读会像「行号倒退」
        assertTrue(delta.diff.contains("@@ -2,1 +2,1 @@"))
        assertFalse(delta.truncated)
    }

    @Test
    fun `insertion has no removed lines`() {
        val delta = FileChangeSummary.between("a\nc\n", "a\nb\nc\n")
        assertEquals(1, delta.added)
        assertEquals(0, delta.removed)
        assertEquals(2, delta.line)
        assertTrue(delta.diff.contains("+b"))
        // 纯插入：旧侧 0 行、新侧 1 行
        assertTrue(delta.diff.contains("@@ -2,0 +2,1 @@"))
    }

    @Test
    fun `deletion has no added lines`() {
        val delta = FileChangeSummary.between("a\nb\nc\n", "a\nc\n")
        assertEquals(0, delta.added)
        assertEquals(1, delta.removed)
        assertEquals(2, delta.line)
        assertTrue(delta.diff.contains("-b"))
    }

    @Test
    fun `identical text has no change position`() {
        val delta = FileChangeSummary.between("same\n", "same\n")
        assertEquals(0, delta.added)
        assertEquals(0, delta.removed)
        assertNull(delta.line)
        assertEquals("", delta.diff)
    }

    @Test
    fun `large change is truncated`() {
        val before = (1..100).joinToString("\n") { "old-$it" }
        val after = (1..100).joinToString("\n") { "new-$it" }
        val delta = FileChangeSummary.between(before, after, maxDiffLines = 10)
        assertTrue(delta.truncated)
        assertEquals(100, delta.added)
        assertEquals(100, delta.removed)
        assertTrue(delta.diff.lines().size <= 10)
    }

    @Test
    fun `scattered insertions do not inflate counts`() {
        val lines = (1..200).map { "line$it" }
        val edited = lines.toMutableList()
        edited.add(10, "inserted-a")
        edited.add(150, "inserted-b")
        val delta = FileChangeSummary.between(
            lines.joinToString("\n") + "\n",
            edited.joinToString("\n") + "\n",
        )
        // 只插了两行：旧实现会报成上百行增删（实测 +141 / -139）
        assertEquals(2, delta.added)
        assertEquals(0, delta.removed)
        assertEquals(2, delta.hunks)
        assertEquals(11, delta.line)
        assertTrue(delta.diff.contains("+inserted-a"))
        assertTrue(delta.diff.contains("+inserted-b"))
        // 两处变更之间用省略标记隔开，不能看起来像连续的一段
        assertTrue(delta.diff.contains("|~"))
        // 第二段不能带前导省略标记以外的错位：头部两条都要在
        assertEquals(2, Regex("@@ -").findAll(delta.diff).count())
        assertTrue(delta.diff.lines().size <= 20)
    }

    @Test
    fun `scattered edits report both hunks`() {
        val lines = (1..200).map { "line$it" }
        val edited = lines.toMutableList()
        edited[1] = "CHANGED-2"
        edited[150] = "CHANGED-151"
        val delta = FileChangeSummary.between(
            lines.joinToString("\n") + "\n",
            edited.joinToString("\n") + "\n",
        )
        assertEquals(2, delta.added)
        assertEquals(2, delta.removed)
        assertEquals(2, delta.hunks)
        assertEquals(2, delta.line)
        assertEquals(2, delta.lineEnd)
    }

    @Test
    fun `repetitive middle still reports exact counts`() {
        // 中间段几乎全是重复行：靠前后缀裁剪解决不了，必须靠真正的行级 diff
        val before = MutableList(3000) { "same" }
        before[9] = "alpha"
        before[2899] = "omega"
        val after = before.toMutableList()
        after[9] = "ALPHA"
        after[2899] = "OMEGA"
        val delta = FileChangeSummary.between(
            before.joinToString("\n") + "\n",
            after.joinToString("\n") + "\n",
        )
        assertEquals(2, delta.added)
        assertEquals(2, delta.removed)
        assertEquals(2, delta.hunks)
    }

    @Test
    fun `huge divergence falls back to whole region`() {
        val before = (1..600).joinToString("\n") { "old-$it" }
        val after = (1..600).joinToString("\n") { "brand-new-$it" }
        val delta = FileChangeSummary.between(before, after)
        assertEquals(600, delta.added)
        assertEquals(600, delta.removed)
        assertEquals(1, delta.hunks)
        assertTrue(delta.truncated)
    }

    @Test
    fun `line numbers never run backwards`() {
        // 加行写新号、上下文写旧号 → 连读会出现 184 后面跟 183；行号列必须单调
        val lines = (1..120).map { "line$it" }
        val edited = lines.toMutableList()
        edited.add(30, "added-line")
        edited.removeAt(100)
        val delta = FileChangeSummary.between(
            lines.joinToString("\n") + "\n",
            edited.joinToString("\n") + "\n",
        )
        val numbers = Regex("^\\s*(\\d+)\\|", RegexOption.MULTILINE)
            .findAll(delta.diff)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue(numbers.size >= 2)
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun `line count treats trailing newline as terminator`() {
        assertEquals(0, FileChangeSummary.lineCount(""))
        assertEquals(1, FileChangeSummary.lineCount("a"))
        assertEquals(1, FileChangeSummary.lineCount("a\n"))
        assertEquals(2, FileChangeSummary.lineCount("a\nb"))
    }

    @Test
    fun `offset maps to one based line`() {
        val text = "first\nsecond\nthird"
        assertEquals(1, FileChangeSummary.lineOfOffset(text, 0))
        assertEquals(2, FileChangeSummary.lineOfOffset(text, 6))
        assertEquals(3, FileChangeSummary.lineOfOffset(text, 13))
    }
}
