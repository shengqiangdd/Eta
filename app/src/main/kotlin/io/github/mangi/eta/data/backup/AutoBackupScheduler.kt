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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次自动备份的结果；[ran] 为 false 表示这次根本没有到期，什么都没做。 */
internal data class AutoBackupOutcome(
    val ran: Boolean,
    val file: File? = null,
    val pruned: Int = 0,
    val error: String? = null,
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

    /** 可选的间隔（小时）。 */
    val INTERVAL_CHOICES = listOf(6, 12, 24, 72, 168)

    const val DEFAULT_INTERVAL_HOURS = 24

    private const val DOWNLOADS_SUBDIR = "Eta"
    private const val BACKUPS_SUBDIR = "backups"
    private const val TEMP_SUFFIX = ".part"

    /** 自动备份目录：`<外部存储>/Download/Eta/backups`。 */
    fun backupDirectory(): File {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, DOWNLOADS_SUBDIR), BACKUPS_SUBDIR)
    }

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

    /** 只保留最近 [keep] 份自动备份，返回实际删掉的份数。 */
    fun prune(directory: File, keep: Int): Int {
        var removed = 0
        autoBackups(directory).drop(keep.coerceAtLeast(0)).forEach { stale ->
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
            val directory = backupDirectory()
            try {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    return@withContext AutoBackupOutcome(
                        ran = true,
                        error = "无法创建备份目录：${directory.absolutePath}",
                    )
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val target = uniqueFile(directory, "$FILE_PREFIX$stamp.eta-backup.json")
                val temp = File(directory, target.name + TEMP_SUFFIX)
                try {
                    FileOutputStream(temp).use { output ->
                        EtaBackupRepository.export(context, output)
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
                    error = throwable.message ?: throwable.safeLogType(),
                )
            }
        }

    /** 同一分钟内可能被触发两次（启动 + 回到前台），撞名时追加 -2、-3。 */
    private fun uniqueFile(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = File(directory, if (extension.isEmpty()) "$stem-$index" else "$stem-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
