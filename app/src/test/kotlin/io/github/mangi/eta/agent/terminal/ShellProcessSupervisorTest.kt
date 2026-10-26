package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun groupCleanupListsProcessGroupsWithPsBeforeFallingBackToProcScan() {
        val supervisor = ShellProcessSupervisor()
        val launcher = supervisor.buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-group-test.owner"),
            ownershipToken = "token123",
            command = "true",
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
        )

        // 全 /proc 扫描是每次工具调用的固定开销（本机 912 进程约 193ms/轮），必须先试 ps。
        assertTrue(launcher.contains("ps -A -o pid=,pgid="))
        assertTrue(launcher.contains("eta_cleanup_ps_group || { eta_cleanup_proc_group;"))
        assertTrue(launcher.contains("eta_killed="))
        // 兜底仍然保留：ps 缺失时必须退回全扫描，而不是悄悄放弃清理。
        assertTrue(launcher.contains("/proc/[0-9]*/stat"))
        val syntax = ProcessBuilder("sh", "-n", "-c", launcher).redirectErrorStream(true).start()
        val output = syntax.inputStream.bufferedReader().readText()
        assertEquals(output, 0, syntax.waitFor())
    }

    @Test
    fun processTreeKillScriptUsesPsInvocationBothPsFlavoursAccept() {
        val script = processTreeKillScript(4242)

        // `ps -axo` 在 BusyBox 与 toybox 上都会被拒绝，取不到子进程就会静默只杀顶层 PID。
        assertTrue(script.contains("ps -A -o pid=,ppid="))
        assertFalse(script.contains("-axo"))
        assertTrue(script.contains("kill_tree 4242"))
        val syntax = ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
        val output = syntax.inputStream.bufferedReader().readText()
        assertEquals(output, 0, syntax.waitFor())
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp"))
        // 工作区路径在 inner script 里被 shellQuote 引号包裹，外层又被整体转义；直接断言路径片段。
        assertTrue(payload.contains("/data/local/tmp/eta"))
        assertTrue(payload.contains("eta_rootfs/workspace"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(AlpineEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // Alpine 的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$eta_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun chrootPayloadBindsExplicitCustomWorkspace() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs",
            command = "pwd",
            workspace = "/storage/emulated/0/project",
        )

        assertTrue(payload.contains("/storage/emulated/0/project"))
        assertFalse(payload.contains("/data/local/tmp/eta"))
        // 命令仍在 /workspace 执行：guest 路径不变，变的是它的宿主来源。
        assertTrue(payload.contains("/workspace"))
    }

    @Test
    fun ptyLauncherUsesNativePtyForEveryIdentity() {
        val executable = File("/data/app/eta/lib/arm64/libeta_pty.so")
        val supervisor = ShellProcessSupervisor(ptyExecutable = { executable })
        listOf("user", "root").forEach { identity ->
            val launcher = supervisor.buildTrackedShellLauncher(
                ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
                ownershipToken = "token123",
                command = null,
                identity = identity,
                environment = TerminalEnvironment.ANDROID,
                linuxRootfsPath = null,
                pty = true,
                ptyCols = 120,
                ptyRows = 40,
            )

            assertTrue(launcher.contains("exec '${executable.absolutePath}' 40 120 -- sh -c"))
            assertTrue(launcher.contains("TERM=xterm-256color"))
            assertTrue(launcher.contains(" group"))
            assertFalse(launcher.contains("script -qfc"))
        }
    }

    @Test(expected = IOException::class)
    fun ptyLauncherRejectsMissingNativePty() {
        ShellProcessSupervisor(ptyExecutable = { null }).buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
        )
    }

    @Test
    fun interactiveRootConsoleAvoidsBusyBoxAshWithSigwinchBug() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildAndroidPayload("root", command = null, interactivePty = true)

        assertTrue(payload.contains("*' v1.35.'*|*' v1.36.'*)"))
        assertTrue(payload.contains("--install -s '/data/local/tmp/eta/.busybox'"))
        assertTrue(payload.contains("export PATH=\"${'$'}PATH:\"'/data/local/tmp/eta/.busybox'; sh;;"))
        assertTrue(payload.contains("*) export ASH_STANDALONE=1; \"${'$'}eta_busybox\" ash ;;"))
        val syntax = ProcessBuilder("sh", "-n", "-c", payload).redirectErrorStream(true).start()
        val output = syntax.inputStream.bufferedReader().readText()
        assertEquals(output, 0, syntax.waitFor())
    }

    @Test
    fun nonInteractiveRootCommandsKeepBusyBoxAsh() {
        val payload = ShellProcessSupervisor().buildAndroidPayload("root", "id")

        assertFalse(payload.contains("v1.36."))
        assertTrue(payload.contains("ash -c 'id'"))
    }
}
