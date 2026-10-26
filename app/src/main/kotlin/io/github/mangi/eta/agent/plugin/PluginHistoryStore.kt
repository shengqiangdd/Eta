package io.github.mangi.eta.agent.plugin

import java.io.File
import org.json.JSONObject

/**
 * 覆盖安装前的历史留底（多版本）。
 *
 * 为什么需要：覆盖安装（含「从来源更新」）会把旧目录直接删掉，
 * 新版跑不起来时用户手上什么都没有 —— 只能重新找来源，甚至找不到旧版本。
 *
 * 为什么是「多版本」而不只是「上一版」：只留一份时，回滚一次就把更早的版本覆盖掉了。
 * 用户从 1.3.0 退到 1.2.0 之后想再退到 1.1.0，数据已经没了 ——
 * 而「连着两个新版本都有问题」恰恰是最需要连续回滚的场景。
 *
 * 布局（每个版本自包含，目录名可离线恢复，不依赖单独的索引文件）：
 * ```
 * plugin-history/<id>/v<version>~<backedUpAt>/
 *     meta.json      {"version":"1.2.0","backedUpAt":1700000000000}
 *     payload/…      完整插件目录
 * ```
 * 不另建索引是刻意的：索引与目录一旦不同步，就会出现「界面说有留底、磁盘上已经没了」。
 * 目录名里带版本与时间，即使 meta.json 损坏也能从名字兜底。
 *
 * 兼容：fix17/fix18 的单版本布局（`meta.json` + `payload/`）在第一次读取时就地迁移，
 * 迁移成功才删旧文件；迁移失败仍按旧格式只读，绝不让已有留底凭空消失。
 */
internal class PluginHistoryStore(
    private val root: File,
    private val maxVersions: Int = MAX_VERSIONS,
) {
    data class Entry(
        val version: String,
        val backedUpAtMillis: Long,
        /** 可直接交给 [PluginPackageInstaller.installLocalDir] 的目录。 */
        val payloadDir: File,
    )

    private val lock = Any()

    /** 全部留底，新的在前。 */
    fun list(pluginId: String): List<Entry> = synchronized(lock) {
        if (!SAFE_ID.matches(pluginId)) return emptyList()
        readEntries(File(root, pluginId))
    }

    /** 最新一份；给只需要「上一版」的调用方（CLI、旧调用点）用。 */
    fun get(pluginId: String): Entry? = list(pluginId).firstOrNull()

    /** 指定版本；回滚到「不是最新那份」时用它。 */
    fun getVersion(pluginId: String, version: String): Entry? {
        val wanted = version.trim()
        if (wanted.isEmpty()) return null
        return list(pluginId).firstOrNull { it.version.equals(wanted, ignoreCase = true) }
    }

    /**
     * 把 [sourceDir] 留底为 [version]。
     *
     * 同一版本只保留最新一份（内容相同，回滚起来没有区别）；超出 [maxVersions] 时丢最旧的。
     * 先写暂存目录再改名：中途失败不会留下一个「看起来能用、实际缺文件」的留底。
     */
    fun remember(
        pluginId: String,
        sourceDir: File,
        version: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val cleaned = version.trim()
        if (!SAFE_ID.matches(pluginId) || cleaned.isEmpty() || !sourceDir.isDirectory) return false
        val dir = File(root, pluginId)
        if (!dir.exists() && !dir.mkdirs()) return false
        // 旧布局没迁移成功就先别写：下面按版本去重会把仅剩的旧留底删掉。
        if (!migrateLegacy(dir)) return false
        val target = File(dir, entryName(cleaned, nowMillis))
        val staging = File(dir, target.name + TMP_SUFFIX)
        val ok = runCatching {
            if (staging.exists() && !staging.deleteRecursively()) error("staging")
            if (!staging.mkdirs()) error("staging")
            sourceDir.copyRecursively(File(staging, PAYLOAD_DIR), overwrite = true)
            File(staging, META_FILE).writeText(metaJson(cleaned, nowMillis))
            entriesIn(dir)
                .filter { it.version.equals(cleaned, ignoreCase = true) }
                .forEach { deleteEntry(it) }
            if (target.exists() && !target.deleteRecursively()) error("replace")
            if (!staging.renameTo(target)) error("rename")
            trim(dir)
            true
        }.getOrDefault(false)
        if (!ok) runCatching { staging.deleteRecursively() }
        ok
    }

    fun forget(pluginId: String): Boolean = synchronized(lock) {
        if (!SAFE_ID.matches(pluginId)) return false
        val dir = File(root, pluginId)
        if (!dir.exists()) return true
        runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 清掉已经不存在的插件的留底：卸载后残留会白占空间，也可能被误当成可回滚目标。 */
    fun prune(installedIds: Set<String>) {
        synchronized(lock) {
            val children = root.listFiles() ?: return
            children.forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.name !in installedIds) {
                    runCatching { child.deleteRecursively() }
                }
            }
        }
    }

    private fun readEntries(dir: File): List<Entry> {
        if (!dir.isDirectory) return emptyList()
        val migrated = migrateLegacy(dir)
        val result = entriesIn(dir).toMutableList()
        if (!migrated) {
            // 只有旧布局且迁移失败时才走这里：仍然让用户看得到、回滚得了。
            legacyEntry(dir)?.let { legacy ->
                val duplicate = result.any {
                    it.version.equals(legacy.version, ignoreCase = true) &&
                        it.backedUpAtMillis == legacy.backedUpAtMillis
                }
                if (!duplicate) result += legacy
            }
        }
        return result.sortedByDescending { it.backedUpAtMillis }
    }

    private fun entriesIn(dir: File): List<Entry> =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.endsWith(TMP_SUFFIX) }
            ?.mapNotNull { entryOf(it) }
            ?.sortedByDescending { it.backedUpAtMillis }
            ?.toList()
            ?: emptyList()

    private fun entryOf(entryDir: File): Entry? {
        val payload = File(entryDir, PAYLOAD_DIR)
        if (!payload.isDirectory) return null
        val meta = File(entryDir, META_FILE)
        val parsed = runCatching { parseMeta(meta.readText()) }.getOrNull()
        val fromName = parseEntryName(entryDir.name)
        val version = parsed?.first ?: fromName?.first ?: return null
        val at = parsed?.second?.takeIf { it > 0L } ?: fromName?.second ?: 0L
        return Entry(version, at, payload)
    }

    /** 旧单版本布局：`<id>/meta.json` + `<id>/payload`。 */
    private fun legacyEntry(dir: File): Entry? {
        val payload = File(dir, PAYLOAD_DIR)
        val meta = File(dir, META_FILE)
        if (!payload.isDirectory || !meta.isFile) return null
        val parsed = runCatching { parseMeta(meta.readText()) }.getOrNull() ?: return null
        return Entry(parsed.first, parsed.second, payload)
    }

    /**
     * 把旧单版本布局就地迁移成多版本布局。
     *
     * 返回 false 表示「旧数据还在、但没能迁移」——调用方必须当作只读处理，
     * 不能用覆盖式的操作把它抹掉。
     */
    private fun migrateLegacy(dir: File): Boolean {
        val oldPayload = File(dir, PAYLOAD_DIR)
        val oldMeta = File(dir, META_FILE)
        if (!oldPayload.isDirectory || !oldMeta.isFile) return true
        val parsed = runCatching { parseMeta(oldMeta.readText()) }.getOrNull() ?: return false
        val (version, at) = parsed
        val target = File(dir, entryName(version, at))
        return runCatching {
            if (target.exists()) {
                // 同一份留底已经在多版本布局里：旧文件是重复的。
                oldPayload.deleteRecursively()
                oldMeta.delete()
                return@runCatching true
            }
            if (!target.mkdirs()) error("mkdir")
            val moved = oldPayload.renameTo(File(target, PAYLOAD_DIR))
            if (!moved) {
                oldPayload.copyRecursively(File(target, PAYLOAD_DIR), overwrite = true)
                if (!oldPayload.deleteRecursively()) error("cleanup")
            }
            File(target, META_FILE).writeText(metaJson(version, at))
            if (!oldMeta.delete()) error("meta")
            true
        }.getOrDefault(false)
    }

    private fun trim(dir: File) {
        val keep = maxVersions.coerceAtLeast(1)
        entriesIn(dir).sortedByDescending { it.backedUpAtMillis }.drop(keep).forEach {
            runCatching { deleteEntry(it) }
        }
        dir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(TMP_SUFFIX) }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun deleteEntry(entry: Entry) {
        entry.payloadDir.parentFile?.let { runCatching { it.deleteRecursively() } }
    }

    private fun entryName(version: String, at: Long): String {
        val safe = version
            .map { c -> if (c.isLetterOrDigit() || c in SAFE_CHARS) c else '_' }
            .joinToString("")
            .take(MAX_NAME_CHARS)
        return "v$safe~${at.coerceAtLeast(0L)}"
    }

    private fun parseEntryName(name: String): Pair<String, Long>? {
        if (!name.startsWith("v")) return null
        val sep = name.lastIndexOf('~')
        if (sep <= 1) return null
        val version = name.substring(1, sep).trim()
        if (version.isEmpty()) return null
        val at = name.substring(sep + 1).toLongOrNull() ?: return null
        return version to at
    }

    internal companion object {
        const val META_FILE = "meta.json"
        const val PAYLOAD_DIR = "payload"
        const val TMP_SUFFIX = ".tmp"

        /** 默认保留的版本数：够覆盖「连着两次更新都有问题」，又不会让留底无限长。 */
        const val MAX_VERSIONS = 5

        private const val MAX_NAME_CHARS = 48
        private const val SAFE_CHARS = "._+-"

        /** 与 manifest 的 id 规则保持一致：留底目录名必须能安全拼进路径。 */
        val SAFE_ID = Regex(PluginManifest.ID_PATTERN)

        fun metaJson(version: String, backedUpAtMillis: Long): String =
            JSONObject().put("version", version).put("backedUpAt", backedUpAtMillis).toString()

        /** 返回 (version, backedUpAt)；缺版本号视为无效留底。 */
        fun parseMeta(text: String): Pair<String, Long>? {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val version = json.optString("version").trim()
            if (version.isEmpty()) return null
            return version to json.optLong("backedUpAt")
        }
    }
}
