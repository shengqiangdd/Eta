package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val autoBackup: AutoBackupSettings = AutoBackupSettings(),
)

/**
 * 自动备份设置。
 *
 * 默认开启：用户不会主动想起来手动导出，而数据丢失不可逆。界面把开关、间隔和
 * 「上次备份」都摆出来，随时可以关掉。
 */
@Serializable
data class AutoBackupSettings(
    val enabled: Boolean = true,
    val intervalHours: Int = 24,
    /** 上次成功备份的时间戳；0 表示从未备份。 */
    val lastBackupAt: Long = 0L,
    /** 上次成功备份的文件路径。 */
    val lastBackupPath: String? = null,
    /** 上次失败原因；成功后清空。 */
    val lastError: String? = null,
) {
    companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
    }
}
