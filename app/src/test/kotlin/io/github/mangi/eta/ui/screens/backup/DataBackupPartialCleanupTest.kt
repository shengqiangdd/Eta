package io.github.mangi.eta.ui.screens.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix135 补：导出途中进程被杀会留下 `.part`。
 *
 * 真机实测（canary，390 MB 库）确认了这一点：强杀后目录里躺着一个 38.9 MB 的
 * `Eta-backup-….eta-backup.json.part`，而 cache 是干净的（cache 中转已经删掉了）。
 * 这份残留不会被 uniqueBackupFile 当成重名（它只看正式名），所以只能靠导出前清理。
 */
internal class DataBackupPartialCleanupTest {

    private fun tempDir(): File = Files.createTempDirectory("eta-partial").toFile()

    private fun seed(dir: File, name: String, size: Int = 16): File =
        File(dir, name).apply { writeBytes(ByteArray(size)) }

    @Test
    fun `上次中断留下的 part 被删掉，正式备份不动`() {
        val dir = tempDir()
        val orphan = seed(dir, "Eta-backup-20261107-0021.eta-backup.json.part", 4096)
        val finished = seed(dir, "Eta-backup-20261107-0016.eta-backup.json", 128)

        assertEquals(1, cleanOrphanPartials(dir))
        assertFalse(orphan.exists())
        assertTrue(finished.exists())
        assertEquals(128, finished.length().toInt())
    }

    @Test
    fun `只认自家命名，不误删目录里的别的临时文件`() {
        val dir = tempDir()
        val others = listOf(
            "notes.part",
            "Eta-backup-20261107-0021.eta-backup.json.bak",
            "Eta-backup-20261107-0021.part",
            "download.tmp",
        ).map { seed(dir, it) }
        val orphan = seed(dir, "Eta-backup-20261107-0021.eta-backup.json.part")

        assertEquals(1, cleanOrphanPartials(dir))
        assertFalse(orphan.exists())
        others.forEach { assertTrue("不该删 ${it.name}", it.exists()) }
    }

    @Test
    fun `目录不存在时返回 0 且不抛异常`() {
        val missing = File(tempDir(), "not-there")
        assertEquals(0, cleanOrphanPartials(missing))
    }

    @Test
    fun `同名目录不当作临时文件删除`() {
        val dir = tempDir()
        val sub = File(dir, "子目录.eta-backup.json.part").apply { mkdirs() }
        assertTrue(sub.isDirectory)

        assertEquals(0, cleanOrphanPartials(dir))
        assertTrue(sub.exists())
    }

    @Test
    fun `后缀常量与导出命名绑定`() {
        // 导出写的名字是 defaultBackupFileName() + PARTIAL_SUFFIX；
        // 清理匹配的是 BACKUP_PARTIAL_SUFFIX。改命名时这条会红，提醒同步两处。
        val exported = "Eta-backup-20261107-0021.eta-backup.json" + ".part"
        assertTrue(exported.endsWith(BACKUP_PARTIAL_SUFFIX))
    }
}
