package io.github.mangi.eta.data.backup

import io.github.mangi.eta.data.repository.EtaBackupJsonStreams
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份失败的归因。
 *
 * 这组用例守着一条用户可见的约定：英文 errno（`ENOSPC`、`SQLITE_FULL`、`EROFS`）
 * 不能被说成「备份文件格式无效」，也不能原样丢给用户 —— 那会把人引向「换个备份文件试试」，
 * 而真正该做的是清存储。
 */
class BackupFailureTextTest {
    @Test
    fun outOfSpaceIsRecognizedThroughTheCauseChain() {
        val wrapped = IOException("stage failed", IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(StorageFailures.isOutOfSpace(wrapped))
        assertFalse(StorageFailures.isNotWritable(wrapped))
    }

    @Test
    fun sqliteFullCountsAsOutOfSpace() {
        // 导入前的 VACUUM INTO 快照空间不足抛的是 SQLiteException，不是 IOException。
        assertTrue(
            StorageFailures.isOutOfSpace(
                IllegalStateException("database or disk is full (code 13 SQLITE_FULL)"),
            ),
        )
    }

    @Test
    fun readOnlyAndPermissionErrorsAreNotTreatedAsCorruptBackups() {
        assertTrue(StorageFailures.isNotWritable(IOException("open failed: EROFS (Read-only file system)")))
        assertTrue(StorageFailures.isNotWritable(IOException("open failed: EACCES (Permission denied)")))
    }

    @Test
    fun genuineFormatErrorsAreNotBlamedOnStorage() {
        val corrupt = IllegalArgumentException("Unexpected token at offset 12")
        assertFalse(StorageFailures.isOutOfSpace(corrupt))
        assertFalse(StorageFailures.isNotWritable(corrupt))
    }

    @Test
    fun stagingFailureNamesTheRealCause() {
        val diskFull = EtaBackupJsonStreams.stagingFailure(
            IOException("write failed: ENOSPC (No space left on device)"),
        )
        assertTrue(diskFull.message.orEmpty().contains("存储空间不足"))

        val readOnly = EtaBackupJsonStreams.stagingFailure(
            IOException("open failed: EROFS (Read-only file system)"),
        )
        assertTrue(readOnly.message.orEmpty().contains("无法写入应用缓存"))

        // 真正的格式问题仍报格式无效：别把「库坏了」说成「磁盘满了」。
        assertEquals(
            "备份文件格式无效",
            EtaBackupJsonStreams.stagingFailure(IllegalStateException("bad json")).message,
        )
    }

    @Test
    fun autoBackupReasonIsHumanReadable() {
        assertTrue(
            AutoBackupScheduler.failureReason(IOException("write failed: ENOSPC (No space left on device)"))
                .contains("存储空间不足"),
        )
        // 领域内已经写好的 message 原样保留，不被这层覆盖。
        assertEquals("boom", AutoBackupScheduler.failureReason(IOException("boom")))
    }
}
