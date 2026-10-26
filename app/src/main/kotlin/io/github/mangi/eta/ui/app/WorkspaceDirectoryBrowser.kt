package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览结果的一行：目录或文件。
 *
 * `directory` 默认为 `true`，保持「只列目录」时的旧语义（工作区选文件夹）。
 */
internal data class WorkspaceDirectoryEntry(
    val path: String,
    val name: String,
    val directory: Boolean = true,
)

/**
 * 「选择工作区文件夹 / 选择要导入的文件」用的枚举器。
 *
 * 为什么不复用 [WorkspaceFileStore.list]：那个方法把结果限制在工作区根之内（防止任务
 * 越界读写），而选目录/选文件时必须能浏览根之外的其它目录，否则永远选不到别处。
 *
 * 为什么不走系统选择器：本机实测（Android 16 / OPLUS）系统 `OPEN_DOCUMENT_TREE` 与
 * `OpenMultipleDocuments` 都无法在虚拟屏工作 —— 默认 flags 下 WindowManager 把整个任务搬回
 * 主屏；加 `DEVICE_DISPLAY_GROUP` 后任务留在虚拟屏，但选择器窗口仍建在 display 0，两侧都不可见。
 * 自己列目录就能在任何显示、任何权限状态下工作。
 *
 * 有 Root 时走一次 shell：App 自身没有「所有文件访问」权限时连 `/storage/emulated/0`
 * 都 stat 不了（FUSE 直接拒绝），只有 root 身份才列得出来。
 */
internal class WorkspaceDirectoryBrowser {
    /**
     * 列举 [path] 下的条目。
     *
     * @param showHidden 是否包含点开头的条目。两种枚举方式必须给出同一结果 ——
     *   `File.listFiles()` 天生带隐藏项、`ls -1p` 天生不带，所以这里统一用 `ls -1pa`
     *   取全集，再在 Kotlin 侧按开关过滤。否则「有 Root / 没 Root」会看到两个不同的列表。
     * @param includeFiles 是否列出文件。选工作区文件夹时只列目录；导入时还要能选文件。
     */
    suspend fun list(
        path: String,
        showHidden: Boolean = false,
        includeFiles: Boolean = false,
    ): List<WorkspaceDirectoryEntry> = withContext(Dispatchers.IO) {
        val canonical = runCatching { File(path).canonicalPath }.getOrElse {
            throw IOException("DIRECTORY_UNAVAILABLE")
        }
        val raw = if (RootAccess.isGranted) {
            parseLsEntries(runLs(canonical))
        } else {
            fileEntries(canonical)
        }
        filterBrowseEntries(raw, showHidden, includeFiles)
            .map { WorkspaceDirectoryEntry(File(canonical, it.name).path, it.name, it.directory) }
    }

    private fun runLs(path: String): String {
        val quoted = shellQuote(path)
        val result = runCatching {
            runOneShotShell(
                ShellProcessSupervisor(),
                "root",
                // -a 连隐藏项一起给（过滤交给 Kotlin）；-p 给目录加尾斜杠；
                // 不用 find -printf（本机 busybox 不支持）。
                "if cd $quoted 2>/dev/null; then ls -1pa 2>/dev/null; else echo __ETA_DIR_UNAVAILABLE__; fi",
                timeoutSeconds = 10,
                maxOutputBytes = 256 * 1024,
            )
        }.getOrElse { throw IOException("DIRECTORY_UNAVAILABLE") }
        val text = String(result.output, Charsets.UTF_8)
        if (text.contains("__ETA_DIR_UNAVAILABLE__")) throw IOException("DIRECTORY_UNAVAILABLE")
        return text
    }

    private fun fileEntries(path: String): List<WorkspaceDirectoryEntry> {
        val directory = File(path)
        if (!directory.isDirectory) throw IOException("DIRECTORY_UNAVAILABLE")
        val children = directory.listFiles() ?: throw IOException("DIRECTORY_UNAVAILABLE")
        return children.map { WorkspaceDirectoryEntry(it.path, it.name, it.isDirectory) }
    }
}

/**
 * 解析 `ls -1pa` 的输出：以 `/` 结尾的行是目录，其余是文件。
 *
 * 抽成纯函数是为了能单测 —— shell 输出解析一旦写错（例如把普通文件也当目录），界面会
 * 让人选到「文件」当工作区，后面每条命令都失败。
 */
internal fun parseLsEntries(output: String): List<WorkspaceDirectoryEntry> =
    output.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (line.endsWith("/")) {
                WorkspaceDirectoryEntry("", line.dropLast(1), directory = true)
            } else {
                WorkspaceDirectoryEntry("", line, directory = false)
            }
        }
        .filter { it.name.isNotEmpty() }
        .toList()

/** 只取目录名字；执行 `ls -1p`（不含隐藏项）时与 [parseLsEntries] 结果一致。 */
internal fun parseLsDirectories(output: String): List<String> =
    parseLsEntries(output).filter { it.directory }.map { it.name }

/**
 * 浏览列表的统一过滤：去掉 `.` / `..`，按开关过滤隐藏项与文件，去重，并排序。
 *
 * 之所以要抽出来：`ls -1pa` 会带点开头的条目，`File.listFiles()` 也会，两种枚举方式必须
 * 落在同一个口径 —— 否则「有没有 Root」会看到两个不同的列表，而那是最难解释的一类差异。
 */
internal fun filterBrowseEntries(
    raw: List<WorkspaceDirectoryEntry>,
    showHidden: Boolean,
    includeFiles: Boolean,
): List<WorkspaceDirectoryEntry> = raw
    .asSequence()
    .filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
    .filter { showHidden || !it.name.startsWith(".") }
    .filter { includeFiles || it.directory }
    .distinctBy { it.name }
    // 目录在前，同类按名称；与文件管理器习惯一致，也免得文件把目录挤到屏外。
    .sortedWith(compareByDescending<WorkspaceDirectoryEntry> { it.directory }.thenBy { it.name.lowercase() })
    .toList()
