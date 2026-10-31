package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal data class TerminalEnvEntry(val key: String, val value: String)

/** 共享存储的覆盖源：只读，不存在就当作没有覆盖。 */
internal const val DOWNLOAD_ENV_CONF = "/storage/emulated/0/Download/eta-env.conf"

internal enum class TerminalEnvValidation {
    OK,
    EMPTY_KEY,
    INVALID_KEY,
    BLOCKED_KEY,
    INVALID_VALUE,
    TOO_LONG,
}

/**
 * 终端环境变量文件（参考 dsh 的 dsh-env.conf）。
 *
 * 读取顺序：应用私有 `files/terminal-user/env.conf`（旧版 `files/terminal/env.conf` 只读兼容）
 * → `/sdcard/Download/eta-env.conf`，后读到的同名键覆盖先读到的。每行 `KEY=VALUE`，`#` 注释。
 *
 * 旧路径 `files/terminal` 可能由早期 Root 终端创建为 root:root 755，App 无法写入；
 * 设置页保存失败就是它。新路径位于 App 独占的 `terminal-user`，不会再被 Root 属主卡住。
 *
 * 只导出安全的键：拒绝 LD_PRELOAD / LD_LIBRARY_PATH（可被用来劫持动态链接），
 * 其余键名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`。设置页编辑的是应用私有文件；
 * Download 文件是只读覆盖源，避免 UI 改了却不生效的误解。
 */
internal class TerminalEnvFile(
    private val privateDir: File?,
    /**
     * 共享存储的覆盖源。
     *
     * 改成可注入的：以前是写死的常量，单元测试跑在真机文件系统上时会把用户真写的
     * `Download/eta-env.conf` 读进来，于是「无文件时应为空」这类用例变成环境依赖。
     */
    private val downloadSource: File = File(DOWNLOAD_ENV_CONF),
) {
    fun privateEntries(): List<TerminalEnvEntry> {
        privateFile()?.takeIf { it.isFile }?.let { return readEntries(it) }
        // 旧版文件只读兼容：读取可以，保存统一写到 App 独占的新路径。
        return readEntries(legacyPrivateFile())
    }

    fun downloadEntries(): List<TerminalEnvEntry> = readEntries(downloadFile())

    fun downloadFileExists(): Boolean = downloadFile().exists()

    fun downloadFileReadable(): Boolean = downloadFile().let { it.isFile && it.canRead() }

    fun effectiveEntries(): List<TerminalEnvEntry> {
        val entries = LinkedHashMap<String, String>()
        privateEntries().forEach { entries[it.key] = it.value }
        downloadEntries().forEach { entries[it.key] = it.value }
        return entries.map { TerminalEnvEntry(it.key, it.value) }
    }

    fun validateEntry(key: String, value: String): TerminalEnvValidation = when {
        key.isEmpty() -> TerminalEnvValidation.EMPTY_KEY
        key.length > TerminalEnvRules.MAX_KEY_CHARS ||
            !TerminalEnvRules.KEY_PATTERN.matches(key) -> TerminalEnvValidation.INVALID_KEY
        key in TerminalEnvRules.BLOCKED_KEYS -> TerminalEnvValidation.BLOCKED_KEY
        value.any { it == '\n' || it == '\r' || it == '\u0000' } -> TerminalEnvValidation.INVALID_VALUE
        value.length > TerminalEnvRules.MAX_VALUE_CHARS -> TerminalEnvValidation.TOO_LONG
        else -> TerminalEnvValidation.OK
    }

    /** 重写应用私有 env.conf；任何一条不合法都整体失败，不会写入半份配置。 */
    fun savePrivateEntries(entries: List<TerminalEnvEntry>): Boolean {
        val normalized = LinkedHashMap<String, String>()
        for (entry in entries) {
            if (validateEntry(entry.key, entry.value) != TerminalEnvValidation.OK) return false
            normalized[entry.key] = entry.value
        }
        val file = privateFile() ?: return false
        return runCatching {
            val directory = file.parentFile
            directory?.mkdirs()
            // 目录与文件都按最小可见性落盘：这是本机上唯一存明文值的地方。
            runCatching {
                directory?.let {
                    Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rwx------"))
                }
            }
            val body = buildString {
                appendLine("# Eta 环境变量（Agent 终端启动时注入）")
                normalized.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
            }
            val temp = File(file.parentFile, "env.conf.tmp")
            createPrivateFile(temp)
            temp.writeText(body, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY) }
            true
        }.getOrDefault(false)
    }

    /**
     * 以 0600 创建文件（不支持 POSIX 权限时退化）。
     *
     * 先写普通临时文件再 chmod 有一个窗口：那几毫秒里别的身份能读到明文。
     * 崩溃遗留的 `env.conf.tmp` 会让 `createFile` 失败，所以先删再建。
     */
    private fun createPrivateFile(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
        val created = runCatching {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        }.isSuccess
        if (!created && !file.createNewFile()) throw java.io.IOException("无法创建 ${file.name}")
        runCatching { Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY) }
    }

    private fun privateFile(): File? = privateDir?.let { File(it, "terminal-user/env.conf") }

    /** 旧版 Root 终端留下的路径；只用于读取迁移，不再写入。 */
    private fun legacyPrivateFile(): File? = privateDir?.let { File(it, "terminal/env.conf") }

    private fun downloadFile(): File = downloadSource

    private fun readEntries(file: File?): List<TerminalEnvEntry> {
        if (file == null || !file.isFile || !file.canRead()) return emptyList()
        val entries = LinkedHashMap<String, String>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val index = line.indexOf('=')
                if (index <= 0) return@forEachLine
                val key = line.substring(0, index).trim()
                if (validateEntry(key, "") != TerminalEnvValidation.OK) return@forEachLine
                var value = line.substring(index + 1).trim()
                if (value.length >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'")))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                entries[key] = value
            }
        }
        return entries.map { TerminalEnvEntry(it.key, it.value) }
    }

    private companion object {
        val OWNER_ONLY: Set<java.nio.file.attribute.PosixFilePermission> =
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    }
}

/**
 * env.conf 的键名 / 取值规则：设置页校验、文件解析、终端注入共用一个实现。
 *
 * 以前只有 [TerminalEnvFile] 内部持有这些常量，注入端要重新判断一次就只能复制规则，
 * 一旦两边漂移就会出现「能存进去却导不出来」的怪现象。
 */
internal object TerminalEnvRules {
    val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** 可被用来劫持动态链接的变量，永远不导出。 */
    val BLOCKED_KEYS = setOf("LD_PRELOAD", "LD_LIBRARY_PATH")

    const val MAX_KEY_CHARS = 128
    const val MAX_VALUE_CHARS = 4096

    fun isInjectable(entry: TerminalEnvEntry): Boolean =
        entry.key.length <= MAX_KEY_CHARS &&
            KEY_PATTERN.matches(entry.key) &&
            entry.key !in BLOCKED_KEYS &&
            entry.value.length <= MAX_VALUE_CHARS &&
            entry.value.none { it == '\n' || it == '\r' || it == '\u0000' }
}
