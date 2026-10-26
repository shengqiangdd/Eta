package io.github.mangi.eta.agent.terminal

import java.io.File

/** 普通模式独立于旧版由 Root 创建的 terminal 父目录，不修改 chroot 的路径和属主。 */
internal object TerminalPrivateStorage {
    fun workspace(filesDir: File): File = directory(filesDir, "workspace")

    /**
     * 会话工作区：每个 conversationId 一个独立目录，避免不同对话互相看到文件。
     * 自定义路径（共享存储项目）不走这里，由 TerminalRuntime 优先返回。
     */
    fun sessionWorkspace(filesDir: File, scopeId: String): File =
        sessionWorkspacePath(filesDir, scopeId).apply { mkdirs() }

    /** 会话目录的纯路径（不创建）；删除会话时用它，避免先 mkdirs 再删。 */
    fun sessionWorkspacePath(filesDir: File, scopeId: String): File {
        val safe = scopeId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "session" }
        return File(filesDir, "terminal-user/workspaces/$safe")
    }

    /** 删除某个会话的私有工作区；目录不存在视为已清理。 */
    fun deleteSessionWorkspace(filesDir: File, scopeId: String): Boolean {
        if (scopeId.isBlank()) return false
        val dir = sessionWorkspacePath(filesDir, scopeId)
        return !dir.exists() || dir.deleteRecursively()
    }

    fun prootEnvironment(filesDir: File, distribution: LinuxDistribution): File =
        directory(filesDir, "proot/${distribution.wireName}")

    private fun directory(filesDir: File, relative: String): File {
        val independent = File(filesDir, "terminal-user/$relative")
        val legacy = File(filesDir, "terminal/$relative")
        // 已有普通环境继续使用原位置；路径选择不读取 Root 授权，也不搬动已有数据。
        return if (!independent.exists() && legacy.exists()) legacy else independent
    }

    fun isProotPath(path: String?): Boolean =
        path?.let { "/terminal-user/proot/" in it || "/terminal/proot/" in it } == true
}
