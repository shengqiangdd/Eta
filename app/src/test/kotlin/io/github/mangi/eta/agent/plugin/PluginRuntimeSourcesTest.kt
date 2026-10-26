package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 来源记录与「当前磁盘上装着什么」的一致性。
 *
 * 守的是一条真机上踩到的缺陷：回滚到 v1.0.0 之后，详情仍然显示 v1.1.0 的
 * 包摘要与安装时间 —— 数据本身是真的，但属于另一版，用户拿去核对包会发现对不上。
 *
 * 三条不变式：
 * 1. 目录安装没有原始 zip，不能拿「目录内容摘要」冒充包摘要；
 * 2. 回滚后来源记录的版本/时间要跟着变，摘要必须清空（留底里没有原始 zip）；
 * 3. 已经写坏的旧记录（版本对不上）会在读取时自愈成「未记录」，而不是一直骗人。
 *
 * 用纯文件系统 + 内存启用状态跑（不用 Robolectric）：后者的 native runtime
 * 在 Linux/aarch64 上起不来，会把这些用例变成"环境失败"，等于没测。
 */
class PluginRuntimeSourcesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var runtime: PluginRuntime

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    /** 只活在内存里：这些用例关心的是文件，不是 SharedPreferences。 */
    private class MemoryEnabledState : PluginEnabledState {
        private val disabled = mutableSetOf<String>()
        override fun isEnabled(pluginId: String): Boolean = pluginId !in disabled
        override fun setEnabled(pluginId: String, enabled: Boolean) {
            if (enabled) disabled -= pluginId else disabled += pluginId
        }
        override fun forget(pluginId: String) {
            disabled -= pluginId
        }
    }

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        runtime = PluginRuntime(filesDir, MemoryEnabledState(), NoOpLogger)
    }

    private fun manifest(id: String = ID, version: String): String = JSONObject().apply {
        put("id", id)
        put("version", version)
        put("name", "来源自测插件")
        put("prompt", "自测用")
    }.toString()

    /** 写一个可直接安装的插件目录（目录安装路径）。 */
    private fun pluginDir(version: String): File {
        val dir = temporary.newFolder("src-$version")
        File(dir, "eta-plugin.json").writeText(manifest(version = version))
        return dir
    }

    private fun pluginZip(version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("eta-plugin.json"))
            zip.write(manifest(version = version).encodeToByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** 链接安装用的来源描述：地址与主机是这一个插件"从哪来"的凭据。 */
    private fun linkSource(version: String) = PluginSourceStore.Source(
        kind = PluginSourceStore.KIND_LINK,
        location = "http://127.0.0.1:8098/demo-$version.zip",
        host = "127.0.0.1",
        sha256 = "",
        version = "",
        installedAtMillis = 0L,
    )

    private fun installZip(version: String, replace: Boolean = false) = runtime.installZip(
        openStream = { ByteArrayInputStream(pluginZip(version)) },
        replaceExisting = replace,
        expectedReplacementId = if (replace) ID else null,
        expectedArchiveSha256 = null,
        source = linkSource(version),
    )

    private fun sourceRecord(): PluginSourceStore.Source? =
        PluginSourceStore(File(filesDir, "plugins")).get(ID)

    @Test
    fun `directory install does not pass off a directory digest as the package digest`() {
        val result = runtime.installPath(
            path = pluginDir("1.0.0"),
            replaceExisting = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
        assertTrue("expected success but was $result", result is PluginPackageInstaller.Result.Installed)

        val source = sourceRecord()
        assertNotNull(source)
        assertEquals("1.0.0", source?.version)
        // 目录内容摘要 ≠ 用户手里那份 zip 的 SHA-256，宁可不记。
        assertEquals("", source?.sha256)
        assertTrue(source!!.installedAtMillis > 0L)
    }

    @Test
    fun `zip install records the archive digest`() {
        assertTrue(installZip("1.1.0") is PluginPackageInstaller.Result.Installed)
        val source = sourceRecord()
        assertEquals(64, source?.sha256?.length)
        assertEquals("1.1.0", source?.version)
    }

    @Test
    fun `rollback rewrites the source record and drops the other version's digest`() {
        // v1.0.0 目录安装 → v1.1.0 zip 覆盖（这一版被留底）→ 回滚。
        runtime.installPath(pluginDir("1.0.0"), false, null, null)
        installZip("1.1.0", replace = true)
        assertEquals("1.1.0", sourceRecord()?.version)
        assertEquals(64, sourceRecord()?.sha256?.length)

        val rolledBack = runtime.rollback(ID)
        assertTrue("expected success but was $rolledBack", rolledBack is PluginPackageInstaller.Result.Installed)

        val source = sourceRecord()
        assertEquals("1.0.0", source?.version)
        // 留底目录里没有原始 zip：旧的 1.1.0 摘要必须清掉，否则详情在说谎。
        assertEquals("", source?.sha256)
        // 地址不变：回滚还是同一个来源，用户仍然可以「从来源更新」。
        assertEquals("http://127.0.0.1:8098/demo-1.1.0.zip", source?.location)
        // 回滚是一次真实安装，时间要更新成这一次。
        assertTrue(source!!.installedAtMillis > 0L)
        assertEquals("1.0.0", runtime.allInstalled().first { it.id == ID }.version)
    }

    @Test
    fun `rollback keeps a plugin that never had a source record without one`() {
        installZip("1.0.0")
        PluginSourceStore(File(filesDir, "plugins")).forget(ID)
        installZip("1.1.0", replace = true)
        PluginSourceStore(File(filesDir, "plugins")).forget(ID)

        assertTrue(runtime.rollback(ID) is PluginPackageInstaller.Result.Installed)
        // 不凭空造一条来源：谁给的这件事没有记录，就不能编。
        assertEquals(null, sourceRecord())
    }

    @Test
    fun `stale record from before the fix heals to 未记录 on read`() {
        installZip("1.0.0")
        // 模拟 fix19 留下的坏记录：版本写的是 1.1.0，摘要/时间也是 1.1.0 那次的。
        val store = PluginSourceStore(File(filesDir, "plugins"))
        store.record(
            ID,
            PluginSourceStore.Source(
                kind = PluginSourceStore.KIND_LINK,
                location = "http://127.0.0.1:8098/demo.zip",
                host = "127.0.0.1",
                sha256 = "521273b5432d3ab1",
                version = "1.1.0",
                installedAtMillis = 1_700_000_000_000L,
            ),
        )

        val healed = store.reconcile(mapOf(ID to "1.0.0"))

        assertEquals("1.0.0", healed[ID]?.version)
        assertEquals("", healed[ID]?.sha256)
        assertEquals(0L, healed[ID]?.installedAtMillis)
        // 地址仍然有效，不能被自愈一起清掉：来源图的是「从哪来」。
        assertEquals("http://127.0.0.1:8098/demo.zip", healed[ID]?.location)
        // 自愈要落盘，否则每次读都得再算一遍，别处读到的还是错的。
        assertEquals("1.0.0", PluginSourceStore(File(filesDir, "plugins")).get(ID)?.version)
    }

    @Test
    fun `enabled state still requires an installed plugin`() {
        assertEquals(false, runtime.setEnabled(ID, false))
        installZip("1.0.0")
        assertTrue(runtime.setEnabled(ID, false))
        assertEquals(false, runtime.isEnabled(ID))
        // 停用后 list() 不返回它，但盘上仍然装着（详情/回滚还能用）。
        assertTrue(runtime.list().isEmpty())
        assertEquals(1, runtime.allInstalled().size)
        assertTrue(runtime.setEnabled(ID, true))
        assertEquals(listOf(ID), runtime.list().map { it.id })
    }

    private companion object {
        const val ID = "source-self-test"
    }
}
