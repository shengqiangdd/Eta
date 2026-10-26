package io.github.mangi.eta.ui.app

import android.os.Environment
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.WorkspacePathRules
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文件夹不能作为工作区的原因；界面据此给出对应文案。 */
internal enum class WorkspaceFolderProblem {
    BLOCKED,
    /** 系统不开放给普通应用（其他应用的 Android/data、Android/obb）。 */
    RESTRICTED,
    /** 共享存储上的目录，但本应用没有「所有文件访问」权限。 */
    NO_APP_ACCESS,
    NOT_DIRECTORY,
    NOT_WRITABLE,
    UNAVAILABLE,
}

/** 选择文件夹的校验结果。 */
internal sealed interface WorkspaceFolderCheck {
    data class Ready(val path: String) : WorkspaceFolderCheck
    data class Rejected(val problem: WorkspaceFolderProblem) : WorkspaceFolderCheck
}

/**
 * 校验用户选中的文件夹能否作为会话工作区。
 *
 * 规则必须和 `working_directory` 工具一致（[WorkspacePathRules]），否则会出现
 * 「界面能选、任务里却报 INVALID_WORKSPACE」。
 *
 * 有 Root 时用一次 shell 探测：App 自身没有「所有文件访问」权限时 `File.canWrite()`
 * 会把可写目录误判成不可写，而终端是 root 身份，判断应以终端为准。
 */
internal object WorkspaceFolderValidator {
    suspend fun check(path: String): WorkspaceFolderCheck = withContext(Dispatchers.IO) {
        if (path.isEmpty() || path.length > 4096 || '\u0000' in path) {
            return@withContext WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.UNAVAILABLE)
        }
        val canonical = runCatching { File(path).canonicalFile }.getOrElse {
            return@withContext WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.UNAVAILABLE)
        }
        // 系统目录先判：根目录在无权限时 canWrite() 也是 false，先判可写会给出误导的提示。
        if (canonical.path in WorkspacePathRules.blockedRoots) {
            return@withContext WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.BLOCKED)
        }
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        if (WorkspacePathRules.isRestrictedSharedPath(canonical.path, primaryRoot)) {
            return@withContext WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.RESTRICTED)
        }
        // 没有「所有文件访问」时，App 身份看共享存储就是「目录不存在」（FUSE 直接拒 stat）。
        // 但终端与文件工具跑在 root 身份上，能正常读写 —— 所以只有「既没权限、也没 Root」
        // 才拒绝，否则会出现「明明有 root 却选不了共享存储」的假失败。
        if (WorkspacePathRules.requiresAllFilesAccess(canonical.path, primaryRoot) &&
            !Environment.isExternalStorageManager() &&
            !RootAccess.isGranted
        ) {
            return@withContext WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.NO_APP_ACCESS)
        }
        if (!RootAccess.isGranted) return@withContext localCheck(canonical)
        val quoted = shellQuote(canonical.path)
        val probe = runCatching {
            runOneShotShell(
                ShellProcessSupervisor(),
                "root",
                "if [ ! -d $quoted ]; then echo NOT_DIRECTORY; " +
                    "elif [ ! -w $quoted ]; then echo NOT_WRITABLE; else echo OK; fi",
                timeoutSeconds = 10,
                maxOutputBytes = 4096,
            )
        }.getOrElse { return@withContext localCheck(canonical) }
        when (String(probe.output, Charsets.UTF_8).trim()) {
            "OK" -> WorkspaceFolderCheck.Ready(canonical.path)
            "NOT_DIRECTORY" -> WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.NOT_DIRECTORY)
            "NOT_WRITABLE" -> WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.NOT_WRITABLE)
            // 探测本身没跑通（例如 su 失败）：退回 App 身份判断，至少给出一致的结论。
            else -> localCheck(canonical)
        }
    }

    private fun localCheck(file: File): WorkspaceFolderCheck = when {
        !file.isDirectory -> WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.NOT_DIRECTORY)
        !file.canWrite() -> WorkspaceFolderCheck.Rejected(WorkspaceFolderProblem.NOT_WRITABLE)
        else -> WorkspaceFolderCheck.Ready(file.path)
    }
}
