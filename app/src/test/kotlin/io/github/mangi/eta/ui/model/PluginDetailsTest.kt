package io.github.mangi.eta.ui.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件详情的时间与摘要格式化。
 *
 * 守的是「没有值」这件事：本地导入没有包摘要、老记录没有安装时间，
 * 界面必须说"未记录"，不能显示 1970 年或一串空白。
 */
class PluginDetailsTest {

    private val fixedMillis = Instant.parse("2026-10-21T07:15:00Z").toEpochMilli()

    @Test
    fun `missing timestamp is null not epoch`() {
        assertNull(PluginDetails.formatInstalledAt(0L))
        assertNull(PluginDetails.formatInstalledAt(-1L))
    }

    @Test
    fun `timestamp respects the given zone`() {
        assertEquals("2026-10-21 07:15", PluginDetails.formatInstalledAt(fixedMillis, ZoneId.of("UTC")))
        assertEquals("2026-10-21 15:15", PluginDetails.formatInstalledAt(fixedMillis, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `digest is shortened only when longer than the limit`() {
        val full = "a".repeat(64)
        assertEquals(16, PluginDetails.shortDigest(full)!!.length)
        assertEquals("abc123", PluginDetails.shortDigest("abc123"))
        assertEquals("abc123", PluginDetails.shortDigest("  abc123  "))
    }

    @Test
    fun `blank digest is null`() {
        assertNull(PluginDetails.shortDigest(""))
        assertNull(PluginDetails.shortDigest("   "))
    }

    @Test
    fun `detail source uses the bare host so the label is not doubled`() {
        // 列表行的文案本身带「来源：」前缀；详情弹窗已经有标签，必须只用主机名。
        assertEquals("127.0.0.1", PluginDetails.sourceForDetails("127.0.0.1", "来源：127.0.0.1"))
    }

    @Test
    fun `detail source falls back to the label for local imports`() {
        assertEquals("从本地文件导入", PluginDetails.sourceForDetails("", "从本地文件导入"))
    }

    @Test
    fun `detail source is null when nothing was recorded`() {
        assertNull(PluginDetails.sourceForDetails("", null))
        assertNull(PluginDetails.sourceForDetails("  ", "   "))
    }

    @Test
    fun `rollback is offered only when the backup is a different version`() {
        assertTrue(PluginDetails.canRollback("1.3.0", "1.2.0"))
        // 留底与当前同版本：点了没有任何变化，不该给入口。
        assertFalse(PluginDetails.canRollback("1.3.0", "1.3.0"))
        assertFalse(PluginDetails.canRollback("1.3.0", " 1.3.0 "))
        assertFalse(PluginDetails.canRollback("1.3.0", null))
        assertFalse(PluginDetails.canRollback("1.3.0", "  "))
    }

    @Test
    fun `capability list keeps everything when it fits`() {
        val slice = PluginDetails.sliceForDetails(listOf("a", "b"), limit = 6)
        assertEquals(listOf("a", "b"), slice.shown)
        assertEquals(0, slice.hidden)
    }

    @Test
    fun `capability list reports the remainder instead of hiding it`() {
        val slice = PluginDetails.sliceForDetails((1..8).map { "t$it" }, limit = 6)
        assertEquals(6, slice.shown.size)
        assertEquals(2, slice.hidden)
    }

    @Test
    fun `non-positive limit does not pretend the list is empty`() {
        val slice = PluginDetails.sliceForDetails(listOf("a", "b", "c"), limit = 0)
        assertEquals(0, slice.shown.size)
        assertEquals(3, slice.hidden)
    }

    @Test
    fun `digest belongs to the installed version only when versions agree`() {
        assertTrue(PluginDetails.recordAppliesToVersion("1.0.0", "1.0.0"))
        assertTrue(PluginDetails.recordAppliesToVersion(" 1.0.0 ", "1.0.0"))
        // 记录没写版本（老数据）时不能一口咬定它在说假话，按可用处理。
        assertTrue(PluginDetails.recordAppliesToVersion("", "1.0.0"))
        // 回滚过的插件：记录还是上一版，摘要/时间必须按「未记录」显示。
        assertFalse(PluginDetails.recordAppliesToVersion("1.1.0", "1.0.0"))
    }
}
