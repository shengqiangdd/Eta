package io.github.mangi.eta.agent.terminal

/**
 * 「环境用错了」的显式提示。
 *
 * 设备侧（`environment=android`）没有 `/workspace`、`/root` 这些 Linux 目录。
 * 更要紧的是：`cd /workspace/xxx && git diff` 这种命令会**半成功半失败**——
 * `cd` 失败，但用 `;`、管道或后续分支串起来的其它片段照常执行，最后拿到的
 * 可能是一段看上去正常的输出 + 非 0 退出码，很容易被读成「已经检查过了」。
 *
 * 这里只在「命令里出现了 Linux 侧路径」且「确实出问题」时补一句说明，
 * 让模型直接知道该换环境，而不是从 stderr 里猜。
 */
internal object EnvironmentMismatchHint {

    /** 设备侧必然不存在的 Linux 路径前缀。 */
    private val LINUX_ONLY_PATHS = listOf("/workspace", "/root/")

    private val LOOKS_LIKE_MISSING = Regex(
        "No such file or directory|can't cd to|cannot access|command not found|not found",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param environment 本次命令实际使用的环境（`android` / `linux`）。
     * @param command 原始命令文本。
     * @param exitCode 退出码；-2 之类的工具侧状态也算失败。
     * @param stderr 已收集到的错误输出。
     * @return 需要附加到工具结果里的说明；不需要提示时返回 null。
     */
    fun note(environment: String, command: String, exitCode: Int, stderr: String): String? {
        if (!environment.equals(ANDROID, ignoreCase = true)) return null
        val hit = LINUX_ONLY_PATHS.firstOrNull { command.contains(it) } ?: return null
        // 命令自己拿它做判断（例如 `[ -d /workspace ]`）不该被当成错误，所以只在真失败时提示。
        val failed = exitCode != 0 || LOOKS_LIKE_MISSING.containsMatchIn(stderr)
        if (!failed) return null
        return "命令里出现了 $hit（Linux 环境路径），但本次 environment=android：设备侧没有该目录。" +
            "失败很可能只发生在这一段，同一命令里的其它片段仍可能已经执行过，" +
            "不要把它当成「已经检查完了」；要访问工作区请把 environment 设为 linux。"
    }

    private const val ANDROID = "android"

    /**
     * 文件工具版本：同样的“环境用错”在文件操作里表现为 `FILE_NOT_FOUND`，
     * 比终端更容易被误读成「文件真的不存在」。
     */
    fun noteForPath(environment: String, path: String, code: String): String? {
        if (!environment.equals(ANDROID, ignoreCase = true)) return null
        // 权限 / 参数 / 二进制这类失败和命名空间无关，提示“换环境”只会把人带偏。
        if (code != "FILE_NOT_FOUND") return null
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        val hit = LINUX_ONLY_PATHS
            .firstOrNull { prefix ->
                val base = prefix.trimEnd('/')
                normalized == base || normalized.startsWith("$base/")
            } ?: return null
        val shown = hit.trimEnd('/')
        return "路径 $shown 是 Linux 环境的工作区，但本次文件操作的 environment=android：设备侧没有该目录。" +
            "「文件不存在」很可能只是环境用错了，不代表文件真的不存在；请把 environment 设为 linux 后重试。"
    }
}
