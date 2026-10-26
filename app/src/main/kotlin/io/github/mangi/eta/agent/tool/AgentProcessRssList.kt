package io.github.mangi.eta.agent.tool

/** `top_memory_apps` 的一行：进程号、常驻内存（KB）与进程名。 */
internal data class ProcessRssRow(val pid: Int, val rssKb: Long, val name: String)

/**
 * `top_memory_apps` 的固定命令与输出解析。
 *
 * 命令只用**小写列名**且只用 toybox 与 BusyBox 共同支持的列，避免换个 shell 就静默失败。
 * 真机实测（PKX110 / Android 16，2026-10-21），同一进程 17907（Eta）：
 *
 * | 命令 | toybox（`/system/bin/sh`） | BusyBox 1.36（Eta terminal 的 root shell） |
 * |---|---|---|
 * | `-o PID,RSS,NAME` | 正常 | `ps: bad -o argument 'PID'`（列名大小写敏感、没有 NAME） |
 * | `-o pid,rss,comm` | `17907 304936 app_process64` | `17907 331m ithub.mangi.eta` |
 * | `-o pid,rss,args` | `17907 305192 io.github.mangi.eta` | `17907 298m {ithub.mangi.eta} io.github.mangi.eta` |
 *
 * 两条要点：
 * 1. **只有 `args` 能给出可识别的进程名**：`comm` 在 toybox 上是二进制名（`app_process64`），
 *    在 BusyBox 上被截成 15 字符（`ithub.mangi.eta`），都不适合当排行里的应用名。
 * 2. **BusyBox 会把 RSS 人类化**（`331m`），所以单位要按后缀换算；精度因此降到 1 MiB 级，
 *    排行够用，不能当精确内存数字引用。
 */
internal object AgentProcessRssList {
    /** BusyBox 1.36 与 toybox 都接受的列名；`comm` 是内核进程名，不含参数。 */
    private val SUPPORTED_COLUMNS =
        setOf(
            "user", "group", "comm", "args", "pid", "ppid", "pgid", "etime",
            "nice", "rgroup", "ruser", "time", "tty", "vsz", "sid", "stat", "rss", "label",
        )

    const val COMMAND = "ps -A -o pid,rss,args"
    const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 30

    /** BusyBox 在 `args` 前加的 `{comm}` 前缀。 */
    private val COMM_PREFIX = Regex("^\\{[^}]*}\\s*")

    /** 命令里用到的列名；测试据此守住「小写 + 两版共同支持」这条约束。 */
    fun columnsOf(command: String): List<String> =
        command.substringAfter("-o ", "").trim().split(',').map { it.trim() }.filter(String::isNotEmpty)

    /** 命令里用到、但不被两版 ps 共同支持的列名；空表示合规。 */
    fun unsupportedColumns(command: String): List<String> =
        columnsOf(command).filter { it !in SUPPORTED_COLUMNS }

    /**
     * 解析 `ps -A -o pid,rss,args` 的输出：首行是表头，参数被截断或出现空行时跳过该行，
     * 不让一行坏数据拖垮整批。
     */
    fun parse(stdout: String, limit: Int): List<ProcessRssRow> {
        val bounded = limit.coerceIn(1, MAX_LIMIT)
        return stdout
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val rssKb = parseRssKb(parts[1]) ?: return@mapNotNull null
                val name = displayName(parts[2])
                if (name.isEmpty()) return@mapNotNull null
                ProcessRssRow(pid, rssKb, name)
            }
            .sortedByDescending(ProcessRssRow::rssKb)
            .take(bounded)
            .toList()
    }

    /**
     * RSS 列：toybox 给纯 KB 数字，BusyBox 按 1024 进制加 `k`/`m`/`g` 后缀（可能是 `1.5g`）。
     * 认不出来就返回 null —— 宁可少一行，也不要把 `331m` 当成 331 KB。
     */
    fun parseRssKb(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val suffix = text.last()
        val digits = if (suffix.isDigit()) text else text.dropLast(1)
        val value = digits.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0) return null
        val multiplier = when (suffix.lowercaseChar()) {
            'k' -> 1L
            'm' -> 1024L
            'g' -> 1024L * 1024L
            else -> if (suffix.isDigit()) 1L else return null
        }
        return (value * multiplier).toLong()
    }

    /**
     * 从 `args` 取可读进程名：去掉 BusyBox 的 `{comm}` 前缀，取第一个参数，
     * 带路径时取 basename。内核线程的 `[kworker/...]` 原样保留。
     */
    fun displayName(argsRaw: String): String {
        val trimmed = argsRaw.trim().replaceFirst(COMM_PREFIX, "").trim()
        if (trimmed.isEmpty()) return ""
        val first = trimmed.substringBefore(' ')
        if (first.startsWith("[")) return first
        return first.substringAfterLast('/').ifEmpty { first }
    }
}
