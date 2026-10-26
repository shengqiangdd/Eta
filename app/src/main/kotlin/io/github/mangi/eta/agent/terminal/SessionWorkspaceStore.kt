package io.github.mangi.eta.agent.terminal

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/**
 * 会话工作区的持久化路径（参考 dsh 的 working-directory）。
 *
 * 默认值由 [TerminalRuntime.sessionWorkspacePath] 计算：非空会话作用域使用独立目录，
 * 自定义工作区优先；一旦用户通过 `working_directory` 改过，本会话的路径以这里为准。
 *
 * 写盘一律用 `commit()`（同步）而不是 `apply()`：改完工作区后紧接着就会跑一个任务，
 * 而 `apply()` 是异步落盘 —— 进程在写盘线程跑完前被杀，就会留下「界面提示已切换、
 * 任务仍在旧目录」的错位。文件只有几十字节，同步写的主线程代价可忽略
 * （display 11 实测：commit 后 force-stop 再启动，工作区仍是新值）。
 * 因此整文件接受这两条纯性能建议（ApplySharedPref / UseKtx），不是遗漏。
 */
@SuppressLint("ApplySharedPref", "UseKtx")
internal object SessionWorkspaceStore {
    private const val PREFS = "eta-session-workspaces"
    private const val KEY_PREFIX = "session_workspace:"
    private const val LINUX_CWD_PREFIX = "session_linux_cwd:"

    fun get(context: Context, scopeId: String): String? {
        if (scopeId.isBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + scopeId, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 返回本会话当前应使用的工作区；存储路径失效时回退默认会话目录。 */
    fun resolve(context: Context, scopeId: String): String {
        val defaultPath = TerminalRuntime.sessionWorkspacePath(context, scopeId)
        if (scopeId.isBlank()) return defaultPath
        return resolveStored(get(context, scopeId), defaultPath)
    }

    /**
     * 已保存的自定义工作区当前是否可用。
     *
     * 与 [resolve] 用同一判据（[resolveStored]）：一个函数决定「回不回退」，
     * 另一个决定界面能不能把这条设置显示为生效。两边各写一套就会出现
     * 「界面说已切换到自定义文件夹、实际每条命令都在私有目录里跑」。
     */
    fun isStoredWorkspaceAvailable(context: Context, scopeId: String): Boolean {
        if (scopeId.isBlank()) return true
        val stored = get(context, scopeId) ?: return true
        return File(stored).isDirectory
    }

    /**
     * 自定义目录失效（被删、存储未挂载、或 App 失去存储权限）时回退默认目录。
     *
     * `File.isDirectory` 是 App 身份的判据：没有「所有文件访问」时共享存储目录一律返回
     * false，所以写入前必须先过 `WorkspaceFolderValidator` 的同一条检查。
     */
    internal fun resolveStored(stored: String?, defaultPath: String): String =
        stored?.takeIf { it.isNotBlank() && File(it).isDirectory } ?: defaultPath

    /** 保存会话工作区；没有会话作用域时返回 false（只影响当前进程）。 */
    fun set(context: Context, scopeId: String, path: String): Boolean {
        if (scopeId.isBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + scopeId, path)
            .commit()
    }

    /**
     * 只清「Android 工作区路径」，保留 Linux 默认目录。
     *
     * 两个键是独立的：用户可能只换了文件夹，没动 Linux cwd；用 [clear] 会把两者一起抹掉。
     */
    fun clearWorkspace(context: Context, scopeId: String) {
        if (scopeId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + scopeId)
            .commit()
    }

    /**
     * 删除会话时清掉两把键。
     *
     * 只清记录，不删目录：目录由 `TerminalPrivateStorage.deleteSessionWorkspace` 负责，
     * 两者都在删除对话的同一个事务钩子里调用。
     */
    fun clear(context: Context, scopeId: String) {
        if (scopeId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + scopeId)
            .remove(LINUX_CWD_PREFIX + scopeId)
            .commit()
    }

    /**
     * 本会话的 Linux 默认目录（guest 视角）。
     *
     * Linux 的 `/workspace` 是宿主共享目录的 bind 视图，不会按会话重新绑定 ——
     * 用户的项目（例如 `/workspace/eta-v2`）必须对所有会话可见。
     * 会话隔离因此落在「默认 cwd」上：每个会话可以记住自己所在的 guest 目录。
     */
    fun resolveLinuxCwd(
        context: Context,
        scopeId: String,
        linuxHostWorkspace: String,
        rootfsPath: String?,
    ): String {
        val stored = if (scopeId.isBlank()) null else context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LINUX_CWD_PREFIX + scopeId, null)
        if (stored.isNullOrBlank()) return LinuxGuestPaths.WORKSPACE
        val normalized = LinuxGuestPaths.normalize(LinuxGuestPaths.WORKSPACE, stored)
            ?: return LinuxGuestPaths.WORKSPACE
        val host = LinuxGuestPaths.hostPath(normalized, linuxHostWorkspace, rootfsPath)
            ?: return LinuxGuestPaths.WORKSPACE
        // 目录被删掉后回退 /workspace，避免每一条命令都先报 "can't cd"。
        return if (File(host).isDirectory) normalized else LinuxGuestPaths.WORKSPACE
    }

    /** 会话里显式设置过的 Linux 默认目录；没设置过返回 null。 */
    fun getLinuxCwd(context: Context, scopeId: String): String? {
        if (scopeId.isBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LINUX_CWD_PREFIX + scopeId, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 保存会话的 Linux 默认目录；返回 false 表示没有会话作用域或路径不合法。 */
    fun setLinuxCwd(context: Context, scopeId: String, guestPath: String): Boolean {
        if (scopeId.isBlank()) return false
        if (!LinuxGuestPaths.isAllowed(guestPath)) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LINUX_CWD_PREFIX + scopeId, guestPath)
            .commit()
    }
}
