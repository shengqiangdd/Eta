package io.github.mangi.eta.agent.plugin

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史留底（`files/plugin-history`）。
 *
 * 这是「更新坏了能退回去」的唯一凭据。多版本之后，用例要守的比单版本更多：
 * 1. 复制的是**完整目录**，不是只有 manifest；
 * 2. 每次留底是**追加**，不能像单版本时代那样整份覆盖（覆盖会让「退两版」永远做不到）；
 * 3. 同一版本重复安装只留最新一份（内容相同，回滚起来没有区别）；
 * 4. 旧单版本布局必须能就地迁移，迁移失败也不能把已有留底抹掉；
 * 5. 上限之外的旧版本要清掉，否则留底会无限长；
 * 6. 卸载后必须清干净（否则白占空间，还会被当成可回滚目标）；
 * 7. 元数据坏了能从目录名兜底，而不是整条留底消失或崩溃。
 */
class PluginHistoryStoreTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("eta-plugin-history").toFile().also { it.deleteOnExit() }

    private fun pluginDir(name: String = "demo", files: Map<String, String> = mapOf("eta-plugin.json" to "{}")): File {
        val dir = Files.createTempDirectory("eta-plugin-$name").toFile().also { it.deleteOnExit() }
        files.forEach { (path, content) ->
            val target = File(dir, path)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        return dir
    }

    @Test
    fun `backup keeps the whole directory`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        val source = pluginDir(files = mapOf("eta-plugin.json" to "{\"id\":\"demo\"}", "lib/tool.js" to "x"))
        assertTrue(store.remember("demo", source, "1.2.0", nowMillis = 1_700_000_000_000L))

        val entry = store.get("demo")
        assertNotNull(entry)
        assertEquals("1.2.0", entry!!.version)
        assertEquals(1_700_000_000_000L, entry.backedUpAtMillis)
        assertTrue(File(entry.payloadDir, "eta-plugin.json").isFile)
        assertTrue(File(entry.payloadDir, "lib/tool.js").isFile)
    }

    @Test
    fun `backups accumulate and list newest first`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        store.remember("demo", pluginDir(files = mapOf("v1.txt" to "1")), "1.0.0", nowMillis = 100L)
        store.remember("demo", pluginDir(files = mapOf("v2.txt" to "2")), "1.1.0", nowMillis = 200L)
        store.remember("demo", pluginDir(files = mapOf("v3.txt" to "3")), "1.2.0", nowMillis = 300L)

        val all = store.list("demo")
        assertEquals(listOf("1.2.0", "1.1.0", "1.0.0"), all.map { it.version })
        assertEquals("1.2.0", store.get("demo")!!.version)

        // 指定版本必须拿到自己那份目录，而不是「最新那份」——
        // 这正是单版本留底做不到的事。
        val oldest = store.getVersion("demo", "1.0.0")
        assertNotNull(oldest)
        assertTrue(File(oldest!!.payloadDir, "v1.txt").isFile)
        assertFalse(File(oldest.payloadDir, "v3.txt").exists())
        assertNull(store.getVersion("demo", "9.9.9"))
    }

    @Test
    fun `the same version keeps only the newest copy`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        store.remember("demo", pluginDir(files = mapOf("old.txt" to "1")), "1.0.0", nowMillis = 100L)
        store.remember("demo", pluginDir(files = mapOf("new.txt" to "2")), "1.0.0", nowMillis = 200L)

        val all = store.list("demo")
        assertEquals(1, all.size)
        assertEquals(200L, all[0].backedUpAtMillis)
        assertTrue(File(all[0].payloadDir, "new.txt").isFile)
        assertFalse(File(all[0].payloadDir, "old.txt").exists())
    }

    @Test
    fun `old versions beyond the limit are dropped`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root, maxVersions = 3)
        repeat(4) { index ->
            store.remember("demo", pluginDir(), "1.$index.0", nowMillis = 100L * (index + 1))
        }

        assertEquals(listOf("1.3.0", "1.2.0", "1.1.0"), store.list("demo").map { it.version })
        assertNull(store.getVersion("demo", "1.0.0"))
    }

    @Test
    fun `legacy single-version layout is migrated in place`() {
        val root = tempRoot()
        val legacy = File(root, "demo")
        File(legacy, "payload").mkdirs()
        File(legacy, "payload/eta-plugin.json").writeText("{\"id\":\"demo\"}")
        File(legacy, "meta.json").writeText(
            PluginHistoryStore.metaJson("1.2.0", 1_700_000_000_000L),
        )

        val store = PluginHistoryStore(root)
        val all = store.list("demo")
        assertEquals(1, all.size)
        assertEquals("1.2.0", all[0].version)
        assertEquals(1_700_000_000_000L, all[0].backedUpAtMillis)
        assertTrue(File(all[0].payloadDir, "eta-plugin.json").isFile)
        // 旧文件留着会变成「第二份」，下次读又被当成一条留底。
        assertFalse(File(legacy, "meta.json").exists())
        assertFalse(File(legacy, "payload").exists())
        // 迁移之后再追加，必须是两条而不是覆盖。
        store.remember("demo", pluginDir(), "1.3.0", nowMillis = 1_700_000_100_000L)
        assertEquals(listOf("1.3.0", "1.2.0"), store.list("demo").map { it.version })
    }

    @Test
    fun `a broken legacy meta keeps the old payload and blocks overwriting`() {
        val root = tempRoot()
        val legacy = File(root, "demo")
        File(legacy, "payload").mkdirs()
        File(legacy, "payload/keep.txt").writeText("keep")
        File(legacy, "meta.json").writeText("not json")

        val store = PluginHistoryStore(root)
        // 读不出元数据 = 没有可回滚版本（不能瞎猜版本号）。
        assertTrue(store.list("demo").isEmpty())
        // 但写入必须先被拒绝：否则覆盖式留底会把这段还活着的旧数据删掉。
        assertFalse(store.remember("demo", pluginDir(), "2.0.0", nowMillis = 100L))
        assertTrue(File(legacy, "payload/keep.txt").isFile)
    }

    @Test
    fun `a missing meta is recovered from the directory name`() {
        val root = tempRoot()
        val entryDir = File(root, "demo/v1.4.0~1700000000000")
        File(entryDir, "payload").mkdirs()
        File(entryDir, "payload/x.txt").writeText("x")

        val store = PluginHistoryStore(root)
        val all = store.list("demo")
        assertEquals(1, all.size)
        assertEquals("1.4.0", all[0].version)
        assertEquals(1_700_000_000_000L, all[0].backedUpAtMillis)
    }

    @Test
    fun `forget removes every version`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        store.remember("demo", pluginDir(), "1.0.0", nowMillis = 100L)
        store.remember("demo", pluginDir(), "1.1.0", nowMillis = 200L)

        assertTrue(store.forget("demo"))
        assertNull(store.get("demo"))
        assertTrue(store.list("demo").isEmpty())
        assertFalse(File(root, "demo").exists())
    }

    @Test
    fun `prune drops backups of uninstalled plugins only`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        store.remember("keep", pluginDir(), "1.0.0")
        store.remember("gone", pluginDir(), "2.0.0")

        store.prune(setOf("keep"))

        assertNotNull(store.get("keep"))
        assertNull(store.get("gone"))
    }

    @Test
    fun `blank version or missing source is rejected`() {
        val root = tempRoot()
        val store = PluginHistoryStore(root)
        assertFalse(store.remember("demo", pluginDir(), "  "))
        assertFalse(store.remember("demo", File(root, "nope"), "1.0.0"))
        assertNull(store.get("demo"))
    }

    @Test
    fun `broken metadata means no backup rather than a crash`() {
        assertNull(PluginHistoryStore.parseMeta("not json"))
        assertNull(PluginHistoryStore.parseMeta("{}"))
        assertNull(PluginHistoryStore.parseMeta("{\"version\":\"  \"}"))
        assertEquals(
            "1.4.0" to 1_700_000_000_000L,
            PluginHistoryStore.parseMeta("{\"version\":\"1.4.0\",\"backedUpAt\":1700000000000}"),
        )
    }
}
