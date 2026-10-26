package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIKE 元字符转义。
 *
 * 这条不转义就会出「搜什么都命中」的假结果：`%` 变通配、`_` 变单字通配。
 * 纯 JVM 测试（不碰 Robolectric），所以在本机也能跑。
 */
class LikePatternEscapeTest {
    @Test
    fun plainTextIsUntouched() {
        assertEquals("工作区", escapeLikePattern("工作区"))
        assertEquals("eta search", escapeLikePattern("eta search"))
    }

    @Test
    fun percentAndUnderscoreAreEscaped() {
        assertEquals("100\\%", escapeLikePattern("100%"))
        assertEquals("foo\\_bar", escapeLikePattern("foo_bar"))
        assertEquals("\\%\\_", escapeLikePattern("%_"))
    }

    @Test
    fun backslashItselfIsEscaped() {
        // 反斜杠既是 LIKE 的转义符本身，也要转义，否则会被当成「转义下一个字符」。
        assertEquals("C:\\\\tmp", escapeLikePattern("C:\\tmp"))
        assertEquals("\\\\", escapeLikePattern("\\"))
    }

    @Test
    fun escapingIsIdempotentForNonMetaCharacters() {
        assertEquals("abc123", escapeLikePattern("abc123"))
        assertEquals("", escapeLikePattern(""))
    }
}
