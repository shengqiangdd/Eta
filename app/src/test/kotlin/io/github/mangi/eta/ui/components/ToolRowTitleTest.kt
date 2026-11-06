package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具折叠行标题的取值契约。
 *
 * 标题来自后端生成的参数预览：友好的摘要可以直接用，但多行 shell / SQL 会带换行和整段参数。
 * 原样当标题时第一行只剩半截语句（真机上出现过把 SQL 片段显示成「乱码」），
 * 所以这里只取首行、折叠空白、限长；完整命令仍在展开后的详情里。
 */
class ToolRowTitleTest {

    @Test
    fun keepsFriendlySummaryAsIs() {
        assertEquals("终端 · 执行命令 · Linux", toolRowTitle("终端 · 执行命令 · Linux", "终端"))
    }

    @Test
    fun takesOnlyTheFirstNonBlankLine() {
        assertEquals("Rest.", toolRowTitle("\n  Rest.\n1", "终端"))
    }

    @Test
    fun collapsesWhitespaceRuns() {
        assertEquals("select id, name", toolRowTitle("select   id,\tname", "终端"))
    }

    @Test
    fun fallsBackWhenPreviewIsBlank() {
        assertEquals("终端", toolRowTitle("   \n ", "终端"))
    }

    @Test
    fun truncatesLongPreview() {
        val title = toolRowTitle("a".repeat(200), "终端")
        assertEquals(61, title.length)
        assertEquals(true, title.endsWith("…"))
    }
}
