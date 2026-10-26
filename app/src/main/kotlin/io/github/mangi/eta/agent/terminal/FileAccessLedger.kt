package io.github.mangi.eta.agent.terminal

import java.util.concurrent.ConcurrentHashMap

/**
 * 本次运行内「模型确实读过哪些文件」的账本。
 *
 * 参考 Claude Code 的 Read-before-Edit：编辑已有文件前必须先 read_file，
 * 避免模型凭猜测改文件、再靠 AMBIGUOUS_MATCH 反复试错。账本按
 * environment + identity + 解析后的路径 区分；同一路径在不同环境/身份下不共享。
 *
 * 文件工具自身写入成功后也会登记，因此「刚写过的文件再改」不需要额外读一次。
 */
internal class FileAccessLedger {
    private val readPaths = ConcurrentHashMap.newKeySet<String>()

    fun markRead(environment: String, identity: String, path: String) {
        if (path.isBlank()) return
        readPaths += key(environment, identity, path)
    }

    fun wasRead(environment: String, identity: String, path: String): Boolean =
        readPaths.contains(key(environment, identity, path))

    private fun key(environment: String, identity: String, path: String): String =
        "$environment\u0000$identity\u0000$path"
}
