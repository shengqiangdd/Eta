package io.github.mangi.eta.ui.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 插件详情里的两处纯展示逻辑：安装时间、包摘要。
 *
 * 为什么单独抽：这两种值都有"合法的没有"——本地导入的插件没有下载摘要、
 * 旧记录没有安装时间。界面必须能区分「未记录」和「1970-01-01 08:00」/空字符串，
 * 而这类边界只有纯函数测得动（真机上很难造出旧记录）。
 */
internal object PluginDetails {

    private val installedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 没有记录（<=0）返回 null，由界面显示"未记录"，而不是格式化成一个假时间。 */
    fun formatInstalledAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (millis <= 0L) return null
        return runCatching {
            Instant.ofEpochMilli(millis).atZone(zone).format(installedAtFormatter)
        }.getOrNull()
    }

    /**
     * 是否提供回滚：留底版本必须存在且与当前版本不同。
     *
     * 相同版本的回滚点了也不会有任何变化，界面就不该给这个入口 ——
     * 用户会以为点坏了（真实场景：重复安装同一版本会刷新留底）。
     */
    fun canRollback(currentVersion: String, backupVersion: String?): Boolean {
        val backup = backupVersion?.trim().orEmpty()
        if (backup.isEmpty()) return false
        return !backup.equals(currentVersion.trim(), ignoreCase = true)
    }

    /**
     * 详情里的「来源」值。
     *
     * 列表行需要 `plugins_source_host`（"来源：主机"）自带前缀，因为那一行没有字段名；
     * 详情弹窗有「来源」标签，再用带前缀的文案就成了「来源：来源：127.0.0.1」。
     * 所以这里优先取主机名，本地导入退回整句，都没有则返回 null 由界面显示「未记录」。
     */
    fun sourceForDetails(host: String, label: String?): String? = when {
        host.isNotBlank() -> host
        !label.isNullOrBlank() -> label
        else -> null
    }

    /**
     * 详情里的能力清单：最多列 [limit] 条，其余只报数量。
     *
     * 为什么不在界面里直接 join：一个插件可以声明几十个工具，弹窗会撑出屏幕；
     * 但也不能干脆截断不报——用户会以为这个插件只有前几个能力。
     */
    data class Slice(val shown: List<String>, val hidden: Int)

    fun sliceForDetails(items: List<String>, limit: Int = 6): Slice {
        if (limit <= 0) return Slice(emptyList(), items.size)
        return if (items.size <= limit) {
            Slice(items, 0)
        } else {
            Slice(items.take(limit), items.size - limit)
        }
    }

    /** 摘要只显示前 [length] 位；短于它原样返回，空值返回 null。 */
    fun shortDigest(sha256: String, length: Int = 16): String? {
        val trimmed = sha256.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= length) trimmed else trimmed.take(length)
    }

    /**
     * 来源记录里的「包摘要 / 安装时间」是否属于当前安装的这一版。
     *
     * 一条来源记录同时记着地址、版本、摘要、安装时间；摘要是**那一版**的包算出来的。
     * 版本对不上时（典型：回滚到旧版），这些数字仍然"是真的"，但属于另一版 ——
     * 直接显示等于告诉用户"你现在装的就是这份包"，而校验和它对不上。
     *
     * 所以这两个值只在版本一致时展示，否则按「未记录」处理。
     * `PluginSourceStore.reconcile(...)` 会顺手把这种记录改对，这里是不依赖磁盘写入的兜底。
     */
    fun recordAppliesToVersion(recordVersion: String, installedVersion: String): Boolean {
        val record = recordVersion.trim()
        if (record.isEmpty()) return true
        return record.equals(installedVersion.trim(), ignoreCase = true)
    }
}
