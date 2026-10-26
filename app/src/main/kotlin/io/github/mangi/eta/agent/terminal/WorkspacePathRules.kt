package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * 会话工作区的路径规则。
 *
 * `working_directory` 工具与界面的「选择文件夹」必须用同一套判断：只在一处放宽或收紧，
 * 就会出现「界面能选、任务里却报 INVALID_WORKSPACE」这种自相矛盾。
 */
internal object WorkspacePathRules {
    /**
     * 系统目录不能作为工作区。
     *
     * 文件工具会按工作区批量读写与搜索，指到这些目录等于把系统直接暴露给模型。
     */
    val blockedRoots: Set<String> = setOf("/", "/system", "/data", "/proc", "/sys", "/dev", "/vendor", "/apex")

    /**
     * 共享存储上的路径是否需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。
     *
     * targetSdk 33+ 之后只能靠这个权限访问其他应用创建的文件；没有它时
     * `File.isDirectory` / `canWrite` 一律返回 false，看起来像「目录不存在」。
     * 这里只做纯字符串判断，方便单测；调用方再叠加 `Environment.isExternalStorageManager()`。
     */
    fun requiresAllFilesAccess(path: String, primaryRoot: String): Boolean {
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        if (normalized == primaryRoot || normalized.startsWith("$primaryRoot/")) return true
        return REMOVABLE_VOLUME.containsMatchIn(normalized)
    }

    /**
     * 系统禁止普通应用进入的目录：即便有「所有文件访问」也打不开。
     *
     * 典型是其他应用的 `Android/data`、`Android/obb`——把它们当工作区只会得到满屏 EACCES。
     */
    fun isRestrictedSharedPath(path: String, primaryRoot: String): Boolean {
        val relative = when {
            path == primaryRoot -> return false
            path.startsWith("$primaryRoot/") -> path.removePrefix("$primaryRoot/")
            else -> REMOVABLE_VOLUME.find(path)?.let { path.substring(it.range.last + 1) } ?: return false
        }.trim('/')
        return relative == "Android/data" || relative == "Android/obb" ||
            relative.startsWith("Android/data/") || relative.startsWith("Android/obb/")
    }

    private val REMOVABLE_VOLUME = Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(/|$)")

    /** 校验一个已存在的目录；通过返回规范路径，否则抛出 IllegalArgumentException。 */
    fun validateExistingDirectory(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.isDirectory) { "工作区必须是目录" }
        require(canonical.canWrite()) { "工作区不可写" }
        require(canonical.path !in blockedRoots) { "不能把系统目录设为工作区" }
        return canonical.path
    }
}
