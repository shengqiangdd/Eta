package io.github.mangi.eta.agent.tool

/**
 * 危险命令的启发式审查（参考 dsh 的 auto-review，但不引入第二个模型）。
 *
 * 只拦「一执行就可能把设备/数据弄坏」的模式：删根目录、删系统/应用数据目录、
 * 格式化、直接写块设备、卸载系统应用。正常命令（包括 rm -rf /data/local/tmp/x）
 * 不受影响 —— 过宽的规则会把 Agent 训练成绕路，反而失去保护意义。
 */
internal object DangerousCommandGuard {
    private data class Rule(val pattern: Regex, val label: String)

    private val rules = listOf(
        Rule(Regex("""\brm\s+(-[a-zA-Z]+\s+)*/(\s|$|;)"""), "删除根目录"),
        Rule(Regex("""\brm\s+(-[a-zA-Z]+\s+)*/data/(data|system|vendor|adb)(/|\s|$)"""), "删除系统或应用数据目录"),
        Rule(Regex("""\brm\s+(-[a-zA-Z]+\s+)*/storage/emulated/0/?\s*($|;)"""), "删除内部存储根目录"),
        Rule(Regex("""\bmkfs(\.\w+)?\b"""), "格式化分区"),
        Rule(Regex("""\bdd\b[^\n]*\bof=/dev/block"""), "直接写块设备"),
        Rule(Regex("""\b(format|fastboot)\s+"""), "格式化或刷机命令"),
        Rule(Regex("""\bpm\s+uninstall\b[^\n]*--user\s+0"""), "卸载用户 0 的应用"),
    )

    /** 返回命中的危险类型；null 表示没命中。 */
    fun check(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        return rules.firstOrNull { it.pattern.containsMatchIn(trimmed) }?.label
    }
}
