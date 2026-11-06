package io.github.mangi.eta.data.backup

import io.github.mangi.eta.data.repository.EtaBackupJsonStreams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

internal class AutoBackupSchedulerTest {

    private fun tempDir(): File = Files.createTempDirectory("eta-auto-backup").toFile()

    private fun seed(dir: File, name: String, modifiedAt: Long): File =
        File(dir, name).apply {
            writeText("{}")
            setLastModified(modifiedAt)
        }

    private fun seedBytes(dir: File, name: String, modifiedAt: Long, size: Int): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size) { 0x41 })
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

    // ---- 目录隔离 ----

    @Test
    fun backupDirectoryIsPerPackage() {
        val root = tempDir()
        try {
            val main = AutoBackupScheduler.backupDirectoryUnder(root, "io.github.mangi.eta")
            val canary = AutoBackupScheduler.backupDirectoryUnder(root, "io.github.mangi.eta.canary")
            assertEquals(File(File(File(root, "Eta"), "backups"), "io.github.mangi.eta"), main)
            assertEquals(File(File(File(root, "Eta"), "backups"), "io.github.mangi.eta.canary"), canary)
            // 边车包的备份绝不能落在主包目录里，否则会出现「谁后备份谁把对方挤掉」。
            assertTrue(main.absolutePath != canary.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packageNameIsSanitizedForPathUse() {
        val root = tempDir()
        try {
            // 包名理论上是合法应用 ID，但这是外部存储路径拼接，不能假设调用方一定传对。
            val escaped = AutoBackupScheduler.backupDirectoryUnder(root, "../..")
            assertEquals(".._..", escaped.name)
            assertEquals("backups", escaped.parentFile?.name)
            assertEquals(
                "unknown",
                AutoBackupScheduler.backupDirectoryUnder(root, "   ").name,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeAutoBackupsReadsLegacyDirectoryButIgnoresManualExports() {
        val root = tempDir()
        try {
            val primary = File(root, "new").apply { mkdirs() }
            val legacy = File(root, "legacy").apply { mkdirs() }
            val fresh = seed(primary, "${AutoBackupScheduler.FILE_PREFIX}fresh.eta-backup.json", 5_000L)
            val old = seed(legacy, "${AutoBackupScheduler.FILE_PREFIX}old.eta-backup.json", 1_000L)
            // 旧混合目录里的手动导出不该被当成自动备份。
            seed(legacy, "Eta-backup-manual.eta-backup.json", 9_000L)

            val merged = AutoBackupScheduler.mergeAutoBackups(primary, legacy)

            assertEquals(listOf(fresh.name, old.name), merged.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyDatabaseIsNotWorthWritingABackup() {
        // 0 会话的备份恢复出来等于什么都没恢复，还会占掉「保留最近 3 份」的名额。
        assertFalse(AutoBackupScheduler.shouldPersistAutoBackup(conversationCount = 0, messageCount = 0))
        assertTrue(AutoBackupScheduler.shouldPersistAutoBackup(conversationCount = 1, messageCount = 0))
        assertTrue(AutoBackupScheduler.shouldPersistAutoBackup(conversationCount = 0, messageCount = 3))
    }

    // ---- 压缩 ----

    @Test
    fun gzipBackupsAndPlainJsonAreBothReadable() {
        val payload = """{"format":"eta-backup","conversations":[]}""".toByteArray()
        assertArrayEquals(
            payload,
            EtaBackupJsonStreams.openMaybeGzip(ByteArrayInputStream(payload)).readBytes(),
        )

        val compressed = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(payload) }
        }.toByteArray()
        // 首字节 1F 8B 是 gzip 魔数；导入侧据此自动解压，用户不必区分两种文件。
        assertEquals(0x1f.toByte(), compressed[0])
        assertArrayEquals(
            payload,
            EtaBackupJsonStreams.openMaybeGzip(ByteArrayInputStream(compressed)).readBytes(),
        )
    }

    @Test
    fun emptyInputIsStillEmptyAfterDetection() {
        // 空文件不能因为探测两字节而「看似有内容」：它仍然要在 stage 里被判成空备份。
        assertEquals(-1, EtaBackupJsonStreams.openMaybeGzip(ByteArrayInputStream(ByteArray(0))).read())
    }

    // ---- 总大小上限 ----

    @Test
    fun pruneRespectsTotalSizeBudget() {
        val dir = tempDir()
        try {
            // 每份 400 字节，共 1200；上限 900 时只能留 2 份。
            val newest = seedBytes(dir, "${AutoBackupScheduler.FILE_PREFIX}3.eta-backup.json.gz", 3_000L, 400)
            val middle = seedBytes(dir, "${AutoBackupScheduler.FILE_PREFIX}2.eta-backup.json.gz", 2_000L, 400)
            val stale = seedBytes(dir, "${AutoBackupScheduler.FILE_PREFIX}1.eta-backup.json.gz", 1_000L, 400)

            val removed = AutoBackupScheduler.prune(dir, keep = 3, maxBytes = 900)

            assertEquals(1, removed)
            assertTrue(newest.exists())
            assertTrue(middle.exists())
            assertFalse(stale.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneAlwaysKeepsTheNewestBackup() {
        val dir = tempDir()
        try {
            val newest = seedBytes(dir, "${AutoBackupScheduler.FILE_PREFIX}only.eta-backup.json.gz", 1_000L, 1_000)
            // 上限比单份还小：宁可超限也不能把最后一份删掉 —— 一份都不留等于没有备份。
            assertEquals(0, AutoBackupScheduler.prune(dir, keep = 3, maxBytes = 100))
            assertTrue(newest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
