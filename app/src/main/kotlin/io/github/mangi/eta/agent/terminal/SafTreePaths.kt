package io.github.mangi.eta.agent.terminal

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * 把系统的「选择文件夹」（SAF tree URI）映射成真实文件系统路径。
 *
 * 终端与文件工具只认路径，`content://` 交给 shell 每次都会先失败一次，所以这里只接受能映射的提供者：
 * - externalstorage：`primary:Download/demo` → `<主存储>/Download/demo`，
 *   `1A2B-3C4D:project` → `/storage/1A2B-3C4D/project`，`home:notes` → `<主存储>/Documents/notes`；
 * - downloads：只有 `raw:/storage/...` 形式带真实路径；`msf:` 是媒体库 id，无法映射。
 *
 * 网盘、文档等其他提供者一律返回 null，由界面明确提示「这个文件夹没有文件系统路径」。
 */
internal object SafTreePaths {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val RAW_PREFIX = "raw:"

    /** 选择结果转为路径；不是真实目录或无法映射时返回 null。 */
    fun toFilesystemPath(uri: Uri): String? {
        if (uri.scheme != "content") return null
        val documentId = treeDocumentId(uri.pathSegments) ?: return null
        val path = when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> pathForDocumentId(documentId, primaryRoot())
            DOWNLOADS_AUTHORITY -> documentId
                .takeIf { it.startsWith(RAW_PREFIX) }
                ?.removePrefix(RAW_PREFIX)
                ?.takeIf { it.startsWith('/') }
            else -> null
        } ?: return null
        // 这里只做映射，不判存在性：targetSdk 33+ 没有「所有文件访问」时，
        // File.isDirectory 对共享存储恒为 false，会把真实目录误报成「没有文件系统路径」，
        // 掩盖真正的原因（权限）。存在性与可写性交给 WorkspaceFolderValidator 判。
        return path
    }

    /**
     * 打开选择器时的起始位置：当前工作区所在的文档。
     *
     * 映射不出来（例如工作区在应用私有目录）就返回 null，让系统用默认位置，不硬编一个不存在的目录。
     */
    fun initialUri(path: String): Uri? {
        val documentId = documentIdForPath(path, primaryRoot()) ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        }.getOrNull()
    }

    /**
     * tree URI 的路径段形如 `["tree", "primary:Download"]`；子文档是
     * `["tree", "primary:Download", "document", "primary:Download/demo"]`，都取 tree 后面那段。
     */
    internal fun treeDocumentId(segments: List<String>): String? {
        if (segments.isEmpty()) return null
        val treeIndex = segments.indexOf("tree")
        val index = if (treeIndex >= 0) treeIndex + 1 else segments.lastIndex
        return segments.getOrNull(index)?.takeIf { it.isNotBlank() }
    }

    /** `卷:相对路径` → 绝对路径；卷名按主存储 / home / 可移动存储解析。 */
    internal fun pathForDocumentId(documentId: String, primaryRoot: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator).trim()
        if (volume.isEmpty()) return null
        val relative = documentId.substring(separator + 1).trim('/')
        // 允许子目录，但不接受 `..`：避免借文档 id 跳出所选目录。
        if (relative.split('/').any { it == ".." }) return null
        val base = when {
            volume.equals("primary", ignoreCase = true) -> File(primaryRoot)
            volume.equals("home", ignoreCase = true) -> File(primaryRoot, "Documents")
            else -> File("/storage", volume)
        }
        return if (relative.isEmpty()) base.absolutePath else File(base, relative).absolutePath
    }

    /**
     * 绝对路径 → externalstorage 文档 id（[pathForDocumentId] 的逆运算）。
     *
     * 只覆盖 `primary` 与可移动卷；`/storage/emulated/1`（其他用户）、`/data/local/tmp`
     * 这类没有对应文档 id 的路径返回 null。
     */
    internal fun documentIdForPath(path: String, primaryRoot: String): String? {
        val absolute = File(path).absolutePath.trimEnd('/').ifEmpty { "/" }
        if (absolute == primaryRoot) return "primary:"
        if (absolute.startsWith("$primaryRoot/")) {
            return "primary:" + absolute.removePrefix("$primaryRoot/")
        }
        if (!absolute.startsWith("/storage/")) return null
        val rest = absolute.removePrefix("/storage/")
        val volume = rest.substringBefore('/')
        if (volume.isEmpty() || volume == "emulated" || volume == "self") return null
        val tail = rest.substringAfter('/', "")
        return if (tail.isEmpty()) "$volume:" else "$volume:$tail"
    }

    private fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
