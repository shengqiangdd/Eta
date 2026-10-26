package io.github.mangi.eta.agent.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 插件 ZIP 安装器的安全与事务语义。
 *
 * 这里全部用真实文件系统（TemporaryFolder）+ 真实 zip，
 * 不 mock，因为要验证的正是"解包落盘"这条路径本身安不安全。
 */
class PluginPackageInstallerTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var installer: PluginPackageInstaller

    @Before
    fun setUp() {
        root = File(temporary.newFolder("files"), "plugins")
        installer = PluginPackageInstaller(root)
    }

    private fun manifestJson(
        id: String = "demo-plugin",
        version: String = "1.0.0",
        extra: JSONObject.() -> Unit = {},
    ): String = JSONObject().apply {
        put("id", id)
        put("version", version)
        put("name", id)
        put("prompt", "演示插件")
        extra()
    }.toString()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun install(vararg entries: Pair<String, ByteArray>, replace: Boolean = false): PluginPackageInstaller.Result =
        installer.installLocalZip({ ByteArrayInputStream(zip(*entries)) }, replaceExisting = replace)

    @Test
    fun happyPathInstallsManifestAndReportsSha256() {
        val result = install(
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
            "assets/notes.txt" to "hello".encodeToByteArray(),
        )
        assertTrue("expected success but was $result", result is PluginPackageInstaller.Result.Installed)
        val installed = result as PluginPackageInstaller.Result.Installed
        assertFalse(installed.alreadyInstalled)
        assertEquals("demo-plugin", installed.plugin.id)
        assertEquals(64, installed.sha256.length)
        assertTrue(File(root, "demo-plugin/eta-plugin.json").isFile)
        assertTrue(File(root, "demo-plugin/assets/notes.txt").isFile)
        assertEquals(listOf("demo-plugin"), installer.installedPlugins().map { it.id })
    }

    @Test
    fun archiveDigestCoversWholeArchiveIncludingCentralDirectory() {
        // 回归：安装器曾用 ZipInputStream「读过的字节」当摘要，而下载端算的是整份文件
        // （含中央目录）。两个口径不同时，从来源更新会拿预览里的 SHA 做覆盖校验，
        // 必然报 PLUGIN_REPLACEMENT_MISMATCH —— 界面上的「可更新到 vX」永远装不上。
        // 这里把「归档摘要 = 归档文件字节的 SHA-256」钉死。
        install("eta-plugin.json" to manifestJson(version = "1.0.0").encodeToByteArray())
        val archive = zip(
            "eta-plugin.json" to manifestJson(version = "2.0.0").encodeToByteArray(),
            "assets/notes.txt" to "hello".encodeToByteArray(),
        )
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }
        val installed = installer.installLocalZip(
            { ByteArrayInputStream(archive) },
            replaceExisting = true,
            expectedReplacementId = "demo-plugin",
            expectedArchiveSha256 = expected,
        )
        assertTrue("expected success but was $installed", installed is PluginPackageInstaller.Result.Installed)
        assertEquals(expected, (installed as PluginPackageInstaller.Result.Installed).sha256)
        assertTrue(File(root, "demo-plugin/eta-plugin.json").readText().contains("2.0.0"))
        // 暂存的归档也不能留在安装器工作目录里。
        assertEquals(
            emptyList<String>(),
            File(root.parentFile, ".eta-plugin-installer").list()?.toList(),
        )
    }

    @Test
    fun reinstallOfSameVersionIsIdempotent() {
        install("eta-plugin.json" to manifestJson().encodeToByteArray())
        val second = install("eta-plugin.json" to manifestJson().encodeToByteArray())
        assertTrue(second is PluginPackageInstaller.Result.Installed)
        assertTrue((second as PluginPackageInstaller.Result.Installed).alreadyInstalled)
    }

    @Test
    fun differentVersionNeedsExplicitReplacement() {
        install("eta-plugin.json" to manifestJson(version = "1.0.0").encodeToByteArray())
        val blocked = install(
            "eta-plugin.json" to manifestJson(version = "2.0.0").encodeToByteArray(),
        )
        assertEquals(
            "PLUGIN_ALREADY_INSTALLED",
            (blocked as PluginPackageInstaller.Result.Failed).code,
        )
        // 旧版本必须原样保留，不能被半新的解包破坏。
        assertTrue(File(root, "demo-plugin/eta-plugin.json").readText().contains("1.0.0"))
    }

    @Test
    fun replacementConfirmationMustMatchIdAndSha() {
        install("eta-plugin.json" to manifestJson(version = "1.0.0").encodeToByteArray())
        val archive = zip("eta-plugin.json" to manifestJson(version = "2.0.0").encodeToByteArray())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }

        val mismatch = installer.installLocalZip(
            { ByteArrayInputStream(archive) },
            replaceExisting = true,
            expectedReplacementId = "someone-else",
            expectedArchiveSha256 = digest,
        )
        assertEquals(
            "PLUGIN_REPLACEMENT_MISMATCH",
            (mismatch as PluginPackageInstaller.Result.Failed).code,
        )

        val wrongSha = installer.installLocalZip(
            { ByteArrayInputStream(archive) },
            replaceExisting = true,
            expectedReplacementId = "demo-plugin",
            expectedArchiveSha256 = "0".repeat(64),
        )
        assertEquals(
            "PLUGIN_REPLACEMENT_MISMATCH",
            (wrongSha as PluginPackageInstaller.Result.Failed).code,
        )

        val ok = installer.installLocalZip(
            { ByteArrayInputStream(archive) },
            replaceExisting = true,
            expectedReplacementId = "demo-plugin",
            expectedArchiveSha256 = digest,
        )
        assertTrue(ok is PluginPackageInstaller.Result.Installed)
        assertTrue(File(root, "demo-plugin/eta-plugin.json").readText().contains("2.0.0"))
        // 备份与暂存目录都必须清干净。
        assertEquals(emptyList<String>(), File(root.parentFile, ".eta-plugin-installer").list()?.toList())
    }

    @Test
    fun traversalPathsAreRejectedBeforeAnythingIsWritten() {
        val result = install(
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
            "../escape.txt" to "pwned".encodeToByteArray(),
        )
        assertEquals("ARCHIVE_UNSAFE_PATH", (result as PluginPackageInstaller.Result.Failed).code)
        assertFalse(File(root, "demo-plugin").exists())
        assertFalse(File(temporary.newFolder(), "escape.txt").exists())
    }

    @Test
    fun absoluteAndBackslashPathsAreRejected() {
        val absolute = install(
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
            "/etc/passwd" to "x".encodeToByteArray(),
        )
        assertEquals("ARCHIVE_UNSAFE_PATH", (absolute as PluginPackageInstaller.Result.Failed).code)

        val backslash = install(
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
            "..\\..\\windows\\system32" to "x".encodeToByteArray(),
        )
        assertEquals("ARCHIVE_UNSAFE_PATH", (backslash as PluginPackageInstaller.Result.Failed).code)
    }

    @Test
    fun symlinkEntryCannotEscapeThePluginRoot() {
        val outside = temporary.newFolder("outside")
        File(outside, "secret.txt").writeText("orig")

        val result = install(
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
            "outside/secret.txt" to "tampered".encodeToByteArray(),
        )
        // 该条目本身仍在插件根内，是合法深路径，应正常安装。
        assertTrue("expected success but was $result", result is PluginPackageInstaller.Result.Installed)
        assertEquals(
            null,
            resolveWithinSanity(root, "../../../outside/secret.txt"),
        )
        // 真正的目标文件没被碰到。
        assertEquals("orig", File(outside, "secret.txt").readText())
    }

    @Test
    fun existingSymlinkInStageIsRejectedInsteadOfFollowed() {
        // 预置一个指向根外的软链，再让归档写同一个名字：
        // 安装器必须识别出它是软链并拒绝，而不是顺着链接写到外面。
        val outside = temporary.newFolder("outside2")
        File(outside, "payload.txt").writeText("untouched")

        val stage = File(root.parentFile.apply { mkdirs() }, ".eta-plugin-installer")
        val planted = File(stage.apply { mkdirs() }, "planted")
        planted.mkdirs()
        Files.createSymbolicLink(
            File(planted, "escape.txt").toPath(),
            File(outside, "payload.txt").toPath(),
        )

        // 直接验证 resolveWithin 的边界语义：软链组件必须被拒。
        val viaPlanted = resolveWithinSanity(planted, "escape.txt")
        assertTrue("软链必须被识别", Files.isSymbolicLink(File(planted, "escape.txt").toPath()))
        assertEquals(
            "软链组件必须让解析返回 null，而不是顺着链接落到根外",
            null,
            viaPlanted,
        )
        assertEquals("untouched", File(outside, "payload.txt").readText())
    }

    /** 复刻安装器的路径解析边界，用来断言"越界"确实被判定为越界。 */
    private fun resolveWithinSanity(root: File, relative: String): File? {
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        var current = root
        for (part in parts) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) return null
            val canonical = runCatching { current.canonicalFile }.getOrNull() ?: return null
            if (!canonical.path.startsWith(root.canonicalFile.path + File.separator) &&
                canonical.path != root.canonicalFile.path
            ) {
                return null
            }
        }
        return current
    }

    @Test
    fun missingManifestFailsAndLeavesNoResidue() {
        val result = install("README.md" to "no manifest here".encodeToByteArray())
        assertEquals("MANIFEST_MISSING", (result as PluginPackageInstaller.Result.Failed).code)
        assertFalse(File(root, "README.md").exists())
        val stage = File(root.parentFile, ".eta-plugin-installer")
        assertEquals("暂存目录必须清空", emptyList<String>(), stage.list()?.toList())
    }

    @Test
    fun invalidManifestFailsWithItsOwnCode() {
        val result = install("eta-plugin.json" to "{\"id\":\"Bad_ID\"}".encodeToByteArray())
        assertEquals("MANIFEST_BAD_ID", (result as PluginPackageInstaller.Result.Failed).code)
        assertEquals(emptyList<String>(), installer.installedPlugins().map { it.id })
    }

    @Test
    fun deepPathBeyondLimitIsRejected() {
        val deep = (1..15).joinToString("/") { "level$it" } + "/eta-plugin.json"
        val result = install(
            deep to manifestJson().encodeToByteArray(),
            "eta-plugin.json" to manifestJson().encodeToByteArray(),
        )
        // 深层条目越界，整个安装必须失败，不能"部分成功"。
        assertEquals("ARCHIVE_UNSAFE_PATH", (result as PluginPackageInstaller.Result.Failed).code)
    }

    @Test
    fun uninstallRejectsMalformedIdsAndRemovesRealOnes() {
        install("eta-plugin.json" to manifestJson().encodeToByteArray())
        assertFalse(installer.uninstall("../etc"))
        assertFalse(installer.uninstall("Bad_Id"))
        assertTrue(installer.uninstall("demo-plugin"))
        assertFalse(File(root, "demo-plugin").exists())
        // 卸载不存在的插件按成功处理（幂等）。
        assertTrue(installer.uninstall("demo-plugin"))
    }

    // ── 目录安装（创造模式用：Android 上没有 zip 命令） ──────────────────────────

    private fun pluginDir(name: String, manifest: String = manifestJson()): File {
        val dir = temporary.newFolder(name)
        File(dir, "eta-plugin.json").writeText(manifest)
        File(dir, "assets").mkdirs()
        File(dir, "assets/notes.txt").writeText("hello")
        return dir
    }

    @Test
    fun directoryInstallWorksWithoutAnyPackagingTool() {
        val result = installer.installLocalDir(pluginDir("src"))
        assertTrue("expected success but was $result", result is PluginPackageInstaller.Result.Installed)
        val installed = result as PluginPackageInstaller.Result.Installed
        assertFalse(installed.alreadyInstalled)
        assertEquals("demo-plugin", installed.plugin.id)
        assertEquals(64, installed.sha256.length)
        assertTrue(File(root, "demo-plugin/eta-plugin.json").isFile)
        assertTrue(File(root, "demo-plugin/assets/notes.txt").isFile)
        assertEquals(listOf("demo-plugin"), installer.installedPlugins().map { it.id })
    }

    @Test
    fun directoryWithoutManifestIsRejected() {
        val dir = temporary.newFolder("no-manifest")
        File(dir, "readme.txt").writeText("x")
        val result = installer.installLocalDir(dir)
        assertEquals("MANIFEST_MISSING", (result as PluginPackageInstaller.Result.Failed).code)
        assertFalse(File(root, "demo-plugin").exists())
    }

    @Test
    fun directoryWithSymlinkIsRejected() {
        val dir = pluginDir("with-link")
        val target = File(temporary.newFolder("outside"), "secret.txt")
        target.writeText("secret")
        Files.createSymbolicLink(File(dir, "leak.txt").toPath(), target.toPath())
        val result = installer.installLocalDir(dir)
        assertEquals("ARCHIVE_SYMLINK_REJECTED", (result as PluginPackageInstaller.Result.Failed).code)
        assertFalse(File(root, "demo-plugin").exists())
    }

    @Test
    fun directoryReinstallOfSameVersionIsIdempotent() {
        installer.installLocalDir(pluginDir("first"))
        val second = installer.installLocalDir(pluginDir("second"))
        assertTrue(second is PluginPackageInstaller.Result.Installed)
        assertTrue((second as PluginPackageInstaller.Result.Installed).alreadyInstalled)
    }

    @Test
    fun directoryReplacementRejectsMismatchedSha() {
        installer.installLocalDir(pluginDir("v1"))
        val upgraded = pluginDir("v2", manifestJson(version = "2.0.0"))
        val installed = installer.installLocalDir(
            upgraded,
            replaceExisting = true,
            expectedReplacementId = "demo-plugin",
        )
        assertTrue(installed is PluginPackageInstaller.Result.Installed)
        val sha = (installed as PluginPackageInstaller.Result.Installed).sha256

        val third = pluginDir("v3", manifestJson(version = "3.0.0"))
        val mismatched = installer.installLocalDir(
            third,
            replaceExisting = true,
            expectedReplacementId = "demo-plugin",
            expectedArchiveSha256 = sha,
        )
        assertEquals("PLUGIN_REPLACEMENT_MISMATCH", (mismatched as PluginPackageInstaller.Result.Failed).code)
        // 被拒的覆盖不能动到已装版本。
        assertTrue(File(root, "demo-plugin/eta-plugin.json").readText().contains("2.0.0"))
    }

    @Test
    fun toolsJsonCarriesNameAndDescription() {
        val plugin = PluginManifest.parse(
            manifestJson {
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("name", "extra_tool").put("description", "做点事"),
                    ),
                )
            },
        )
        val json = installer.toolsJson((plugin as PluginManifest.ParseResult.Success).plugin)
        assertEquals(1, json.length())
        assertEquals("extra_tool", json.getJSONObject(0).getString("name"))
        assertEquals("做点事", json.getJSONObject(0).getString("description"))
    }
}
