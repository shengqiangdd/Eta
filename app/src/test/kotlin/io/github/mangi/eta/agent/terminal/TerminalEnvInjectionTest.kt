package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境变量注入的门禁测试。
 *
 * 核心不变量：**变量值绝不出现会被 `ps` 看到的地方**——脚本片段（也就是拼进
 * `sh -c` / `su -c` 的那段 argv）里只有文件路径。之前 token 泄露就是这里把
 * `export KEY='value';` 拼进了命令行。
 */
class TerminalEnvInjectionTest {
    private fun tempDir(): File = File.createTempFile("eta-env-inject", "").apply { delete(); mkdirs() }

    private fun runShell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    @Test
    fun scriptOnlyCarriesThePathNotTheValue() {
        val dir = tempDir()
        try {
            val entries = listOf(
                TerminalEnvEntry("GITHUB_TOKEN", "ghp_super_secret"),
                TerminalEnvEntry("HTTPS_PROXY", "http://127.0.0.1:7890"),
            )
            val script = requireNotNull(TerminalEnvInjection(dir).script(entries))
            assertFalse(script, script.contains("ghp_super_secret"))
            assertFalse(script, script.contains("127.0.0.1"))
            assertTrue(script, script.contains(". '"))
            assertTrue(script, script.contains("rm -f '"))
            // 必须是一行：会话协议按单逻辑行下发，换行会截断命令。
            assertFalse(script, script.contains('\n'))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unreadableScriptIsReportedAndCommandStillRuns() {
        val dir = tempDir()
        try {
            val script = requireNotNull(
                TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("A", "1"))),
            )
            assertTrue(script, script.contains("if [ -r '"))
            assertTrue(script, script.contains("then . '"))
            assertTrue(script, script.contains(ENV_SCRIPT_UNREADABLE_NOTICE))
            assertFalse("提示必须是单行", script.contains('\n'))
            // 把脚本删掉，模拟「source 时已经不存在」：提示要出现，后面的命令照常执行。
            val path = dir.listFiles()!!.single().absolutePath
            val output = runShell("rm -f '$path'; $script; printf 'after=ok'")
            assertTrue(output, output.contains(ENV_SCRIPT_UNREADABLE_NOTICE))
            assertTrue(output, output.contains("after=ok"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scriptFileIsPrivateAndHoldsTheExports() {
        val dir = tempDir()
        try {
            val script = requireNotNull(
                TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x"))),
            )
            val file = dir.listFiles()!!.single()
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            assertTrue(file.readText(), file.readText().contains("export GITHUB_TOKEN='ghp_x'"))
            assertTrue(script, script.contains(file.name))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun realShellGetsTheValueAndLeavesNoScriptBehind() {
        val dir = tempDir()
        try {
            val script = requireNotNull(
                TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_super_secret"))),
            )
            val output = runShell(
                "$script; printf 'value=%s' \"\$GITHUB_TOKEN\"",
            )
            assertEquals("value=ghp_super_secret", output)
            assertEquals("脚本应在 source 后立即删除", emptyList<File>(), dir.listFiles()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 这条才是原始缺陷的回归测试：`ps` 读的就是 /proc/PID/cmdline。 */
    @Test
    fun shellCommandLineDoesNotContainTheValue() {
        val dir = tempDir()
        try {
            val script = requireNotNull(
                TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_super_secret"))),
            )
            // 直接在 Kotlin 侧比对，而不是拿密钥当 grep 模式——模式本身就会进 argv，
            // 这样写会自己骗自己（第一版就踩了）。
            val output = runShell("$script; cat /proc/\$\$/cmdline")
            assertFalse(output, output.contains("ghp_super_secret"))
            assertFalse(output, output.contains("export GITHUB_TOKEN="))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun valuesAreShellQuoted() {
        val dir = tempDir()
        try {
            val script = requireNotNull(
                TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("TOKEN", "a'b c"))),
            )
            val output = runShell("$script; printf '%s' \"\$TOKEN\"")
            assertEquals("a'b c", output)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyOrUnsafeEntriesProduceNoScript() {
        val dir = tempDir()
        try {
            assertNull(TerminalEnvInjection(dir).script(emptyList()))
            assertNull(
                TerminalEnvInjection(dir).script(
                    listOf(
                        TerminalEnvEntry("LD_PRELOAD", "/tmp/evil.so"),
                        TerminalEnvEntry("1BAD", "x"),
                        TerminalEnvEntry("MULTI", "line1\nline2"),
                        TerminalEnvEntry("", "x"),
                    ),
                ),
            )
            assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleScriptsAreSweptButFreshOnesSurvive() {
        val dir = tempDir()
        try {
            val stale = File(dir, ".eta-env-old.sh").apply { writeText("export A='1'\n") }
            stale.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000)
            val fresh = File(dir, ".eta-env-fresh.sh").apply { writeText("export B='2'\n") }

            requireNotNull(TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("C", "3"))))

            assertFalse("陈旧脚本应被回收", stale.exists())
            assertTrue("新脚本不能被误删", fresh.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun androidTargetWritesUnderAppPrivateDirectory() {
        val filesDir = tempDir()
        try {
            val script = requireNotNull(
                terminalEnvScript(
                    entries = listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x")),
                    environment = TerminalEnvironment.ANDROID,
                    filesDir = filesDir,
                    rootfsPath = null,
                ),
            )
            val runtimeDir = File(filesDir, ANDROID_ENV_DIR)
            assertEquals(1, runtimeDir.listFiles()!!.size)
            assertTrue(script, script.contains(runtimeDir.absolutePath))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    /** guest 看不到宿主私有目录，脚本必须写进 rootfs 并用 /tmp 引用。 */
    @Test
    fun linuxTargetWritesInsideRootfs() {
        val filesDir = tempDir()
        val rootfs = tempDir()
        try {
            val script = requireNotNull(
                terminalEnvScript(
                    entries = listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x")),
                    environment = TerminalEnvironment.DEBIAN,
                    filesDir = filesDir,
                    rootfsPath = rootfs.absolutePath,
                ),
            )
            val runtimeDir = File(rootfs, "tmp")
            assertEquals(1, runtimeDir.listFiles()!!.size)
            assertTrue(script, script.contains("/tmp/.eta-env-"))
            assertFalse(script, script.contains(rootfs.absolutePath))
        } finally {
            filesDir.deleteRecursively()
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun linuxTargetWithoutRootfsIsSkippedInsteadOfLeaking() {
        assertNull(
            terminalEnvScript(
                entries = listOf(TerminalEnvEntry("GITHUB_TOKEN", "ghp_x")),
                environment = TerminalEnvironment.DEBIAN,
                filesDir = tempDir(),
                rootfsPath = null,
            ),
        )
    }

    /** 私有目录要收紧：只把文件设成 0600、目录留 0755 时，别的身份仍能列出并逐个试读。 */
    @Test
    fun ownedDirectoryIsTightenedToOwnerOnly() {
        val dir = tempDir()
        try {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            requireNotNull(TerminalEnvInjection(dir).script(listOf(TerminalEnvEntry("A", "1"))))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * rootfs 的 `/tmp` 是 guest 的公共目录（通常 1777），把它改成 0700 就是在弄坏整个发行版。
     * Linux 落点必须传 ownsDirectory=false，用“不被修改”来守。
     */
    @Test
    fun guestTempDirectoryPermissionsAreLeftAlone() {
        val rootfs = tempDir()
        val filesDir = tempDir()
        try {
            val guestTmp = File(rootfs, "tmp").apply { mkdirs() }
            Files.setPosixFilePermissions(guestTmp.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
            requireNotNull(
                terminalEnvScript(
                    entries = listOf(TerminalEnvEntry("A", "1")),
                    environment = TerminalEnvironment.DEBIAN,
                    filesDir = filesDir,
                    rootfsPath = rootfs.absolutePath,
                ),
            )
            assertEquals("rwxrwxrwx", PosixFilePermissions.toString(Files.getPosixFilePermissions(guestTmp.toPath())))
        } finally {
            rootfs.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    /** 注入失败时要能让用户看见，而且这一行本身只能是一行、不含值。 */
    @Test
    fun failureNoticeIsOneLineAndValueFree() {
        val prefix = envFailurePrefix()
        assertTrue(prefix, prefix.startsWith("echo '"))
        assertTrue(prefix, prefix.endsWith("; "))
        assertFalse(prefix, prefix.contains('\n'))
        assertTrue(prefix, prefix.contains(ENV_INJECTION_FAILED_NOTICE))
        // 单引号包裹：文案里不能有单引号，否则会提前闭合引号、把后半段当命令执行。
        assertFalse(ENV_INJECTION_FAILED_NOTICE, ENV_INJECTION_FAILED_NOTICE.contains('\''))
    }
}
