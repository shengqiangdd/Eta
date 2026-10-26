package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器「记住上次目录」的纯逻辑。
 *
 * `WorkspacePickerHistory` 本身要 Context（共享存储读写），在 Linux/aarch64 上跑不了
 * Robolectric；能守住的关键判断是「记住哪个目录」，抽成了纯函数在这里测。
 */
class WorkspacePickerHistoryTest {

    @Test
    fun importRemembersTheDirectoryNotTheFile() {
        // 记成文件路径的话，下次选择器会落在一个文件上 → 一进去就是「无法列出该目录」。
        assertEquals(
            "/storage/emulated/0/Download/0-eta-ws",
            importHistoryDirectory("/storage/emulated/0/Download/0-eta-ws/note one.txt"),
        )
    }

    @Test
    fun importRemembersTheRootWhenTheFileSitsInRoot() {
        assertEquals("/", importHistoryDirectory("/note.txt"))
    }

    @Test
    fun importGivesNoAnswerForPathsWithoutADirectory() {
        // 相对路径/空串：父目录拿不到，调用方保持原值，而不是记一个空目录。
        assertNull(importHistoryDirectory("note.txt"))
        assertNull(importHistoryDirectory(""))
    }

    @Test
    fun importAndExportDoNotShareTheSameSlot() {
        // 共用一个 key 会让「导出到 A」把「导入自 B」冲掉，两个动作互相破坏记忆。
        val keys = WorkspacePickerHistory.Kind.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("key 不能为空：$keys", keys.all { it.isNotBlank() })
    }
}
