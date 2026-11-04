package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQuoteTest {

    private fun quote(text: String, author: String = "Eta") =
        ComposerQuoteUi(messageId = "m-1", author = author, excerpt = text)

    @Test
    fun `多行引用逐行加引用符并保留作者行`() {
        val prefix = quote("第一行\n\n第三行").toPromptPrefix()
        assertEquals("> **Eta**\n> 第一行\n>\n> 第三行\n\n", prefix)
    }

    @Test
    fun `作者为空时不写空的加粗行`() {
        val prefix = quote("正文", author = "  ").toPromptPrefix()
        assertEquals("> 正文\n\n", prefix)
    }

    @Test
    fun `引用与正文之间留一个空行`() {
        val merged = composeQuotedMessage(quote("被引用的内容"), "我的问题")
        assertEquals("> **Eta**\n> 被引用的内容\n\n我的问题", merged)
    }

    @Test
    fun `没有引用时正文原样返回`() {
        assertEquals("我的问题", composeQuotedMessage(null, "我的问题"))
        assertEquals("我的问题", composeQuotedMessage(quote("   "), "我的问题"))
        assertTrue(quote("   ").isBlank)
    }

    @Test
    fun `超长正文截断到上限并补省略号`() {
        val long = "字".repeat(COMPOSER_QUOTE_MAX_CHARS + 50)
        val excerpt = composerQuoteExcerpt(long)
        assertEquals(COMPOSER_QUOTE_MAX_CHARS + 1, excerpt.length)
        assertTrue(excerpt.endsWith("…"))
        assertFalse(excerpt.contains("\n"))
    }

    @Test
    fun `恰好等于上限时不补省略号`() {
        val exact = "字".repeat(COMPOSER_QUOTE_MAX_CHARS)
        assertEquals(exact, composerQuoteExcerpt(exact))
    }

    @Test
    fun `不在代理对中间截断 emoji`() {
        // 每个 emoji 占两个 char：前面垫一个 ASCII 字符，上限正好落在代理对中间。
        val text = "a" + "🙂".repeat(400)
        val excerpt = composerQuoteExcerpt(text)
        val body = excerpt.removeSuffix("…")
        assertTrue(excerpt.endsWith("…"))
        // 切点落在代理对中间时宁可少带一个字符，也不能留半个字符。
        assertEquals(COMPOSER_QUOTE_MAX_CHARS, excerpt.length)
        assertTrue(body.length <= COMPOSER_QUOTE_MAX_CHARS)
        // 高低代理必须成对，否则渲染成替换字符，而且这段文字会原样发给模型。
        assertEquals(
            body.count { it.isHighSurrogate() },
            body.count { it.isLowSurrogate() },
        )
        assertFalse(body.last().isHighSurrogate())
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("正文", composerQuoteExcerpt("\n  正文 \n"))
    }
}
