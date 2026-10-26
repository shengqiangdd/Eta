package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导出重名避让：`a.txt` → `a (1).txt`。
 *
 * 纯粹的函数级校验，不用 Robolectric —— 覆盖别人的文件是不可接受的副作用，
 * 而序号逻辑最容易在「无扩展名」「多个点」「点开头」这三种文件名上出错。
 */
class WorkspaceExportNameTest {

    @Test
    fun keepsNameWhenFree() {
        assertEquals("a.txt", uniqueExportFileName("a.txt", emptySet()))
    }

    @Test
    fun addsIndexBeforeExtension() {
        assertEquals("a (1).txt", uniqueExportFileName("a.txt", setOf("a.txt")))
        assertEquals("a (2).txt", uniqueExportFileName("a.txt", setOf("a.txt", "a (1).txt")))
    }

    @Test
    fun handlesNoExtensionAndMultipleDots() {
        assertEquals("README (1)", uniqueExportFileName("README", setOf("README")))
        assertEquals("archive.tar (1).gz", uniqueExportFileName("archive.tar.gz", setOf("archive.tar.gz")))
        // 点开头的隐藏文件没有扩展名，加序号后仍要能看出原名字。
        assertEquals(".env (1)", uniqueExportFileName(".env", setOf(".env")))
    }

    @Test
    fun neverReturnsATakenNameWhenRoomExists() {
        val taken = setOf("report.pdf", "report (1).pdf", "report (3).pdf")
        assertEquals("report (2).pdf", uniqueExportFileName("report.pdf", taken))
    }

    @Test(expected = ExportTargetBusy::class)
    fun failsInsteadOfReusingATakenNameWhenIndexesRunOut() {
        // 序号拉满仍全被占用时必须失败：返回一个已知被占用的名字，下一步就是截断别人的文件。
        uniqueExportFileName("a.txt", setOf("a.txt", "a (1).txt", "a (2).txt"), maxAttempts = 2)
    }
}
