package io.github.mangi.eta.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class AutoBackupSchedulerTest {

    private fun tempDir(): File = Files.createTempDirectory("eta-auto-backup").toFile()

    private fun seed(dir: File, name: String, modifiedAt: Long): File =
        File(dir, name).apply {
            writeText("{}")
            setLastModified(modifiedAt)
        }

    // ---- 到期判定 ----

    @Test
    fun neverBackedUpCountsAsDue() {
        // 装好就留一份，而不是等满一个间隔 —— 那段时间恰恰最容易丢数据。
        assertTrue(AutoBackupScheduler.isDue(lastBackupAt = 0L, intervalHours = 24, now = 1_000L))
    }

    @Test
    fun notDueBeforeIntervalElapsed() {
        val now = 10_000L
        assertFalse(
            AutoBackupScheduler.isDue(
                lastBackupAt = now - 1_000L,
                intervalHours = 24,
                now = now,
            ),
        )
    }

    @Test
    fun dueExactlyAtInterval() {
        val intervalMs = 24L * 60L * 60L * 1000L
        val now = 100_000_000L
        assertTrue(
            AutoBackupScheduler.isDue(
                lastBackupAt = now - intervalMs,
                intervalHours = 24,
                now = now,
            ),
        )
    }

    @Test
    fun nonPositiveIntervalIsNeverDue() {
        // 间隔非法时宁可不动，也不要在每次检查时都写一份备份。
        assertFalse(AutoBackupScheduler.isDue(lastBackupAt = 0L, intervalHours = 0, now = 1_000L))
        assertFalse(AutoBackupScheduler.isDue(lastBackupAt = 0L, intervalHours = -5, now = 1_000L))
    }

    // ---- 生成文件识别 ----

    @Test
    fun autoBackupsIgnoresManualBackupsAndTempFiles() {
        val dir = tempDir()
        try {
            val first = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}a.eta-backup.json", 1_000L)
            val second = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}b.eta-backup.json", 2_000L)
            // 用户手动导出的备份、写到一半的临时文件，都不该被当成自动备份。
            seed(dir, "Eta-backup-20260101-1200.eta-backup.json", 3_000L)
            seed(dir, "${AutoBackupScheduler.FILE_PREFIX}c.eta-backup.json.part", 4_000L)

            assertEquals(listOf(second.name, first.name), AutoBackupScheduler.autoBackups(dir).map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 清理 ----

    @Test
    fun pruneKeepsNewestAndNeverTouchesManualBackups() {
        val dir = tempDir()
        try {
            val manual = seed(dir, "Eta-backup-20260101-1200.eta-backup.json", 9_000L)
            val newest = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}4.eta-backup.json", 4_000L)
            val middle = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}3.eta-backup.json", 3_000L)
            val keepLast = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}2.eta-backup.json", 2_000L)
            val stale = seed(dir, "${AutoBackupScheduler.FILE_PREFIX}1.eta-backup.json", 1_000L)

            val removed = AutoBackupScheduler.prune(dir, keep = 3)

            assertEquals(1, removed)
            assertFalse("最旧的一份应当被清掉", stale.exists())
            assertTrue(newest.exists())
            assertTrue(middle.exists())
            assertTrue(keepLast.exists())
            // 这条是关键：自动清理绝不能连用户手动导出的备份一起删。
            assertTrue("用户手动导出的备份必须原样保留", manual.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneWithMoreFilesThanKeepRemovesTheRest() {
        val dir = tempDir()
        try {
            repeat(5) { index ->
                seed(
                    dir,
                    "${AutoBackupScheduler.FILE_PREFIX}$index.eta-backup.json",
                    (index + 1) * 1_000L,
                )
            }

            val removed = AutoBackupScheduler.prune(dir, keep = 2)

            assertEquals(3, removed)
            assertEquals(2, AutoBackupScheduler.autoBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneOnEmptyOrMissingDirectoryIsSafe() {
        val dir = tempDir()
        try {
            assertEquals(0, AutoBackupScheduler.prune(dir, keep = 3))
            assertEquals(0, AutoBackupScheduler.prune(File(dir, "missing"), keep = 3))
        } finally {
            dir.deleteRecursively()
        }
    }
}
