package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEnvFileTest {
    /**
     * 单测不许读设备上真实的 Download 覆盖源。
     *
     * 在真机上跑时那个文件确实存在（用户真写的），「没有配置时应为空」这类断言会莫名失败：
     * 本轮就是被 /sdcard/Download/eta-env.conf 里的探针键绊了 4 个用例。
     */
    private fun hermeticEnvFile(dir: File): TerminalEnvFile =
        TerminalEnvFile(dir, downloadSource = File(dir, "no-download.conf"))
    @Test
    fun readsKeysFromPrivateFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText(
                """
                # comment
                HTTPS_PROXY=http://127.0.0.1:7890
                PATH=/usr/bin:/bin
                """.trimIndent(),
            )
            assertEquals(
                listOf("HTTPS_PROXY", "PATH"),
                hermeticEnvFile(dir).effectiveEntries().map { it.key },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresUnsafeOrMalformedLines() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText(
                """
                LD_PRELOAD=/tmp/evil.so
                1BAD=x
                GOOD="quoted value"
                """.trimIndent(),
            )
            val entries = hermeticEnvFile(dir).effectiveEntries()
            assertEquals(listOf("GOOD"), entries.map { it.key })
            assertEquals("quoted value", entries.single().value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyWhenNoFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            assertEquals(emptyList<TerminalEnvEntry>(), hermeticEnvFile(dir).effectiveEntries())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editedEntriesRoundTripThroughPrivateFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = hermeticEnvFile(dir)
            assertTrue(store.savePrivateEntries(listOf(
                TerminalEnvEntry("HTTPS_PROXY", "http://127.0.0.1:7890"),
                TerminalEnvEntry("APP_NAME", "Eta 测试"),
            )))
            assertEquals(
                listOf("HTTPS_PROXY", "APP_NAME"),
                store.privateEntries().map { it.key },
            )
            assertEquals("Eta 测试", store.privateEntries().single { it.key == "APP_NAME" }.value)
            assertEquals(2, store.effectiveEntries().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savesToAppOwnedTerminalUserAndReadsLegacyOnlyAsFallback() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText("OLD=1\n")
            val store = hermeticEnvFile(dir)
            assertEquals("1", store.privateEntries().single { it.key == "OLD" }.value)

            assertTrue(store.savePrivateEntries(listOf(TerminalEnvEntry("NEW", "2"))))
            assertTrue(File(dir, "terminal-user/env.conf").isFile)
            assertFalse(File(dir, "terminal/env.conf").readText().contains("NEW"))
            assertEquals(listOf("NEW"), store.privateEntries().map { it.key })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savingReplacesTheWholePrivateConfig() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = hermeticEnvFile(dir)
            store.savePrivateEntries(listOf(TerminalEnvEntry("OLD", "1")))
            assertTrue(store.savePrivateEntries(listOf(TerminalEnvEntry("NEW", "2"))))
            assertEquals(listOf(TerminalEnvEntry("NEW", "2")), store.privateEntries())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidEntriesAreRejectedWithoutWriting() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = hermeticEnvFile(dir)
            val invalid = listOf(
                TerminalEnvEntry("", "x"),
                TerminalEnvEntry("1BAD", "x"),
                TerminalEnvEntry("LD_PRELOAD", "/tmp/evil.so"),
                TerminalEnvEntry("GOOD", "line1\nline2"),
            )
            invalid.forEach { entry ->
                assertTrue(entry.key, !store.savePrivateEntries(listOf(entry)))
            }
            assertEquals(emptyList<TerminalEnvEntry>(), store.privateEntries())
            assertEquals(TerminalEnvValidation.BLOCKED_KEY, store.validateEntry("LD_PRELOAD", "x"))
            assertEquals(TerminalEnvValidation.INVALID_VALUE, store.validateEntry("GOOD", "a\nb"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * env.conf 是本机上唯一存明文值的地方，不能被其他身份读到；
     * 临时文件也不能留下来（它曾同样是明文）。
     */
    @Test
    fun savedFileAndDirectoryAreOwnerOnly() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = hermeticEnvFile(dir)
            assertTrue(store.savePrivateEntries(listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x"))))
            val file = File(dir, "terminal-user/env.conf")
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            assertEquals(
                "rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "terminal-user").toPath())),
            )
            assertFalse("临时文件不该留在盘上", File(File(dir, "terminal-user"), "env.conf.tmp").exists())
            assertEquals(listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x")), store.privateEntries())
        } finally {
            dir.deleteRecursively()
        }
    }
}
