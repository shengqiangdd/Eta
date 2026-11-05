package io.github.mangi.eta.agent.terminal

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * 终端工作区设置：可以把共享存储里的某个目录设为终端默认工作区。
 *
 * 为什么需要：默认工作区在 App 私有目录（`files/terminal-user/workspace`），
 * 用户在 `Download/xxx` 里的项目要每次手动 `cd`，编码时还得在文件工具和终端之间
 * 对路径。设成工作区后，打开的终端直接落在项目目录里。
 *
 * 边界：
 * - 只影响 **user 身份**与 Linux 的 `/workspace`；
 * - **root 身份保持 `/data/local/tmp/eta`** —— 那里同时放着 busybox applet 目录
 *   （`ShellProcessSupervisor.BUSYBOX_APPLET_DIR`），不能被用户路径覆盖。
 */
internal object TerminalWorkspaceSettings {
    private const val PREFS = "eta-terminal-workspace"
    private const val KEY_PATH = "workspace_path"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * 校验并保存；返回 null 表示成功，否则返回给用户看的原因。
     *
     * 空串表示恢复默认。校验既拦明显危险的系统目录，也确认「存在或可创建、是目录、可写」——
     * 设成一个不可写的路径会让终端一打开就报错，比直接拒绝更难排查。
     */
    fun set(context: Context, rawPath: String?): String? {
        val path = rawPath?.trim().orEmpty()
        if (path.isEmpty()) {
            clear(context)
            return null
        }
        if (!path.startsWith('/')) return "需要绝对路径"
        if (path in BLOCKED_ROOTS) return "不能把系统目录设为工作区"
        val dir = File(path)
        if (!dir.exists() && !runCatching { dir.mkdirs() }.getOrDefault(false)) {
            return "目录不存在，也无法创建"
        }
        if (!dir.isDirectory) return "这个路径不是目录"
        if (!dir.canWrite()) return "目录不可写"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATH, dir.absolutePath)
            .apply()
        return null
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_PATH) }
    }

    private val BLOCKED_ROOTS = setOf("/", "/system", "/data", "/proc", "/sys", "/dev", "/vendor", "/apex")
}
