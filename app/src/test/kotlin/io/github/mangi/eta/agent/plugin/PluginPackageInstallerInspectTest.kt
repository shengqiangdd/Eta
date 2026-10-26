package io.github.mangi.eta.agent.plugin

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 链接安装的预览解析（[PluginPackageInstaller.inspectZip]）。
 *
 * 预览必须和真正的安装用同一套判据：如果预览说"可以装"、安装却失败，
 * 用户会以为下载坏了；反过来更糟——预览放过一个坏包，用户点确认后才报错。
 */
class PluginPackageInstallerInspectTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun installer() = PluginPackageInstaller(temporaryFolder.newFolder("plugins"))

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifest = """
        {
          "id": "demo_plugin",
          "version": "1.2.0",
          "name": "Demo Plugin",
          "description": "只用于测试",
          "mcp": [{"name": "demo", "transport": "http", "url": "https://example.com/mcp"}],
          "prompt": "这是提示词扩展"
        }
    """.trimIndent()

    @Test
    fun `valid package is reported with name version and channels`() {
        val bytes = zipOf(
            mapOf(
                "eta-plugin.json" to manifest,
                "README.md" to "# demo",
            ),
        )
        val result = installer().inspectZip { bytes.inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Ready)
        result as PluginPackageInstaller.Inspection.Ready
        assertEquals("demo_plugin", result.plugin.id)
        assertEquals("Demo Plugin", result.plugin.name)
        assertEquals("1.2.0", result.plugin.version)
        assertEquals(1, result.plugin.mcp.size)
        assertTrue(result.plugin.prompt.isNotBlank())
    }

    @Test
    fun `manifest nested in a directory is accepted like the installer does`() {
        val bytes = zipOf(mapOf("demo/eta-plugin.json" to manifest))
        val result = installer().inspectZip { bytes.inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Ready)
    }

    @Test
    fun `package without manifest is rejected`() {
        val bytes = zipOf(mapOf("README.md" to "# demo"))
        val result = installer().inspectZip { bytes.inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Rejected)
        assertEquals("MANIFEST_MISSING", (result as PluginPackageInstaller.Inspection.Rejected).code)
    }

    @Test
    fun `unsafe path is rejected before any write`() {
        val bytes = zipOf(
            mapOf(
                "eta-plugin.json" to manifest,
                "../escape.txt" to "bad",
            ),
        )
        val result = installer().inspectZip { bytes.inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Rejected)
        assertEquals("ARCHIVE_UNSAFE_PATH", (result as PluginPackageInstaller.Inspection.Rejected).code)
    }

    @Test
    fun `invalid manifest is rejected with manifest code`() {
        val bytes = zipOf(mapOf("eta-plugin.json" to """{"id": "Bad Id", "version": "1.0.0"}"""))
        val result = installer().inspectZip { bytes.inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Rejected)
        assertEquals("MANIFEST_BAD_ID", (result as PluginPackageInstaller.Inspection.Rejected).code)
    }

    @Test
    fun `broken bytes are rejected instead of throwing`() {
        val result = installer().inspectZip { byteArrayOf(1, 2, 3, 4).inputStream() }
        assertTrue(result is PluginPackageInstaller.Inspection.Rejected)
    }
}
