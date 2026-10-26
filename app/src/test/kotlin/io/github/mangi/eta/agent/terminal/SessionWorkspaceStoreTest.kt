package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SessionWorkspaceStore.resolveStored] 是「自定义工作区还算不算数」的唯一判据：
 * 界面显示、工具落盘都走它，回退行为必须被钉住。
 */
class SessionWorkspaceStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun existingDirectoryIsUsedAsStored() {
        val folder = temporary.newFolder("project")

        assertEquals(folder.path, SessionWorkspaceStore.resolveStored(folder.path, "/fallback"))
    }

    @Test fun deletedDirectoryFallsBackToDefault() {
        val missing = File(temporary.root, "removed").path

        assertEquals("/fallback", SessionWorkspaceStore.resolveStored(missing, "/fallback"))
    }

    @Test fun regularFileIsNotAWorkspace() {
        val file = temporary.newFile("notes.txt")

        assertEquals("/fallback", SessionWorkspaceStore.resolveStored(file.path, "/fallback"))
    }

    @Test fun blankOrMissingValueFallsBackToDefault() {
        assertEquals("/fallback", SessionWorkspaceStore.resolveStored(null, "/fallback"))
        assertEquals("/fallback", SessionWorkspaceStore.resolveStored("", "/fallback"))
        assertEquals("/fallback", SessionWorkspaceStore.resolveStored("   ", "/fallback"))
    }
}
