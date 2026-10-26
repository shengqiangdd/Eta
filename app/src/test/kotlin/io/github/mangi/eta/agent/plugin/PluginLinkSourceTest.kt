package io.github.mangi.eta.agent.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接校验规则。
 *
 * 这些用例守的是「链接来自外部」这个前提：粘贴进来的东西可能是 `file://`、
 * 可能是带凭据的回环地址、也可能是超长字符串。校验只负责挡掉"根本不是
 * 可下载地址"的输入；真正的内容安全在安装器（清单解析、路径校验、体积上限）。
 */
class PluginLinkSourceTest {

    @Test
    fun `https link is accepted and marked secure`() {
        val result = PluginLinkSource.validate("https://example.com/plugins/demo.zip")
        assertTrue(result is PluginLinkSource.Validation.Valid)
        result as PluginLinkSource.Validation.Valid
        assertEquals("example.com", result.host)
        assertTrue(result.secure)
    }

    @Test
    fun `http link is accepted but marked insecure`() {
        val result = PluginLinkSource.validate("http://127.0.0.1:8080/demo.zip")
        assertTrue(result is PluginLinkSource.Validation.Valid)
        result as PluginLinkSource.Validation.Valid
        assertEquals("127.0.0.1", result.host)
        assertEquals(false, result.secure)
    }

    @Test
    fun `non http scheme is rejected`() {
        val result = PluginLinkSource.validate("file:///sdcard/Download/demo.zip")
        assertTrue(result is PluginLinkSource.Validation.Invalid)
        assertEquals("LINK_UNSUPPORTED_SCHEME", (result as PluginLinkSource.Validation.Invalid).code)
    }

    @Test
    fun `link without host is rejected`() {
        val result = PluginLinkSource.validate("https:///demo.zip")
        assertTrue(result is PluginLinkSource.Validation.Invalid)
        assertEquals("LINK_NO_HOST", (result as PluginLinkSource.Validation.Invalid).code)
    }

    @Test
    fun `credentials inside link are rejected`() {
        val result = PluginLinkSource.validate("https://user:secret@example.com/demo.zip")
        assertTrue(result is PluginLinkSource.Validation.Invalid)
        assertEquals("LINK_USER_INFO", (result as PluginLinkSource.Validation.Invalid).code)
    }

    @Test
    fun `blank link is rejected`() {
        val result = PluginLinkSource.validate("   ")
        assertTrue(result is PluginLinkSource.Validation.Invalid)
        assertEquals("LINK_EMPTY", (result as PluginLinkSource.Validation.Invalid).code)
    }

    @Test
    fun `over long link is rejected`() {
        val result = PluginLinkSource.validate("https://example.com/" + "a".repeat(PluginLinkSource.MAX_URL_LENGTH))
        assertTrue(result is PluginLinkSource.Validation.Invalid)
        assertEquals("LINK_TOO_LONG", (result as PluginLinkSource.Validation.Invalid).code)
    }
}
