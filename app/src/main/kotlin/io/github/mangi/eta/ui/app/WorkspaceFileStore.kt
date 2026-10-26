package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import android.os.Process
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal class WorkspaceFileStore(
    context: Context,
    /** 覆盖文件浏览根目录；null 表示全局工作区。 */
    private val rootOverride: File? = null,
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext
    private val root: File get() = (rootOverride ?: TerminalPrivateStorage.workspace(appContext.filesDir)).canonicalFile

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        if (!root.mkdirs() && !root.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
        val backend = backendFor(root)
        val directory = resolve(backend, path)
        val entries = mutableListOf<WorkspaceEntry>()
        var offset = 0
        var revision: String? = null
        do {
            ensureActive()
            val page = backend.list(directory, offset, 200, revision)
            revision = page.revision
            page.entries.forEach { entry ->
                val target = if (entry.kind == FileToolKind.SYMLINK) backend.stat(backend.resolve(entry.path)) else entry
                if (File(target.path).toPath().startsWith(root.toPath()) && target.kind in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) {
                    entries += WorkspaceEntry(File(entry.path).relativeTo(root).path, entry.name,
                        target.kind == FileToolKind.DIRECTORY, target.sizeBytes)
                }
            }
            if (!page.hasMore) break
            check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
            offset = page.nextOffset
        } while (true)
        entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        AgentFileReferenceGateway.importDocumentUri(appContext, uri, root) is AgentFileReferenceGateway.Resolution.Success
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use {
            val file = File(source)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    ensureActive()
                    val bytes = backend.readBytes(source, offset, 16 * 1024)
                    check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }

    private fun resolve(backend: FileToolBackend, path: String): String = backend.resolve(path).also {
        require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    }

    /**
     * 从应用内选择器选中的文件路径导入。
     *
     * 落点与 [importFile] 一致（`<root>/imports/<uuid>/<name>`、同样 32 MiB 上限、同样名字清洗），
     * 区别只是数据来源是文件系统路径而不是 content URI —— 内置选择器给不出 URI。
     * 没有「所有文件访问」时 App 自己读不到共享存储，只能退回 root 复制。
     */
    suspend fun importPath(sourcePath: String): String = withContext(Dispatchers.IO) {
        if (!root.mkdirs() && !root.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
        val source = File(sourcePath)
        // 这里只拦「既不是文件、也没 Root 兜底」的情形。有 Root 时不能提前判死：
        // 没有「所有文件访问」的 App 对共享存储里的普通文件 `isFile` 同样返回 false，
        // 把这道门禁放在 root 分支之前，结果就是「看得见、点得动、一导入必失败」。
        if (!source.isFile && !RootAccess.isGranted) throw ImportNotFile()
        val importDirectory = File(root, "imports/${UUID.randomUUID()}")
        if (!importDirectory.mkdirs()) throw java.io.IOException("IMPORT_FAILED")
        val target = File(importDirectory, AgentFileReferenceGateway.safeImportName(source.name))
        val copied = try {
            copyIntoWorkspace(source, target)
        } catch (failure: Throwable) {
            importDirectory.deleteRecursively()
            throw failure
        }
        if (!copied) {
            importDirectory.deleteRecursively()
            throw java.io.IOException("IMPORT_FAILED")
        }
        target.relativeTo(root).path
    }

    /** 导出到应用内选择器选中的目录；返回落地路径。重名自动加序号，绝不覆盖既有文件。 */
    suspend fun exportToPath(path: String, directoryPath: String): String = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val sourcePath = resolve(backend, path)
        check(backend.stat(sourcePath).kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val source = File(sourcePath)
        val targetDirectory = File(directoryPath)
        // 先取已有名字再复制：先写后改名会在重名时短暂覆盖别人文件。
        val taken = directoryNames(targetDirectory)
        val target = File(targetDirectory, uniqueExportFileName(source.name, taken))
        if (!copyOutOfWorkspace(backend, source, target)) throw java.io.IOException("EXPORT_UNAVAILABLE")
        target.absolutePath
    }

    /**
     * 目标目录里已有的条目名。
     *
     * App 没有「所有文件访问」时 `File.list()` 会返回 null，但 root 仍能复制 —— 只有这里
     * 也退回 root 列目录，才不会出现「有 Root 却导不出」的矛盾（重名检测与目录存在性都靠它）。
     */
    private fun directoryNames(directory: File): Set<String> {
        directory.list()?.let { return it.toSet() }
        if (!RootAccess.isGranted) throw java.io.IOException("EXPORT_UNAVAILABLE")
        val result = runCatching {
            runOneShotShell(
                ShellProcessSupervisor(),
                "root",
                rootListNamesCommand(directory.path),
                timeoutSeconds = 10,
                maxOutputBytes = 256 * 1024,
            )
        }.getOrNull() ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        if (result.exitCode != 0) throw java.io.IOException("EXPORT_UNAVAILABLE")
        return String(result.output, Charsets.UTF_8)
            .lineSequence()
            .map { it.trimEnd('\r') }
            // `ls -1a` 会带 `.` / `..`；留着它们会让名字集合与 `File.list()` 不一致。
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .toSet()
    }

    /** 导入复制：能直接读就走有界流复制，读不到（无 all-files / 无存储权限）再交给 root。 */
    private suspend fun copyIntoWorkspace(source: File, target: File): Boolean {
        if (source.isFile && source.canRead()) {
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        BoundedFileCopy.copy(input, output, AgentFileReferenceGateway.MAX_IMPORT_BYTES)
                    }
                }
                return true
            } catch (_: BoundedFileCopy.TooLargeException) {
                throw ImportsTooLarge()
            } catch (_: Exception) {
                target.delete()
            }
        }
        if (!RootAccess.isGranted) return false
        val result = runCatching {
            runOneShotShell(
                ShellProcessSupervisor(),
                "root",
                rootImportCommand(
                    source.path,
                    target.path,
                    AgentFileReferenceGateway.MAX_IMPORT_BYTES,
                    ownerUid = Process.myUid(),
                ),
                timeoutSeconds = 30,
                maxOutputBytes = 64 * 1024,
            )
        }.getOrNull() ?: return false
        return when (result.exitCode) {
            0 -> true
            3 -> throw ImportNotFile()
            4 -> throw ImportsTooLarge()
            else -> false
        }
    }

    /** 导出复制：优先直接写；App 读不到或写不了时再交给 root。 */
    private suspend fun copyOutOfWorkspace(backend: FileToolBackend, source: File, target: File): Boolean {
        if (source.canRead()) {
            try {
                source.inputStream().use { input ->
                    newFileStream(target).use { output -> input.copyTo(output, 64 * 1024) }
                }
                return true
            } catch (_: FileAlreadyExistsException) {
                // 不能删：这个文件是别人的（取完名字到落盘之间被人创建了）。
                throw ExportTargetBusy()
            } catch (_: Exception) {
                target.delete()
            }
        }
        if (!RootAccess.isGranted) {
            // 正常路径不会到这里（后端读 + 本地写）；保底：按字节从工作区后端读出来写目标。
            return try {
                newFileStream(target).use { output ->
                    var offset = 0L
                    val size = backend.stat(source.path).sizeBytes
                    while (offset < size) {
                        // 这是个普通 suspend 函数，没有 CoroutineScope 接收者，取当前上下文来响应取消。
                        currentCoroutineContext().ensureActive()
                        val bytes = backend.readBytes(source.path, offset, 16 * 1024)
                        check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                        output.write(bytes)
                        offset += bytes.size
                    }
                }
                true
            } catch (_: FileAlreadyExistsException) {
                throw ExportTargetBusy()
            } catch (_: Exception) {
                target.delete()
                false
            }
        }
        val result = runCatching {
            runOneShotShell(
                ShellProcessSupervisor(),
                "root",
                rootExportCommand(source.path, target.path),
                timeoutSeconds = 60,
                maxOutputBytes = 64 * 1024,
            )
        }.getOrNull() ?: return false
        return when (result.exitCode) {
            0 -> true
            6 -> throw ExportTargetBusy()
            else -> false
        }
    }
}

/** 超过导入上限；界面据此提示「文件太大」，而不是笼统的「操作失败」。 */
internal class ImportsTooLarge : java.io.IOException("IMPORT_TOO_LARGE")

/**
 * 选中的不是可复制的普通文件（目录、设备节点、已消失的路径）。
 *
 * 单独成类型是为了让界面说「这不是文件」；笼统的「操作失败」会让人去查权限和空间，
 * 而真正的原因是选错了目标。
 */
internal class ImportNotFile : java.io.IOException("IMPORT_NOT_FILE")

/**
 * 目标位置已经有文件，且名字已经排到上限。
 *
 * 导出承诺「绝不覆盖」：序号用尽时必须失败，而不是拿一个已知被占用的名字去写 ——
 * 那一步会直接截断别人的文件。
 */
internal class ExportTargetBusy : java.io.IOException("EXPORT_TARGET_BUSY")

/** 以「不存在才创建」的语义打开目标文件，从文件系统层面阻止覆盖。 */
private fun newFileStream(target: File) = Files.newOutputStream(
    target.toPath(),
    StandardOpenOption.CREATE_NEW,
    StandardOpenOption.WRITE,
)

/**
 * root 身份的导入命令。
 *
 * 抽成纯函数是为了能单测：文件名里的空格、引号、`$` 一旦少转义一层，轻则导入失败，
 * 重则让文件名参与命令执行。`-f` 检查必须排在 `wc -c` 之前 —— 目录也能交出大小，
 * 不先拦掉就会把目录当文件 `cp`。
 *
 * [ownerUid] 大于 0 时，复制完成后再把文件交回应用自身：`cp` 由 root 执行，落盘属主是
 * root，权限随 root 的 umask（本机实测 `-rw-r-----`）。应用私有目录里躺着 root 属主的
 * 文件，一旦 root 授权不可用（用户撤销、管理器升级后授权失效），这份数据就再也读不出来，
 * 用户看到的是「我的文件坏了」。改属主失败时删掉刚复制的文件并失败，不留半成品。
 */
internal fun rootImportCommand(
    sourcePath: String,
    targetPath: String,
    limitBytes: Long,
    ownerUid: Int = -1,
): String = buildString {
    val target = shellQuote(targetPath)
    append("[ -f ").append(shellQuote(sourcePath)).append(" ] || exit 3; ")
    // 先量大小再复制：`cp` 一个 2 GiB 的文件会把导入变成一次没有任何反馈的长任务。
    append("size=${'$'}(wc -c < ").append(shellQuote(sourcePath)).append(" 2>/dev/null) || exit 5; ")
    append("[ \"${'$'}size\" -le ").append(limitBytes).append(" ] || exit 4; ")
    append("cp -f ").append(shellQuote(sourcePath)).append(' ').append(target).append(" || exit 5")
    if (ownerUid > 0) {
        append("; chown ").append(ownerUid).append(':').append(ownerUid).append(' ').append(target)
        append(" || { rm -f ").append(target).append("; exit 5; }")
    }
}

/**
 * root 身份列目录条目名的命令。
 *
 * `-a` 连隐藏项一起给：重名检测必须看得见隐藏文件，否则导出会撞上一个「看不见的同名文件」。
 */
internal fun rootListNamesCommand(directoryPath: String): String {
    val quoted = shellQuote(directoryPath)
    return "if [ -d $quoted ]; then cd $quoted && ls -1a; else exit 3; fi"
}

/**
 * root 身份的导出命令：目标已存在就以 6 退出，绝不覆盖。
 *
 * 「先探一次再 `cp -f`」有真实窗口：探完到 `cp` 创建之间，别人新建的同名文件会被
 * `cp -f` 直接覆盖（`-f` 先 unlink 再创建，连 inode 都换了）。这里改成两段式：
 * 1. `(set -C; : > 目标)` 用 noclobber 做 **O_CREAT|O_EXCL 原子占位**，占不上就是被别人抢先；
 * 2. 占位成功后 `cat 源 > 目标` 只写自己占下的 inode —— 不用 `cp -f`，它会再 unlink 一次，
 *    等于把窗口重新打开。
 * 写入失败要删掉占位：留一个 0 字节 / 半截文件比报错更糟，用户会以为导出成功。
 */
internal fun rootExportCommand(sourcePath: String, targetPath: String): String = buildString {
    val source = shellQuote(sourcePath)
    val target = shellQuote(targetPath)
    // 先探一次只为给出准确的「占用」语义（权限不足等情况仍走 5）。
    append("[ -e ").append(target).append(" ] && exit 6; ")
    append("(set -C; : > ").append(target).append(") 2>/dev/null || { [ -e ").append(target)
        .append(" ] && exit 6; exit 5; }; ")
    append("cat ").append(source).append(" > ").append(target)
        .append(" || { rm -f ").append(target).append("; exit 5; }")
}

/**
 * 目标目录里已有同名文件时给出不冲突的名字：`a.txt` → `a (1).txt`。
 *
 * 抽成纯函数便于单测 —— 覆盖别人的文件是不可接受的副作用，而序号逻辑很容易在
 * 「无扩展名 / 多个点」上出错。
 */
internal fun uniqueExportFileName(name: String, taken: Set<String>, maxAttempts: Int = 999): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    for (index in 1..maxAttempts) {
        val candidate = "$base ($index)$extension"
        if (candidate !in taken) return candidate
    }
    // 序号用尽：绝不返回一个已知被占用的名字，宁可让这次导出失败。
    throw ExportTargetBusy()
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}
