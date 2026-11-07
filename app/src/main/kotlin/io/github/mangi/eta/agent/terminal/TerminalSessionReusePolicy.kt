package io.github.mangi.eta.agent.terminal

/**
 * `terminal` 会话复用判定。
 *
 * 真机缺陷：连着两次 `echo` 都返回了完整的 384 字符 `runtime` 块 ——
 * 因为每次调用都用 `UUID.randomUUID()` 开新会话，`runtimeSignature` 永远是空。
 * 顺带两个后果：每次都重启 shell 进程；`cd` / `export` 等会话状态全部丢失。
 *
 * 三条判据缺一不可：环境决定命令去哪里执行、身份决定权限、cwd 决定命令落在哪。
 * 任何一条不同都必须开新会话 —— 复用会把命令送到错的地方，比慢一点危险得多。
 */
internal object TerminalSessionReusePolicy {
    fun reusable(
        existingEnvironment: String,
        existingIdentity: String,
        existingCwd: String,
        closed: Boolean,
        environment: String,
        identity: String,
        cwd: String,
    ): Boolean {
        if (closed) return false
        return existingEnvironment == environment &&
            existingIdentity == identity &&
            existingCwd == cwd
    }
}
