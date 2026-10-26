package io.github.mangi.eta.agent.plugin

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源记录（`files/plugins/.sources.json`）。
 *
 * 这份文件是「这个插件从哪来」的唯一凭据：界面上的来源标签、菜单里的「从来源更新」
 * 全靠它。所以用例要守住三件事：
 * 1. 写了能读回来（链接 / 本地路径两种）；
 * 2. 插件没了必须清掉残留（否则界面会给一个不存在的插件提供「更新」）；
 * 3. 文件损坏时**整张表当空**而不是崩溃 —— 来源只是说明，不能因为它坏了就打不开插件页。
 */
class PluginSourceStoreTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("eta-plugin-sources").toFile().also { it.deleteOnExit() }

    private fun linkSource(url: String = "https://example.com/demo.zip", host: String = "example.com") =
        PluginSourceStore.Source(
            kind = PluginSourceStore.KIND_LINK,
            location = url,
            host = host,
            sha256 = "abc123",
            version = "1.0.0",
            installedAtMillis = 1_700_000_000_000L,
        )

    @Test
    fun `recorded link source is readable`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("demo", linkSource())

        val loaded = PluginSourceStore(root).get("demo")
        assertEquals(PluginSourceStore.KIND_LINK, loaded?.kind)
        assertEquals("https://example.com/demo.zip", loaded?.location)
        assertEquals("example.com", loaded?.host)
        assertEquals("abc123", loaded?.sha256)
        assertEquals("1.0.0", loaded?.version)
        assertEquals(1_700_000_000_000L, loaded?.installedAtMillis)
        assertTrue(loaded!!.isLink)
    }

    @Test
    fun `path source is not a link`() {
        val root = tempRoot()
        PluginSourceStore(root).record(
            "local",
            PluginSourceStore.Source(
                kind = PluginSourceStore.KIND_PATH,
                location = "/sdcard/Download/local.zip",
                host = "",
                sha256 = "",
                version = "",
                installedAtMillis = 0L,
            ),
        )
        val loaded = PluginSourceStore(root).get("local")
        assertEquals(PluginSourceStore.KIND_PATH, loaded?.kind)
        assertFalse(loaded!!.isLink)
    }

    @Test
    fun `record overwrites the previous entry`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("demo", linkSource())
        store.record(
            "demo",
            PluginSourceStore.Source(
                kind = PluginSourceStore.KIND_LINK,
                location = "https://cdn.example.com/v2/demo.zip",
                host = "cdn.example.com",
                sha256 = "def456",
                version = "1.1.0",
                installedAtMillis = 1_700_000_100_000L,
            ),
        )
        assertEquals(1, store.load().size)
        assertEquals("1.1.0", store.get("demo")?.version)
    }

    @Test
    fun `prune drops entries whose plugin is gone`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("keep", linkSource())
        store.record("gone", linkSource(url = "https://example.com/gone.zip"))

        store.prune(setOf("keep"))

        val remaining = store.load()
        assertEquals(setOf("keep"), remaining.keys)
        assertNull(store.get("gone"))
    }

    @Test
    fun `prune with all plugins missing clears the store`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("a", linkSource())
        store.record("b", linkSource(url = "https://example.com/b.zip"))
        store.prune(emptySet())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `forget removes one entry and keeps the others`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("a", linkSource())
        store.record("b", linkSource(url = "https://example.com/b.zip"))
        store.forget("a")
        assertEquals(setOf("b"), store.load().keys)
        // 再忘一次不应抛异常：卸载与 prune 可能都调用它。
        store.forget("a")
        assertEquals(setOf("b"), store.load().keys)
    }

    @Test
    fun `broken json is treated as empty instead of crashing`() {
        val root = tempRoot()
        root.mkdirs()
        File(root, PluginSourceStore.STORE_FILE).writeText("{ this is not json")
        val store = PluginSourceStore(root)
        assertTrue(store.load().isEmpty())
        assertNull(store.get("demo"))
        // 坏文件之后仍要能写入，否则一次损坏就永久失去来源记录。
        store.record("demo", linkSource())
        assertEquals("1.0.0", PluginSourceStore(root).get("demo")?.version)
    }

    @Test
    fun `unknown kind entries are ignored`() {
        val root = tempRoot()
        root.mkdirs()
        File(root, PluginSourceStore.STORE_FILE).writeText(
            """{"demo":{"kind":"mystery","location":"x","host":"","sha256":"","version":"1.0","installedAt":0}}""",
        )
        assertTrue(PluginSourceStore(root).load().isEmpty())
    }

    @Test
    fun `missing store file is empty`() {
        assertTrue(PluginSourceStore(tempRoot()).load().isEmpty())
    }

    @Test
    fun `temporary file is not left behind`() {
        val root = tempRoot()
        PluginSourceStore(root).record("demo", linkSource())
        assertTrue(File(root, PluginSourceStore.STORE_FILE).isFile)
        // 写临时文件再替换：replace 之后临时文件不能残留（残留会被看成"半份数据"）。
        assertFalse(File(root, "${PluginSourceStore.STORE_FILE}.tmp").exists())
    }

    @Test
    fun `reconcile keeps a record that matches the installed version`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("demo", linkSource())

        val reconciled = store.reconcile(mapOf("demo" to "1.0.0"))

        assertEquals("abc123", reconciled["demo"]?.sha256)
        assertEquals(1_700_000_000_000L, reconciled["demo"]?.installedAtMillis)
    }

    @Test
    fun `reconcile voids digest and time recorded for another version`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        // 记录说 1.1.0（摘要/时间都属于 1.1.0），磁盘上却是 1.0.0 —— 正是回滚留下的样子。
        store.record("demo", linkSource().copy(version = "1.1.0", sha256 = "def456"))

        val reconciled = store.reconcile(mapOf("demo" to "1.0.0"))

        assertEquals("1.0.0", reconciled["demo"]?.version)
        assertEquals("", reconciled["demo"]?.sha256)
        assertEquals(0L, reconciled["demo"]?.installedAtMillis)
        // 来源地址与主机与版本无关，必须保留：用户还要靠它「从来源更新」。
        assertEquals("https://example.com/demo.zip", reconciled["demo"]?.location)
        assertEquals("example.com", reconciled["demo"]?.host)
        // 自愈要落盘，否则别的读路径（CLI、下次启动）拿到的还是旧值。
        assertEquals("1.0.0", PluginSourceStore(root).get("demo")?.version)
        assertEquals("", PluginSourceStore(root).get("demo")?.sha256)
    }

    @Test
    fun `reconcile fills a blank version but keeps the digest`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        // 旧记录没写版本：说明它是那次安装写的，摘要仍然对应当前这一版。
        store.record("demo", linkSource().copy(version = "", sha256 = "abc123"))

        val reconciled = store.reconcile(mapOf("demo" to "2.0.0"))

        assertEquals("2.0.0", reconciled["demo"]?.version)
        assertEquals("abc123", reconciled["demo"]?.sha256)
        assertEquals(1_700_000_000_000L, reconciled["demo"]?.installedAtMillis)
    }

    @Test
    fun `reconcile drops records for plugins that are gone`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("keep", linkSource())
        store.record("gone", linkSource(url = "https://example.com/gone.zip"))

        val reconciled = store.reconcile(mapOf("keep" to "1.0.0"))

        assertEquals(setOf("keep"), reconciled.keys)
        assertEquals(setOf("keep"), store.load().keys)
    }

    @Test
    fun `reconcile is idempotent and leaves a clean store untouched`() {
        val root = tempRoot()
        val store = PluginSourceStore(root)
        store.record("demo", linkSource())
        val before = File(root, PluginSourceStore.STORE_FILE).readText()

        repeat(3) { store.reconcile(mapOf("demo" to "1.0.0")) }

        assertEquals(before, File(root, PluginSourceStore.STORE_FILE).readText())
    }

    @Test
    fun `reconcile on an empty store does not create the file`() {
        val root = tempRoot()
        assertTrue(PluginSourceStore(root).reconcile(mapOf("demo" to "1.0.0")).isEmpty())
        // 空表不写盘：没必要因为一次刷新就在插件目录里凭空多出一个文件。
        assertFalse(File(root, PluginSourceStore.STORE_FILE).exists())
    }
}
