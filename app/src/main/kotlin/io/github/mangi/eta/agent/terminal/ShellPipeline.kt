package io.github.mangi.eta.agent.terminal

/**
 * 判断命令是否把输出送进了管道。
 *
 * 后台命令的 stdout 是日志文件：管道下游（grep/sed/awk/sort…）看到非终端，
 * 会按 4 KiB 全缓冲，[BackgroundTerminalJobs.read] 长时间只能读到空日志。
 * 命令确实含管道时改用 PTY 捕获，下游看到终端就按行写盘。
 *
 * 只做词法判断，不解析语法：引号与反斜杠之外出现的单个 `|` 视为管道，
 * `||` 视为逻辑或。判错的代价只是「多给一个终端」或「少给一个终端」，
 * 不会改写命令本身。
 */
internal object ShellPipeline {
    fun hasStreamingPipe(command: String): Boolean {
        var quote = '\u0000'
        var index = 0
        while (index < command.length) {
            val char = command[index]
            if (quote == '\u0000') {
                when {
                    char == '\\' -> index++
                    char == '\'' || char == '"' -> quote = char
                    char == '|' -> {
                        val next = command.getOrNull(index + 1)
                        if (next != '|') return true
                        index++
                    }
                }
            } else if (char == quote) {
                quote = '\u0000'
            } else if (quote == '"' && char == '\\') {
                index++
            }
            index++
        }
        return false
    }
}
