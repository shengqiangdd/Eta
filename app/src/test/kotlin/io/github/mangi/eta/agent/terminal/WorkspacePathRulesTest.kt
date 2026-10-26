package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathRulesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun systemRootsStayBlocked() {
        assertTrue("/" in WorkspacePathRules.blockedRoots)
        assertTrue("/data" in WorkspacePathRules.blockedRoots)
        assertTrue("/proc" in WorkspacePathRules.blockedRoots)
        assertFalse("/storage/emulated/0" in WorkspacePathRules.blockedRoots)
    }

    @Test fun existingDirectoryValidatesToItsCanonicalPath() {
        val folder = temporary.newFolder("project")

        assertEquals(folder.canonicalPath, WorkspacePathRules.validateExistingDirectory(folder))
    }

    @Test fun regularFileCannotBeAWorkspace() {
        val file = temporary.newFile("notes.txt")

        val error = runCatching { WorkspacePathRules.validateExistingDirectory(file) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("工作区必须是目录", error?.message)
    }

    @Test fun canonicalPathIsUsedRatherThanTheRawInput() {
        val folder = temporary.newFolder("project")
        val viaDot = File(temporary.root, "project/.")

        assertEquals(folder.canonicalPath, WorkspacePathRules.validateExistingDirectory(viaDot))
    }

    @Test fun sharedStorageNeedsAllFilesAccess() {
        val primary = "/storage/emulated/0"

        assertTrue(WorkspacePathRules.requiresAllFilesAccess(primary, primary))
        assertTrue(WorkspacePathRules.requiresAllFilesAccess("$primary/Download/demo", primary))
        assertTrue(WorkspacePathRules.requiresAllFilesAccess("/storage/1A2B-3C4D/project", primary))
        // 应用私有目录与 /data/local/tmp 不属于共享存储，不需要该权限。
        assertFalse(WorkspacePathRules.requiresAllFilesAccess("/data/local/tmp", primary))
        assertFalse(WorkspacePathRules.requiresAllFilesAccess("/data/user/0/io.github.mangi.eta/files", primary))
        // 前缀相似但不是子目录，不能误判。
        assertFalse(WorkspacePathRules.requiresAllFilesAccess("/storage/emulated/0X", primary))
    }

    @Test fun otherAppsAndroidDataAndObbAreRestricted() {
        val primary = "/storage/emulated/0"

        assertTrue(WorkspacePathRules.isRestrictedSharedPath("$primary/Android/data", primary))
        assertTrue(WorkspacePathRules.isRestrictedSharedPath("$primary/Android/data/com.foo/files", primary))
        assertTrue(WorkspacePathRules.isRestrictedSharedPath("/storage/1A2B-3C4D/Android/obb", primary))
        assertFalse(WorkspacePathRules.isRestrictedSharedPath("$primary/Android/media", primary))
        // 名字里带 Android/data 的普通目录不受影响。
        assertFalse(WorkspacePathRules.isRestrictedSharedPath("$primary/Download/Android/data", primary))
    }
}
