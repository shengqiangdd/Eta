package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalPrivateStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun ordinaryDirectoriesCanBeCreatedWhenLegacyParentIsNotWritable() {
        val files = temporary.newFolder("files")
        val legacy = File(files, "terminal").apply { mkdirs() }
        val oldRootfs = File(legacy, "debian/rootfs").apply { mkdirs() }
        val marker = File(oldRootfs, LinuxEnvironmentPaths.READY_MARKER).apply { writeText("existing-root-environment") }
        assertTrue(legacy.setWritable(false, false))
        try {
            // 测试进程是 root 时 root 无视 DAC 权限，根本构造不出「不可写的父目录」。
            // 这是环境限制而不是实现问题：跳过它，别让它长期挂在「已知失败」里当噪音。
            Assume.assumeFalse("测试进程是 root，无法构造只读父目录", legacy.canWrite())
            assertFalse(legacy.canWrite())
            val workspace = TerminalPrivateStorage.workspace(files)
            val environment = TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.DEBIAN)

            assertTrue(workspace.mkdirs())
            assertTrue(environment.mkdirs())
            File(workspace, "ordinary.txt").writeText("app-data")
            assertEquals("app-data", File(workspace, "ordinary.txt").readText())
            assertEquals("existing-root-environment", marker.readText())
            assertFalse(legacy.canWrite())
            assertFalse(File(legacy, "proot").exists())
            assertFalse(File(legacy, "workspace").exists())
        } finally {
            legacy.setWritable(true, true)
        }
    }

    @Test
    fun existingOrdinaryDataKeepsItsOriginalPath() {
        val files = temporary.newFolder("existing")
        val workspace = File(files, "terminal/workspace").apply { mkdirs() }
        val environment = File(files, "terminal/proot/debian").apply { mkdirs() }
        val saved = File(workspace, "saved.txt").apply { writeText("keep") }

        assertEquals(workspace, TerminalPrivateStorage.workspace(files))
        assertEquals(environment, TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.DEBIAN))
        assertEquals("keep", saved.readText())
        assertFalse(File(files, "terminal-user").exists())
    }

    @Test
    fun newDirectoryRemainsSelectedWhenBothLayoutsExist() {
        val files = temporary.newFolder("both")
        val workspace = TerminalPrivateStorage.workspace(files).apply { mkdirs() }
        val environment = TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.ALPINE).apply { mkdirs() }
        File(files, "terminal/workspace").mkdirs()
        File(files, "terminal/proot/alpine").mkdirs()

        assertEquals(workspace, TerminalPrivateStorage.workspace(files))
        assertEquals(environment, TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.ALPINE))
    }

    @Test
    fun sessionWorkspacesAreSeparatedAndPathSafe() {
        val files = temporary.newFolder("sessions")
        val first = TerminalPrivateStorage.sessionWorkspace(files, "conversation/one")
        val second = TerminalPrivateStorage.sessionWorkspace(files, "conversation/two")

        assertTrue(first.isDirectory)
        assertTrue(second.isDirectory)
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.absolutePath.contains("terminal-user/workspaces/"))
        assertFalse(first.absolutePath.contains(".."))
        assertTrue(first.name.contains("conversation_one"))
    }

    @Test
    fun deletingSessionWorkspaceRemovesFilesWithoutRecreatingTheDirectory() {
        val files = temporary.newFolder("delete-session")
        val directory = TerminalPrivateStorage.sessionWorkspace(files, "conv-delete")
        File(directory, "a.txt").writeText("x")
        val path = TerminalPrivateStorage.sessionWorkspacePath(files, "conv-delete")

        assertTrue(TerminalPrivateStorage.deleteSessionWorkspace(files, "conv-delete"))
        assertFalse("delete must not leave a recreated directory behind", path.exists())
        // 没有会话 id 时不能误删共享的 session 目录。
        assertFalse(TerminalPrivateStorage.deleteSessionWorkspace(files, ""))
        // 目录已经不存在时视为清理成功。
        assertTrue(TerminalPrivateStorage.deleteSessionWorkspace(files, "conv-delete"))
    }

    @Test
    fun bothOrdinaryLayoutsAreRecognizedWithoutChangingChrootIdentity() {
        listOf("terminal", "terminal-user").forEach { parent ->
            assertEquals(LinuxExecutionBackend.PROOT, LinuxEnvironmentPaths.backendOf("/data/user/0/app/files/$parent/proot/debian/rootfs"))
        }
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentPaths.backendOf("/data/user/0/app/files/terminal/debian/rootfs"))
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentPaths.backendOf(null))
    }
}
