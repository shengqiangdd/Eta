package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxGuestPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun relativePathsResolveAgainstTheCurrentGuestDirectory() {
        assertEquals("/workspace/eta-v2", LinuxGuestPaths.normalize("/workspace", "eta-v2"))
        assertEquals("/workspace/eta-v2/app", LinuxGuestPaths.normalize("/workspace/eta-v2", "app"))
        assertEquals("/workspace/eta-v2", LinuxGuestPaths.normalize("/workspace/eta-v2/app", ".."))
        assertEquals("/root/.dsh", LinuxGuestPaths.normalize("/workspace", "~/.dsh"))
        assertEquals("/root", LinuxGuestPaths.normalize("/workspace", "~"))
    }

    @Test fun pathsOutsideWorkspaceAndRootAreRejected() {
        assertNull(LinuxGuestPaths.normalize("/workspace", "/etc"))
        assertNull(LinuxGuestPaths.normalize("/workspace", "../../etc/passwd"))
        assertNull(LinuxGuestPaths.normalize("/workspace", "/workspace/../etc"))
        assertNull(LinuxGuestPaths.normalize("/workspace", "bad\u0000path"))
        assertTrue(LinuxGuestPaths.isAllowed("/workspace"))
        assertTrue(LinuxGuestPaths.isAllowed("/workspace/eta-v2"))
        assertTrue(LinuxGuestPaths.isAllowed("/root"))
        assertFalse(LinuxGuestPaths.isAllowed("/rootfs"))
        assertFalse(LinuxGuestPaths.isAllowed("/workspaces"))
    }

    @Test fun guestPathsMapToHostPathsForValidation() {
        val host = File(temporary.root, "terminal-user/workspace").apply { mkdirs() }
        val rootfs = File(temporary.root, "rootfs").apply { mkdirs() }
        File(rootfs, "root").mkdirs()

        assertEquals(
            File(host, "eta-v2").path,
            LinuxGuestPaths.hostPath("/workspace/eta-v2", host.path, rootfs.path),
        )
        assertEquals(
            File(rootfs, "root/.dsh").path,
            LinuxGuestPaths.hostPath("/root/.dsh", host.path, rootfs.path),
        )
        assertNull(LinuxGuestPaths.hostPath("/etc", host.path, rootfs.path))
        // rootfs 未安装时 /root 无法校验，不能猜一个宿主路径出来。
        assertNull(LinuxGuestPaths.hostPath("/root/.dsh", host.path, null))
    }
}
