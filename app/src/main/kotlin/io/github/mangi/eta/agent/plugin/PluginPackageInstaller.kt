package io.github.mangi.eta.agent.plugin

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件 ZIP 安装器。
 *
 * 安全契约（与 SkillPackageInstaller 同一套思路，但不复用其"一个 zip 一个 skill"假设）：
 * 1. **暂存**：先解到 `files/.eta-plugin-installer/<uuid>`，校验通过才进入正式目录。
 * 2. **路径**：拒绝越界路径、绝对路径、符号链接；条目数与单文件大小有上限。
 * 3. **校验**：`eta-plugin.json` 必须存在且能解析；id/version 合法。
 * 4. **事务**：移动失败时删除新目录；覆盖安装前备份旧目录，失败即恢复。
 * 5. **幂等**：重复安装同一版本视为成功（返回 alreadyInstalled），不报错也不重复落盘。
 */
internal class PluginPackageInstaller(
    private val pluginsRoot: File,
    private val limits: PluginLimits = PluginLimits(),
    /** 覆盖安装前把旧版本目录留一份到这里；null 表示不留底（单测与旧构造点）。 */
    private val historyRoot: File? = null,
) {
    data class PluginLimits(
        val maxArchiveBytes: Long = 64L * 1024L * 1024L,
        val maxExtractedBytes: Long = 256L * 1024L * 1024L,
        val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
        val maxEntries: Int = 4_096,
        val maxPathDepth: Int = 12,
    )

    sealed interface Result {
        data class Installed(
            val plugin: PluginManifest.Plugin,
            val sha256: String,
            val alreadyInstalled: Boolean,
        ) : Result

        data class Failed(val code: String, val message: String) : Result
    }

    private val canonicalRoot = pluginsRoot.canonicalFile
    private val workRoot = File(
        requireNotNull(canonicalRoot.parentFile) { "plugins 目录必须有父目录" },
        ".eta-plugin-installer",
    )
    private val installed = AtomicBoolean(false)

    fun installLocalZip(
        openStream: () -> InputStream,
        replaceExisting: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
    ): Result {
        if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
            return Result.Failed("PLUGIN_ROOT_UNWRITABLE", "无法创建插件目录")
        }
        if (!workRoot.exists()) workRoot.mkdirs()
        val stageId = java.util.UUID.randomUUID().toString()
        val stageDir = File(workRoot, stageId)
        val archiveFile = File(workRoot, "$stageId.zip")
        try {
            stageDir.mkdirs()
            var extractedBytes = 0L
            var entries = 0
            var manifestText: String? = null
            val digest = MessageDigest.getInstance("SHA-256")
            // 先把整份归档落到暂存文件，再解包。SHA-256 必须覆盖**整个归档字节**：
            // 下载端（PluginLinkInstaller）算的是文件摘要，而 ZipInputStream 只消费
            // 本地头与条目数据，读完最后一个条目就停下（实测 509 字节的包只读了 456），
            // 用它读过的字节当摘要，与预览里的 SHA 永远不相等 —— 从来源更新会稳定报
            // PLUGIN_REPLACEMENT_MISMATCH。落盘也让「安装的字节」就是「预览的那一份」。
            BufferedInputStream(openStream()).use { raw ->
                val counting = CountingInputStream(raw, limits.maxArchiveBytes) { bytes ->
                    digest.update(bytes, 0, bytes.size)
                }
                archiveFile.outputStream().buffered().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = counting.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            ZipInputStream(BufferedInputStream(archiveFile.inputStream())).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entries++
                        if (entries > limits.maxEntries) {
                            return fail(Result.Failed("ARCHIVE_TOO_MANY_ENTRIES", "条目数超过 ${limits.maxEntries}"), stageDir)
                        }
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                            return fail(
                                Result.Failed("ARCHIVE_UNSAFE_PATH", "归档包含不安全的路径：${name.take(120)}"),
                                stageDir,
                            )
                        }
                        val target = resolveWithin(stageDir, name)
                            ?: return fail(
                                Result.Failed("ARCHIVE_UNSAFE_PATH", "归档路径越界：${name.take(120)}"),
                                stageDir,
                            )
                        // 软链校验必须在写盘前：若同名软链已存在（覆盖安装或恶意暂存残留），
                        // 直接写会把数据写到根目录外面去。
                        if (Files.isSymbolicLink(target.toPath())) {
                            return fail(
                                Result.Failed(
                                    "ARCHIVE_SYMLINK_REJECTED",
                                    "归档目标已是符号链接，已拒绝：${name.take(120)}",
                                ),
                                stageDir,
                            )
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            val written = copyBounded(zip, target, limits.maxSingleFileBytes)
                            extractedBytes += written
                            if (extractedBytes > limits.maxExtractedBytes) {
                                return fail(
                                    Result.Failed("ARCHIVE_TOO_LARGE", "解包总量超过 ${limits.maxExtractedBytes} 字节"),
                                    stageDir,
                                )
                            }
                            if (name == PluginManifest.FILE_NAME ||
                                name.endsWith("/" + PluginManifest.FILE_NAME)
                            ) {
                                manifestText = target.readText()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            val manifest = manifestText
                ?: return fail(Result.Failed("MANIFEST_MISSING", "ZIP 中缺少 ${PluginManifest.FILE_NAME}"), stageDir)
            return commit(
                stageDir = stageDir,
                manifestText = manifest,
                sha = sha,
                replaceExisting = replaceExisting,
                expectedReplacementId = expectedReplacementId,
                expectedArchiveSha256 = expectedArchiveSha256,
            )
        } catch (io: IOException) {
            return fail(Result.Failed("ARCHIVE_TOO_LARGE", "归档超过 ${limits.maxArchiveBytes} 字节"), stageDir)
        } catch (throwable: Throwable) {
            return fail(
                Result.Failed("PLUGIN_INSTALL_FAILED", throwable.message ?: throwable.javaClass.simpleName),
                stageDir,
            )
        } finally {
            // 暂存归档只在本次安装里有意义；成功或失败都不该留在 .eta-plugin-installer 里。
            archiveFile.delete()
        }
    }

    sealed interface Inspection {
        data class Ready(
            val plugin: PluginManifest.Plugin,
            val entries: Int,
        ) : Inspection

        data class Rejected(val code: String, val message: String) : Inspection
    }

    /**
     * 只解析、不落盘：给「通过链接安装」的**预览**用。
     *
     * 为什么要单独一个方法：链接是外部内容，直接装进去的插件会往 Agent 里注入
     * MCP 服务器与提示词。参考 DSHA 的两段式（先 inspect 出预览、用户确认后才 commit），
     * 这里返回清单解析结果让界面摊开给用户看，确认后再走 [installLocalZip] 正式安装。
     *
     * 校验口径与安装一致：条目数上限、不安全路径、清单必须存在且能解析。
     * 唯一的区别是**不写盘**——预览失败不会在插件目录里留下半份东西。
     */
    fun inspectZip(openStream: () -> InputStream): Inspection {
        var manifestText: String? = null
        var entries = 0
        try {
            BufferedInputStream(openStream()).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entries++
                        if (entries > limits.maxEntries) {
                            return Inspection.Rejected(
                                "ARCHIVE_TOO_MANY_ENTRIES",
                                "条目数超过 ${limits.maxEntries}",
                            )
                        }
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                            return Inspection.Rejected(
                                "ARCHIVE_UNSAFE_PATH",
                                "归档包含不安全的路径：${name.take(120)}",
                            )
                        }
                        if (!entry.isDirectory &&
                            (name == PluginManifest.FILE_NAME ||
                                name.endsWith("/" + PluginManifest.FILE_NAME))
                        ) {
                            manifestText = readTextBounded(zip, PluginManifest.MAX_MANIFEST_BYTES)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (throwable: Throwable) {
            return Inspection.Rejected(
                "PLUGIN_INSPECT_FAILED",
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
        val text = manifestText
            ?: return Inspection.Rejected("MANIFEST_MISSING", "ZIP 中缺少 ${PluginManifest.FILE_NAME}")
        return when (val parsed = PluginManifest.parse(text)) {
            is PluginManifest.ParseResult.Success -> Inspection.Ready(parsed.plugin, entries)
            is PluginManifest.ParseResult.Failure -> Inspection.Rejected(parsed.code, parsed.message)
        }
    }

    private fun readTextBounded(input: InputStream, maxBytes: Int): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > maxBytes) {
                throw IOException("manifest 超过 $maxBytes 字节")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    /**
     * 从目录安装插件。
     *
     * 存在的理由很具体：Android 上没有 zip 命令（busybox / toybox 都没有 zip applet），
     * 而 Agent 造插件时只能写文件。要求它"先打包再安装"，创造流程就会卡在最后一步，
     * 或者逼它去编造一个不存在的打包命令。目录安装复用同一套安全契约：
     * 拒绝符号链接、拒绝越界路径、条目数与体积上限、同一事务提交与回滚。
     *
     * 与 zip 安装的差异只有一处：manifest 必须在目录根级（zip 允许它在任意层级）。
     * SHA-256 按"相对路径 + 内容"计算，与条目顺序无关，可直接用于覆盖安装比对。
     */
    fun installLocalDir(
        sourceDir: File,
        replaceExisting: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
    ): Result {
        if (Files.isSymbolicLink(sourceDir.toPath())) {
            return Result.Failed("PLUGIN_SYMLINK_REJECTED", "拒绝安装符号链接目录")
        }
        val source = runCatching { sourceDir.canonicalFile }.getOrNull()
            ?: return Result.Failed("PLUGIN_NOT_FOUND", "目录不可访问：${sourceDir.path}")
        if (!source.isDirectory) return Result.Failed("PLUGIN_NOT_FOUND", "不是目录：${source.path}")
        if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
            return Result.Failed("PLUGIN_ROOT_UNWRITABLE", "无法创建插件目录")
        }
        if (!workRoot.exists()) workRoot.mkdirs()
        val stageDir = File(workRoot, java.util.UUID.randomUUID().toString())
        return try {
            stageDir.mkdirs()
            val rootPath = source.toPath()
            val entries = ArrayList<LocalEntry>()
            var symlink: String? = null
            Files.walk(rootPath).use { stream ->
                stream.forEach { path ->
                    if (path == rootPath) return@forEach
                    val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                    if (Files.isSymbolicLink(path)) {
                        if (symlink == null) symlink = relative
                        return@forEach
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    entries += LocalEntry(relative, path.toFile(), runCatching { Files.size(path) }.getOrDefault(0L))
                }
            }
            val linked = symlink
            if (linked != null) {
                return fail(
                    Result.Failed("ARCHIVE_SYMLINK_REJECTED", "目录包含符号链接，已拒绝：${linked.take(120)}"),
                    stageDir,
                )
            }
            entries.sortBy { it.relative }
            if (entries.size > limits.maxEntries) {
                return fail(Result.Failed("ARCHIVE_TOO_MANY_ENTRIES", "条目数超过 ${limits.maxEntries}"), stageDir)
            }
            val unsafe = entries.firstOrNull {
                it.relative.contains("..") || it.relative.startsWith("/") || it.relative.contains("\\")
            }
            if (unsafe != null) {
                return fail(
                    Result.Failed("ARCHIVE_UNSAFE_PATH", "目录包含不安全的路径：${unsafe.relative.take(120)}"),
                    stageDir,
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            for (entry in entries) {
                if (entry.size > limits.maxSingleFileBytes) {
                    return fail(
                        Result.Failed("ARCHIVE_TOO_LARGE", "单文件超过 ${limits.maxSingleFileBytes} 字节"),
                        stageDir,
                    )
                }
                total += entry.size
                if (total > limits.maxExtractedBytes) {
                    return fail(
                        Result.Failed("ARCHIVE_TOO_LARGE", "目录总量超过 ${limits.maxExtractedBytes} 字节"),
                        stageDir,
                    )
                }
                val target = resolveWithin(stageDir, entry.relative)
                    ?: return fail(
                        Result.Failed("ARCHIVE_UNSAFE_PATH", "路径越界：${entry.relative.take(120)}"),
                        stageDir,
                    )
                target.parentFile?.mkdirs()
                digest.update(entry.relative.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                entry.file.inputStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            val manifestEntry = entries.firstOrNull { it.relative == PluginManifest.FILE_NAME }
                ?: return fail(
                    Result.Failed("MANIFEST_MISSING", "目录根级缺少 ${PluginManifest.FILE_NAME}"),
                    stageDir,
                )
            val manifestText = runCatching { manifestEntry.file.readText() }.getOrElse {
                return fail(Result.Failed("MANIFEST_INVALID_JSON", "manifest 不是 UTF-8 文本"), stageDir)
            }
            commit(
                stageDir = stageDir,
                manifestText = manifestText,
                sha = digest.digest().joinToString("") { "%02x".format(it) },
                replaceExisting = replaceExisting,
                expectedReplacementId = expectedReplacementId,
                expectedArchiveSha256 = expectedArchiveSha256,
            )
        } catch (throwable: Throwable) {
            fail(
                Result.Failed("PLUGIN_INSTALL_FAILED", throwable.message ?: throwable.javaClass.simpleName),
                stageDir,
            )
        }
    }

    private data class LocalEntry(val relative: String, val file: File, val size: Long)

    /** 暂存目录 → 正式目录的提交；zip 与目录安装共用同一套事务与回滚。 */
    private fun commit(
        stageDir: File,
        manifestText: String,
        sha: String,
        replaceExisting: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ): Result {
        val parsed = PluginManifest.parse(manifestText)
        if (parsed is PluginManifest.ParseResult.Failure) {
            return fail(Result.Failed(parsed.code, parsed.message), stageDir)
        }
        val plugin = (parsed as PluginManifest.ParseResult.Success).plugin
        val targetDir = File(canonicalRoot, plugin.id)
        if (targetDir.exists()) {
            if (!replaceExisting) {
                val existingVersion = readInstalledVersion(targetDir)
                if (existingVersion == plugin.version) {
                    deleteRecursively(stageDir)
                    return Result.Installed(plugin, sha, alreadyInstalled = true)
                }
                return Result.Failed(
                    "PLUGIN_ALREADY_INSTALLED",
                    "插件 ${plugin.id} 已安装（版本 $existingVersion）；覆盖安装需显式确认",
                )
            }
            val expectedId = expectedReplacementId?.trim().orEmpty()
            if (expectedId.isNotEmpty() && expectedId != plugin.id) {
                return fail(
                    Result.Failed(
                        "PLUGIN_REPLACEMENT_MISMATCH",
                        "确认替换的插件 id 与来源不一致：$expectedId vs ${plugin.id}",
                    ),
                    stageDir,
                )
            }
            val expectedSha = expectedArchiveSha256?.trim().orEmpty()
            if (expectedSha.isNotEmpty() && !expectedSha.equals(sha, ignoreCase = true)) {
                return fail(
                    Result.Failed("PLUGIN_REPLACEMENT_MISMATCH", "确认替换的 SHA-256 与本次不一致"),
                    stageDir,
                )
            }
        }

        // 版本号要在改名之前读：改名之后 targetDir 已经是新版本了。
        val previousVersion = if (targetDir.exists()) readInstalledVersion(targetDir) else null
        val backup = if (targetDir.exists()) File(workRoot, "backup-${stageDir.name}") else null
        if (backup != null && !targetDir.renameTo(backup)) {
            return fail(Result.Failed("PLUGIN_BACKUP_FAILED", "无法备份旧版本，已放弃安装"), stageDir)
        }
        if (!stageDir.renameTo(targetDir)) {
            // 恢复备份，保证用户原有的插件不丢。
            backup?.renameTo(targetDir)
            return fail(Result.Failed("PLUGIN_MOVE_FAILED", "无法把插件移入正式目录"), stageDir)
        }
        backup?.let { oldDir ->
            // 用旧目录留底：更新后发现新版有问题时还能退回上一版。
            val history = historyRoot
            val version = previousVersion
            if (history != null && !version.isNullOrBlank()) {
                PluginHistoryStore(history).remember(plugin.id, oldDir, version)
            }
            deleteRecursively(oldDir)
        }
        return Result.Installed(plugin, sha, alreadyInstalled = false)
    }

    fun uninstall(pluginId: String): Boolean {
        if (!Regex(PluginManifest.ID_PATTERN).matches(pluginId)) return false
        val target = File(canonicalRoot, pluginId)
        if (!target.exists()) return true
        return deleteRecursively(target)
    }

    /** 插件在磁盘上的实际目录；用于 `plugin_list` 回答「它到底装在哪」。 */
    fun pluginPath(pluginId: String): String? {
        if (!Regex(PluginManifest.ID_PATTERN).matches(pluginId)) return null
        return File(canonicalRoot, pluginId).takeIf { it.isDirectory }?.absolutePath
    }

    fun installedPlugins(): List<PluginManifest.Plugin> {
        val children = canonicalRoot.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory }.mapNotNull { dir ->
            val manifest = File(dir, PluginManifest.FILE_NAME)
            if (!manifest.isFile) return@mapNotNull null
            when (val parsed = PluginManifest.parse(manifest.readText())) {
                is PluginManifest.ParseResult.Success -> parsed.plugin
                is PluginManifest.ParseResult.Failure -> null
            }
        }.sortedBy { it.id }
    }

    private fun fail(result: Result.Failed, stageDir: File): Result.Failed {
        deleteRecursively(stageDir)
        return result
    }

    private fun resolveWithin(root: File, relative: String): File? {
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.size > limits.maxPathDepth) return null
        var current = root
        for (part in parts) {
            current = File(current, part)
            val path = current.toPath()
            // 软链组件一律拒绝：哪怕链接目标最终仍在根内，
            // 也意味着安装内容可以被外部预先布置的文件劫持。
            if (Files.isSymbolicLink(path)) return null
            val canonical = runCatching { current.canonicalFile }.getOrNull() ?: return null
            if (!canonical.path.startsWith(root.canonicalFile.path + File.separator) &&
                canonical.path != root.canonicalFile.path
            ) {
                return null
            }
        }
        return current
    }

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) throw IOException("single file too large")
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun readInstalledVersion(dir: File): String {
        val manifest = File(dir, PluginManifest.FILE_NAME)
        if (!manifest.isFile) return ""
        return runCatching {
            when (val parsed = PluginManifest.parse(manifest.readText())) {
                is PluginManifest.ParseResult.Success -> parsed.plugin.version
                is PluginManifest.ParseResult.Failure -> ""
            }
        }.getOrDefault("")
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteRecursively(child) }
        }
        return file.delete() || !file.exists()
    }

    private class CountingInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
        private val onChunk: (ByteArray) -> Unit,
    ) : InputStream() {
        private var total = 0L
        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                total++
                if (total > maxBytes) throw IOException("archive too large")
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) {
                total += read
                if (total > maxBytes) throw IOException("archive too large")
                onChunk(b.copyOfRange(off, off + read))
            }
            return read
        }

        override fun close() = delegate.close()
    }

    /** 供 UI/日志展示：把 manifest 的工具清单转成 JSON 数组。 */
    fun toolsJson(plugin: PluginManifest.Plugin): JSONArray =
        JSONArray().also { array ->
            plugin.tools.forEach { tool ->
                array.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description),
                )
            }
        }
}
