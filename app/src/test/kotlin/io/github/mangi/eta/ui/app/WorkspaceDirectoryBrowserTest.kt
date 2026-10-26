package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ls -1p` 的输出解析：只有以 `/` 结尾的行算目录。
 *
 * 这一层写错的后果很具体 —— 界面会把普通文件当成「文件夹」显示，用户选中后
 * WorkspaceFolderValidator 报 NOT_DIRECTORY，看起来像"选不了目录"的功能缺陷。
 */
class WorkspaceDirectoryBrowserTest {

    @Test
    fun keepsOnlyDirectoriesWithTrailingSlash() {
        val output = """
            DCIM/
            Download/
            note.txt
            screenshot.png
        """.trimIndent()
        assertEquals(listOf("DCIM", "Download"), parseLsDirectories(output))
    }

    @Test
    fun stripsCarriageReturnFromWindowsStyleLines() {
        assertEquals(listOf("Download"), parseLsDirectories("Download/\r\nfile.txt\r\n"))
    }

    @Test
    fun ignoresBlankLinesAndTrailingNewline() {
        assertEquals(emptyList<String>(), parseLsDirectories("\n\n"))
        assertEquals(listOf("a"), parseLsDirectories("a/\n"))
    }

    @Test
    fun keepsNamesWithSpacesAndDots() {
        val output = "My Folder/\n.vault/\n..hidden/"
        assertEquals(listOf("My Folder", ".vault", "..hidden"), parseLsDirectories(output))
    }

    @Test
    fun returnsEmptyWhenOnlyFilesAreListed() {
        assertEquals(emptyList<String>(), parseLsDirectories("a.txt\nb.bin\n"))
    }

    @Test
    fun parseLsEntriesMarksFilesAndDirectories() {
        val entries = parseLsEntries("DCIM/\r\nnote.txt\n.data/\n.rc\n")
        assertEquals(
            listOf(
                "DCIM" to true,
                "note.txt" to false,
                ".data" to true,
                ".rc" to false,
            ),
            entries.map { it.name to it.directory },
        )
    }

    @Test
    fun filterHidesDotEntriesByDefault() {
        val raw = listOf(
            WorkspaceDirectoryEntry("", "Download", true),
            WorkspaceDirectoryEntry("", ".thumbnails", true),
            WorkspaceDirectoryEntry("", "note.txt", false),
        )
        assertEquals(
            listOf("Download"),
            filterBrowseEntries(raw, showHidden = false, includeFiles = false).map { it.name },
        )
        assertEquals(
            // 隐藏项按名字排序时排在前面（`.` 的编码小于字母）；选“显示隐藏项”只是为了看得见，不改变普通项的次序。
            listOf(".thumbnails", "Download", "note.txt"),
            filterBrowseEntries(raw, showHidden = true, includeFiles = true).map { it.name },
        )
    }

    @Test
    fun filterDropsDotAndDotDotAndSortsDirectoriesFirst() {
        val raw = listOf(
            WorkspaceDirectoryEntry("", ".", true),
            WorkspaceDirectoryEntry("", "..", true),
            WorkspaceDirectoryEntry("", "zebra.txt", false),
            WorkspaceDirectoryEntry("", "alpha", true),
            WorkspaceDirectoryEntry("", "Beta", true),
            WorkspaceDirectoryEntry("", "apple.txt", false),
            WorkspaceDirectoryEntry("", "", false),
        )
        assertEquals(
            listOf("alpha", "Beta", "apple.txt", "zebra.txt"),
            filterBrowseEntries(raw, showHidden = false, includeFiles = true).map { it.name },
        )
    }

    @Test
    fun filterKeepsOnlyOneEntryPerName() {
        val raw = listOf(
            WorkspaceDirectoryEntry("", "Download", true),
            WorkspaceDirectoryEntry("", "Download", true),
        )
        assertEquals(1, filterBrowseEntries(raw, showHidden = false, includeFiles = false).size)
    }
}
