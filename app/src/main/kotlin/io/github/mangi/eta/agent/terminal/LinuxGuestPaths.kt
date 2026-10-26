package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * Linux 环境内工作目录的纯路径逻辑（不访问设备）。
 *
 * 为什么要单独一层：`working_directory` 只接受 guest 视角的路径（例如 `/workspace/project`），
 * 而 App 校验目录是否存在必须走宿主视角（`/workspace` 是 bind mount 的视图）。
 * 把「归一化」和「映射到宿主」拆成纯函数，才能在 JVM 单测里覆盖它们，
 * 也避免在工具层再写一份只针对 `/workspace` 的特判。
 */
internal object LinuxGuestPaths {
    const val WORKSPACE = "/workspace"
    const val ROOT_HOME = "/root"

    /**
     * 把用户/模型给出的 cwd 归一化成 guest 绝对路径。
     *
     * 只允许 `/workspace` 与 `/root` 之下：Linux 环境里其余目录属于 rootfs 自身，
     * 让 Agent 随意切换会把「项目工作区」和「系统目录」混在一起；
     * 需要访问其它位置时仍可用绝对路径执行单条命令，不必改默认 cwd。
     */
    fun normalize(current: String, raw: String): String? {
        if (raw.isBlank() || '\u0000' in raw || raw.length > 4096) return null
        val expanded = when {
            raw == "~" -> ROOT_HOME
            raw.startsWith("~/") -> "$ROOT_HOME/${raw.removePrefix("~/")}"
            raw.startsWith("/") -> raw
            else -> "${current.trimEnd('/')}/$raw"
        }
        val segments = ArrayDeque<String>()
        for (segment in expanded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast() else return null
                else -> segments.addLast(segment)
            }
        }
        val normalized = "/" + segments.joinToString("/")
        return normalized.takeIf { isAllowed(it) }
    }

    fun isAllowed(path: String): Boolean =
        path == WORKSPACE || path.startsWith("$WORKSPACE/") ||
            path == ROOT_HOME || path.startsWith("$ROOT_HOME/")

    /**
     * guest 路径 → 宿主路径；无法映射时返回 null。
     *
     * [linuxHostWorkspace] 是 `/workspace` 这个 bind mount 的宿主来源；
     * `/root` 落在 rootfs 里，rootfs 未安装时无法校验。
     */
    fun hostPath(guestPath: String, linuxHostWorkspace: String, rootfsPath: String?): String? {
        val host = when {
            guestPath == WORKSPACE -> linuxHostWorkspace
            guestPath.startsWith("$WORKSPACE/") -> linuxHostWorkspace.trimEnd('/') + guestPath.removePrefix(WORKSPACE)
            guestPath == ROOT_HOME -> rootfsPath?.let { "${it.trimEnd('/')}/root" }
            guestPath.startsWith("$ROOT_HOME/") -> rootfsPath?.let {
                "${it.trimEnd('/')}/root${guestPath.removePrefix(ROOT_HOME)}"
            }
            else -> null
        } ?: return null
        return File(host).absolutePath
    }
}
