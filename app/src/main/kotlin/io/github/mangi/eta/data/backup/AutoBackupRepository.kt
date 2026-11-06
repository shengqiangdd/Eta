package io.github.mangi.eta.data.backup

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 自动备份的读写入口。
 *
 * 界面只看 [settingsFlow]，启动时机只调 [runIfDue]，两边共用同一份状态 ——
 * 避免「界面说上次备份在两小时前、实际文件根本没生成」这类对不上的情况。
 */
internal object AutoBackupRepository {

    fun settingsFlow(): Flow<AutoBackupSettings> =
        SettingsDataStore.settingsFlow()
            .map { it.autoBackup }
            .distinctUntilChanged()

    suspend fun settings(): AutoBackupSettings = SettingsDataStore.settings().autoBackup

    suspend fun setEnabled(enabled: Boolean) {
        SettingsDataStore.updateAutoBackup { it.copy(enabled = enabled) }
    }

    suspend fun setIntervalHours(hours: Int) {
        val allowed = AutoBackupScheduler.INTERVAL_CHOICES
        val normalized = if (hours in allowed) hours else AutoBackupSettings.DEFAULT_INTERVAL_HOURS
        SettingsDataStore.updateAutoBackup { it.copy(intervalHours = normalized) }
    }

    /**
     * 到期且当前空闲时备份一次。
     *
     * 返回 null 表示这次什么都没做：没开启、没到期、或正在跑任务。
     * 「正在跑任务」时**不**写 lastBackupAt —— 否则用户刚好在长任务里待一天，
     * 会自动备份永远被跳过却显示「已备份」。
     */
    suspend fun runIfDue(
        context: Context,
        busy: Boolean,
        now: Long = System.currentTimeMillis(),
    ): AutoBackupOutcome? {
        val current = settings()
        if (!current.enabled) return null
        if (busy) return null
        if (!AutoBackupScheduler.isDue(current.lastBackupAt, current.intervalHours, now)) return null
        return execute(context)
    }

    /** 用户手动点「立即备份」：不看间隔，也不看是否正在跑任务（界面已提示）。 */
    suspend fun runNow(context: Context): AutoBackupOutcome = execute(context)

    private suspend fun execute(context: Context): AutoBackupOutcome {
        val outcome = AutoBackupScheduler.run(context)
        SettingsDataStore.updateAutoBackup { current ->
            if (outcome.error != null) {
                // 失败不动 lastBackupAt：下次回前台还要再试，不能因为一次失败就等满一整个间隔。
                current.copy(lastError = outcome.error)
            } else {
                current.copy(
                    lastBackupAt = System.currentTimeMillis(),
                    // 空库跳过一次备份时不要抹掉上一份有效路径，否则界面会显示「备份过但没有文件」。
                    lastBackupPath = outcome.file?.absolutePath ?: current.lastBackupPath,
                    lastError = null,
                )
            }
        }
        return outcome
    }
}
