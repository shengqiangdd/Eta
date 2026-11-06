package io.github.mangi.eta.data.backup

import android.content.Context
import android.os.Environment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.repository.EtaBackupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/** 一次自动备份的结果；[ran] 为 false 表示这次根本没有到期，什么都没做。 */
internal data class AutoBackupOutcome(
    val ran: Boolean,
    val file: File? = null,
    val pruned: Int = 0,
    val error: String? = null,
    /** 库是空的（0 会话 0 消息）：这次没有写盘，也不算失败。 */
    val skippedEmpty: Boolean = false,
)

/**
 * 自动备份：把「用户会不会记得手动导出」这件事从用户身上拿走。
 *
 * 参考 DSHA 的做法（默认开启、按间隔生成、只保留最近几份），这里刻意收紧三处：
 *
 * 1. **只清理自己生成的文件** —— 按 [FILE_PREFIX] 前缀识别，用户在备份页手动导出的
 *    `Eta-backup-*.json` 一份都不碰。自动清理误删用户备份是不可接受的失败。
 * 2. **先写临时文件再改名** —— 写到一半被杀（低内存、用户强停）不会留下一个
 *    看似正常、实际截断的备份文件，那比没有备份更危险。
 * 3. **落点在外部存储** —— 写在 `Download/Eta/backups` 而不是应用私有目录，
 *    否则卸载重装时备份会跟着一起消失，等于没备。
 */
internal object AutoBackupScheduler {
    /** 自动备份文件名前缀；[prune] 只认这个前缀。 */
    const val FILE_PREFIX = "Eta-auto-"

    /** 保留的自动备份份数。 */
    const val KEEP_COUNT = 3

    /** 自动备份总量上限：份数之内也按总大小兜底，避免巨库把保留的几份堆到 GB 级。 */
    const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    private const val WRITE_BUFFER_BYTES = 64 * 1024

    /** 可选的间隔（小时）。 */
    val INTERVAL_CHOICES = listOf(6, 12, 24, 72, 168)

    const val DEFAULT_INTERVAL_HOURS = 24

    private const val DOWNLOADS_SUBDIR = "Eta"
    private const val BACKUPS_SUBDIR = "backups"
    private const val TEMP_SUFFIX = ".part"

    /** 自动备份目录：`<外部存储>/Download/Eta/backups/<applicationId>`。
     *
     * 按包名分子目录是必须的：主包与边车包（applicationIdSuffix 为 .canary）共用外部存储，
     * 混在同一个目录里时，边车包空库启动写出的 0 会话备份会被「只保留最近 N 份」当成有效备份，
     * 把主包的真备份挤掉。 */
    fun backupDirectory(packageName: String): File =
        backupDirectoryUnder(downloadsRoot(), packageName)

    /** 旧版本只写到 `Download/Eta/backups`，各包混装且无法区分归属；只读兼容，不再往里写。 */
    fun legacyBackupDirectory(): File = File(File(downloadsRoot(), DOWNLOADS_SUBDIR), BACKUPS_SUBDIR)

    internal fun backupDirectoryUnder(root: File, packageName: String): File =
        File(File(File(root, DOWNLOADS_SUBDIR), BACKUPS_SUBDIR), sanitizePackage(packageName))

    private fun downloadsRoot(): File {
        @Suppress("DEPRECATION")
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    private fun sanitizePackage(packageName: String): String =
        packageName.trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * 是否到期。
     *
     * [lastBackupAt] 为 0 表示从未备份过 —— 视为到期，于是装好之后第一次检查就会留一份，
     * 而不是等满一个间隔才第一次备份（那段时间恰恰是最容易丢数据的）。
     */
    fun isDue(lastBackupAt: Long, intervalHours: Int, now: Long): Boolean {
        if (intervalHours <= 0) return false
        if (lastBackupAt <= 0L) return true
        val intervalMs = intervalHours.toLong() * 60L * 60L * 1000L
        return now - lastBackupAt >= intervalMs
    }

    /** 目录里的自动备份，最新在前。 */
    fun autoBackups(directory: File): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && !file.name.endsWith(TEMP_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** 本包目录 + 旧混合目录里的自动备份，最新在前。旧目录是只读兼容，只为不让老备份「消失」。 */
    fun autoBackupsFor(packageName: String): List<File> =
        mergeAutoBackups(backupDirectory(packageName), legacyBackupDirectory())

    internal fun mergeAutoBackups(primary: File, legacy: File): List<File> =
        (autoBackups(primary) + autoBackups(legacy))
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }

    /** 空库不值得写盘：0 会话的备份恢复出来等于什么都没恢复，还会占用保留名额。 */
    internal fun shouldPersistAutoBackup(conversationCount: Int, messageCount: Int): Boolean =
        conversationCount > 0 || messageCount > 0

    /**
     * 只保留最近 [keep] 份自动备份、且总量不超过 [maxBytes]，返回实际删掉的份数。
     *
     * 份数与大小两道闸：巨库单份就有几百 MB，只按份数留 3 份会堆到 GB；
     * 但无论超多少，**至少留最新的一份** —— 一份都不留等于没有备份。
     */
    fun prune(directory: File, keep: Int, maxBytes: Long = MAX_TOTAL_BYTES): Int {
        val backups = autoBackups(directory)
        val keepCount = keep.coerceAtLeast(0)
        var removed = 0
        backups.drop(keepCount).forEach { stale ->
            if (stale.delete()) removed++
        }
        val kept = backups.take(keepCount).toMutableList()
        var total = kept.sumOf { it.length().coerceAtLeast(0L) }
        while (maxBytes > 0 && kept.size > 1 && total > maxBytes) {
            val stale = kept.removeAt(kept.lastIndex)
            total -= stale.length().coerceAtLeast(0L)
            if (stale.delete()) removed++
        }
        return removed
    }

    /**
     * 立即执行一次备份。
     *
     * 调用方负责判断「是否到期」与「是否正在跑任务」；这里只做备份本身，失败通过
     * [AutoBackupOutcome.error] 返回而不抛异常 —— 自动任务不该把界面搞崩。
     */
    suspend fun run(context: Context, keep: Int = KEEP_COUNT): AutoBackupOutcome =
        withContext(Dispatchers.IO) {
            val directory = backupDirectory(context.packageName)
            try {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    return@withContext AutoBackupOutcome(
                        ran = true,
                        error = "无法创建备份目录：${directory.absolutePath}",
                    )
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val target = uniqueFile(directory, "$FILE_PREFIX$stamp.eta-backup.json.gz")
                val temp = File(directory, target.name + TEMP_SUFFIX)
                try {
                    // 压缩写：巨库的明文 JSON 能到 400 MB 级，gzip 后通常只剩十分之一左右。
                    // 大小上限仍按解压后的明文算（EtaBackupJsonStreams 的 LimitedOutput 在内层）。
                    val summary = GZIPOutputStream(
                        BufferedOutputStream(FileOutputStream(temp), WRITE_BUFFER_BYTES),
                        WRITE_BUFFER_BYTES,
                    ).use { output ->
                        EtaBackupRepository.export(context, output)
                    }
                    if (!shouldPersistAutoBackup(summary.conversationCount, summary.messageCount)) {
                        temp.delete()
                        return@withContext AutoBackupOutcome(ran = true, skippedEmpty = true)
                    }
                    if (!temp.renameTo(target)) {
                        throw IllegalStateException("备份文件改名失败：${target.name}")
                    }
                } catch (throwable: Throwable) {
                    temp.delete()
                    throw throwable
                }
                AutoBackupOutcome(
                    ran = true,
                    file = target,
                    pruned = prune(directory, keep),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                AndroidAgentLogger.error(
                    "Auto backup failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}",
                )
                AutoBackupOutcome(
                    ran = true,
                    error = failureReason(throwable),
                )
            }
        }

    /** 自动备份失败时的展示原因：不把英文 errno 直接摆给用户。 */
    internal fun failureReason(throwable: Throwable): String = when {
        StorageFailures.isOutOfSpace(throwable) -> "存储空间不足（清理后重试）"
        StorageFailures.isNotWritable(throwable) -> "备份目录不可写（只读或权限不足）"
        else -> throwable.message ?: throwable.safeLogType()
    }

    /** 同一分钟内可能被触发两次（启动 + 回到前台），撞名时追加 -2、-3。 */
    private fun uniqueFile(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val stem: String
        val extension: String
        // 后缀可能有两段（.json.gz），撞名时要插在最后一段之前，别拼出 .json-2.gz。
        if (name.endsWith(".json.gz")) {
            extension = "json.gz"
            stem = name.removeSuffix(".$extension")
        } else {
            extension = name.substringAfterLast('.', "")
            stem = if (extension.isEmpty()) name else name.removeSuffix(".$extension")
        }
        var index = 2
        while (true) {
            val candidate = File(directory, if (extension.isEmpty()) "$stem-$index" else "$stem-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
