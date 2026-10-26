package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellTextTest {

    @Test
    fun singleQuotesAreEscapedSoTheTextCannotEscapeTheShell() {
        // 这是注入尝试：闭合引号后接一条命令。
        assertEquals("'a'\\''; rm -rf /'", RootShellText.shellQuote("a'; rm -rf /"))
    }

    @Test
    fun dollarsAndBackticksStayLiteralInsideSingleQuotes() {
        assertEquals("'\$HOME `id`'", RootShellText.shellQuote("\$HOME `id`"))
    }

    @Test
    fun spacesBecomePercentS() {
        assertEquals("hello%sworld", RootShellText.encodeInputText("hello world"))
        assertEquals("a%sb%sc", RootShellText.encodeInputText("a b c"))
    }

    @Test
    fun asciiPrintableRejectsControlAndNonAscii() {
        assertTrue(RootShellText.isAsciiPrintable("abc 123 !@#"))
        assertFalse(RootShellText.isAsciiPrintable("中文"))
        assertFalse(RootShellText.isAsciiPrintable("line\nbreak"))
        assertFalse(RootShellText.isAsciiPrintable(""))
        // Emoji 也要走粘贴通路（代理层用 UTF-16，会落到代理对）。
        assertFalse(RootShellText.isAsciiPrintable("🙂"))
    }
}
