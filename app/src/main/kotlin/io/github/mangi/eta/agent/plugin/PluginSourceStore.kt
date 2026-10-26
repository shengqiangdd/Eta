package io.github.mangi.eta.agent.plugin

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * 插件「从哪来」的记录。
 *
 * 为什么要单独记：manifest 只有 id / 版本，装完就再也看不出这个插件是谁给的。
 * 链接安装之后，用户想核实来源、想「从同一个链接更新」，都离不开这一条记录
 * （DSHA 把来源写进 install journal，是同一个考虑）。
 *
 * 存放位置：`files/plugins/.sources.json` —— 与插件目录同级但**是文件不是目录**，
 * 安装器只把 plugins 目录下的子目录当插件，不会把它误读成插件。
 */
internal class PluginSourceStore(
    private val pluginsRoot: File,
) {
    data class Source(
        /** [KIND_LINK]（从 URL 下载）或 [KIND_PATH]（本地文件 / 目录导入）。 */
        val kind: String,
        /** 链接安装是 URL；本地导入是 content:// 或绝对路径。 */
        val location: String,
        /** 链接安装的来源主机；本地导入为空。 */
        val host: String,
        val sha256: String,
        val version: String,
        val installedAtMillis: Long,
    ) {
        val isLink: Boolean get() = kind == KIND_LINK
    }

    private val storeFile = File(pluginsRoot, STORE_FILE)
    private val lock = Any()

    fun load(): Map<String, Source> = synchronized(lock) {
        val raw = runCatching { storeFile.takeIf(File::isFile)?.readText() }.getOrNull()
            ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, Source>()
        json.keys().forEach { id ->
            val entry = json.optJSONObject(id) ?: return@forEach
            val kind = entry.optString("kind")
            if (kind != KIND_LINK && kind != KIND_PATH) return@forEach
            result[id] = Source(
                kind = kind,
                location = entry.optString("location"),
                host = entry.optString("host"),
                sha256 = entry.optString("sha256"),
                version = entry.optString("version"),
                installedAtMillis = entry.optLong("installedAt"),
            )
        }
        result
    }

    fun get(pluginId: String): Source? = load()[pluginId]

    fun record(pluginId: String, source: Source) = synchronized(lock) {
        val next = load().toMutableMap()
        next[pluginId] = source
        write(next)
    }

    fun forget(pluginId: String) {
        synchronized(lock) {
            val current = load()
            if (!current.containsKey(pluginId)) return@synchronized
            write(current - pluginId)
        }
    }

    /**
     * 清掉插件目录里已不存在的记录（卸载残留、或用户手工删了目录）。
     *
     * 不清理的话，界面会给一个已经不存在的插件显示来源，甚至允许「从来源更新」，
     * 而更新目标根本不存在。
     */
    fun prune(installedIds: Set<String>) = synchronized(lock) {
        val current = load()
        val next = current.filterKeys { it in installedIds }
        if (next.size != current.size) write(next)
    }

    /**
     * 把来源记录对齐到「当前磁盘上真的装着什么」，返回对齐后的表。
     *
     * 为什么需要自愈：记录里的 `sha256` 与 `installedAt` 是**某一版**的包摘要与安装时间，
     * 它们只有在 `version` 与当前安装版本一致时才成立。fix19 之前的回滚不写来源，
     * 于是回滚到 1.0.0 之后详情仍在说「v1.1.0 的摘要、v1.1.0 的安装时间」——
     * 数据是真的，但属于另一版，用户看到的是一个对不上的校验值。
     *
     * 处理规则（只在版本对不上时动手，不猜）：
     * - 插件目录里已不存在 → 删掉记录（同 [prune]）；
     * - 记录版本为空 → 只补上当前版本，保留摘要与时间（旧记录没写版本，但装完没换过）；
     * - 记录版本 ≠ 当前版本 → **摘要与安装时间作废**（置空/0），版本改成当前版本。
     *   界面据此显示「未记录」，而不是拿上一版的数字冒充。
     *
     * @param installedVersions 插件 id → 当前安装版本（来自磁盘上的 manifest）。
     */
    fun reconcile(installedVersions: Map<String, String>): Map<String, Source> = synchronized(lock) {
        val current = load()
        if (current.isEmpty()) return@synchronized current
        var changed = false
        val next = LinkedHashMap<String, Source>(current.size)
        current.forEach { (id, source) ->
            val installed = installedVersions[id]?.trim()
            if (installed.isNullOrEmpty()) {
                // 插件目录里没有这个 id：记录是卸载残留。
                changed = true
                return@forEach
            }
            if (source.version.isBlank()) {
                next[id] = source.copy(version = installed)
                changed = true
                return@forEach
            }
            if (source.version.equals(installed, ignoreCase = true)) {
                next[id] = source
            } else {
                next[id] = source.copy(version = installed, sha256 = "", installedAtMillis = 0L)
                changed = true
            }
        }
        if (changed) write(next)
        next
    }

    private fun write(entries: Map<String, Source>) {
        val json = JSONObject()
        entries.forEach { (id, source) ->
            json.put(
                id,
                JSONObject()
                    .put("kind", source.kind)
                    .put("location", source.location)
                    .put("host", source.host)
                    .put("sha256", source.sha256)
                    .put("version", source.version)
                    .put("installedAt", source.installedAtMillis),
            )
        }
        pluginsRoot.mkdirs()
        val text = json.toString()
        val tmp = File(pluginsRoot, "$STORE_FILE.tmp")
        tmp.writeText(text)
        // 先写临时文件再替换：进程被杀时不会留下半份 JSON（半份会让整张表读不出来）。
        val moved = runCatching {
            Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            // 原子替换不可用时退回直接覆盖：这只是一份来源说明，坏了也不影响插件本体。
            storeFile.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val STORE_FILE = ".sources.json"
        const val KIND_LINK = "link"
        const val KIND_PATH = "path"
    }
}
