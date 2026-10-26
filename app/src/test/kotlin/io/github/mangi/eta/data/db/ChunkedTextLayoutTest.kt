package io.github.mangi.eta.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分块增量写决策的纯函数测试：不起库、不碰 Android。 */
class ChunkedTextLayoutTest {

    @Test
    fun `chunkEnd 常规切分`() {
        val text = "x".repeat(ChunkedTextLayout.CHUNK_CHARS * 2 + 5)
        assertEquals(ChunkedTextLayout.CHUNK_CHARS, ChunkedTextLayout.chunkEnd(text, 0))
        assertEquals(ChunkedTextLayout.CHUNK_CHARS * 2, ChunkedTextLayout.chunkEnd(text, ChunkedTextLayout.CHUNK_CHARS))
        assertEquals(text.length, ChunkedTextLayout.chunkEnd(text, ChunkedTextLayout.CHUNK_CHARS * 2))
        assertEquals(text.length, ChunkedTextLayout.chunkEnd(text, text.length))
    }

    @Test
    fun `chunkEnd 不在代理对中间切开`() {
        val emoji = "😀"
        val text = "a".repeat(ChunkedTextLayout.CHUNK_CHARS - 1) + emoji + "b".repeat(10)
        // 高代理项正好在 CHUNK_CHARS-1：块尾要退一格，让代理对整体留在下一块
        assertEquals(ChunkedTextLayout.CHUNK_CHARS - 1, ChunkedTextLayout.chunkEnd(text, 0))
        // 退格后从代理对开头继续切，不丢字
        val boundary = ChunkedTextLayout.CHUNK_CHARS - 1
        val secondEnd = ChunkedTextLayout.chunkEnd(text, boundary)
        val pieces = listOf(text.substring(0, boundary), text.substring(boundary, secondEnd))
        assertEquals("切分后要能拼回原文", text, pieces.joinToString(""))
        assertTrue("任何一块都不为空", pieces.all { it.isNotEmpty() })
    }

    @Test
    fun `chunkEnd 只按上限切分不误伤普通字符`() {
        val text = "a".repeat(ChunkedTextLayout.CHUNK_CHARS + 1)
        assertEquals(ChunkedTextLayout.CHUNK_CHARS, ChunkedTextLayout.chunkEnd(text, 0))
        assertEquals(text.length, ChunkedTextLayout.chunkEnd(text, ChunkedTextLayout.CHUNK_CHARS))
    }

    @Test
    fun `matches 逐字符比较`() {
        val text = "abcdef"
        assertTrue(ChunkedTextLayout.matches(text, 0, "abc"))
        assertTrue(ChunkedTextLayout.matches(text, 3, "def"))
        assertFalse("内容不同应判否", ChunkedTextLayout.matches(text, 0, "abd"))
        assertFalse("越界应判否", ChunkedTextLayout.matches(text, 4, "def"))
        assertTrue("空块视为匹配", ChunkedTextLayout.matches(text, 6, ""))
    }

    @Test
    fun `plan 内容一致时判定不写库`() {
        val plan = ChunkedTextLayout.plan(true, 10, listOf(4, 4, 2), 10)
        assertTrue(plan is ChunkedTextLayout.Plan.Unchanged)
        assertEquals(3, (plan as ChunkedTextLayout.Plan.Unchanged).chunkCount)
        assertEquals(10, plan.length)
    }

    @Test
    fun `plan 纯追加时只重写最后一块`() {
        val plan = ChunkedTextLayout.plan(true, 10, listOf(4, 4, 2), 25)
        assertTrue(plan is ChunkedTextLayout.Plan.TailWrite)
        val rewrite = (plan as ChunkedTextLayout.Plan.TailWrite).rewrite
        assertEquals("从第 2 块（0 基）开始重写", 2, rewrite.chunkIndex)
        assertEquals("重写起点是最后一块的起始偏移", 8, rewrite.startOffset)
    }

    @Test
    fun `commonPrefixLength 定位块内第一处不同`() {
        assertEquals(3, ChunkedTextLayout.commonPrefixLength("abcdef", 0, "abcXY"))
        assertEquals(0, ChunkedTextLayout.commonPrefixLength("abcdef", 0, "Xbc"))
        assertEquals(6, ChunkedTextLayout.commonPrefixLength("abcdef", 0, "abcdef"))
        assertEquals("新文本比块短时只比到末尾", 2, ChunkedTextLayout.commonPrefixLength("ab", 0, "abcdef"))
        assertEquals("越界起点按 0 处理", 0, ChunkedTextLayout.commonPrefixLength("ab", 9, "abc"))
    }

    @Test
    fun `plan JSON 封口可丢弃时从封口所在块重写`() {
        // 旧内容三块共 10 字符，新内容只是在最后一块的位置「把 `]` 换成新元素 + `]`」；
        // 前两块（各 4 字符）仍可复用，第三块（含封口）必须整块重写。
        val plan = ChunkedTextLayout.plan(
            prefixMatched = false,
            prefixLength = 8,
            chunkLengths = listOf(4, 4),
            textLength = 30,
            dropClosingBracket = true,
        )
        assertTrue("应判为尾部重写", plan is ChunkedTextLayout.Plan.TailWrite)
        plan as ChunkedTextLayout.Plan.TailWrite
        assertEquals("从封口所在块（下标 2）开始重写", 2, plan.rewrite.chunkIndex)
        assertEquals("重写起点就是前缀末尾", 8, plan.rewrite.startOffset)
    }

    @Test
    fun `plan 有封口标记但新文本没变长时整段重写`() {
        assertTrue(
            ChunkedTextLayout.plan(
                prefixMatched = false,
                prefixLength = 8,
                chunkLengths = listOf(4, 4),
                textLength = 8,
                dropClosingBracket = true,
            ) is ChunkedTextLayout.Plan.FullWrite,
        )
    }

    @Test
    fun `plan 没有封口标记时比对失败整段重写`() {
        assertTrue(
            ChunkedTextLayout.plan(
                prefixMatched = false,
                prefixLength = 8,
                chunkLengths = listOf(4, 4),
                textLength = 30,
                dropClosingBracket = false,
            ) is ChunkedTextLayout.Plan.FullWrite,
        )
    }

    @Test
    fun `plan 比对失败必须整段重写`() {
        assertTrue(ChunkedTextLayout.plan(false, 0, emptyList(), 100) is ChunkedTextLayout.Plan.FullWrite)
        assertTrue(ChunkedTextLayout.plan(false, 10, listOf(4, 6), 100) is ChunkedTextLayout.Plan.FullWrite)
    }

    @Test
    fun `plan 变短或没有分块时整段重写`() {
        assertTrue(ChunkedTextLayout.plan(true, 10, listOf(4, 4, 2), 9) is ChunkedTextLayout.Plan.FullWrite)
        assertTrue(ChunkedTextLayout.plan(true, 0, emptyList(), 9) is ChunkedTextLayout.Plan.FullWrite)
    }

    @Test
    fun `plan 长度自相矛盾时保守整段重写`() {
        // 块长度之和与比对长度不符：说明读到的分块有缺口，宁可重写
        assertTrue(ChunkedTextLayout.plan(true, 10, listOf(4, 4, 1), 30) is ChunkedTextLayout.Plan.FullWrite)
    }

    @Test
    fun `reference 拼装格式稳定`() {
        assertEquals("@eta:chunks:v1:3:10", ChunkedTextLayout.reference("@eta:chunks:v1:", 3, 10))
    }
}
