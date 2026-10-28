package io.github.mangi.eta.agent.memory

/**
 * 记忆里的「经验教训」章节：工具失败时自动沉淀，按「工具 + 错误码」去重。
 *
 * 为什么单独做一套格式而不是让模型自己写：
 * 用户要的是「别在同一个坑上摔两次」，但模型在 run 中途不会主动为一次 `exit 1` 去写记忆；
 * 而每次失败都新增一条又会把记忆撑爆。所以这里只记客观事实（工具名、错误码、错误首行），
 * 用稳定签名合并成 `hits=N`，既不膨胀，也能在下次注入时把高频坑排到前面。
 *
 * 章节是普通 Markdown：说明行、用户手写的内容都原样保留，只增删/改动能被 [ENTRY] 识别的行。
 */
internal object AgentMemoryLearnings {

    const val SECTION_TITLE = "经验教训"

    /**
     * 必须是**顶级标题**（`#`），不能是 `##`。
     *
     * `# 核心记忆` 的自动注入范围是「从它到下一个 `#`」（AgentMemoryContextBuilder.extractCore），
     * 所以把经验章节写成 `##` 并追加到文件末尾时，它会被算进核心记忆正文：
     * 既挤占核心预算，又会在 `<memory_learnings>` 里重复注入一次。
     * `#` 在 MEMORY.md 里表示「独立页」，正好把两者切开。
     */
    const val SECTION_HEADING = "# 经验教训"

    /** 章节首次创建时写入的说明，让用户知道这些行是谁写的。 */
    const val SECTION_NOTE =
        "> 系统在工具调用失败时自动记录，按「工具 + 错误码」去重累加 hits；可自由编辑或删除。"

    const val MAX_ENTRIES = 80
    const val MAX_MESSAGE_CHARS = 140

    /**
     * 这些工具自身就是记忆/子代理管理入口：
     * 它们的失败（例如 revision 冲突）属于交互语义，不是「环境坑」，记录进来只会污染经验区。
     */
    private val EXCLUDED_TOOLS = setOf(
        "memory_get",
        "memory_write",
        "memory_search",
        "memory_outline",
        "character_memory_get",
        "character_memory_write",
    )

    fun shouldRecord(tool: String): Boolean =
        tool.isNotBlank() && tool !in EXCLUDED_TOOLS && !tool.startsWith("character_memory")

    data class Entry(
        val date: String,
        val tool: String,
        val code: String,
        val hits: Int,
        val message: String,
        /** 在 body 中的行下标（0-based），用于原地更新。 */
        val lineIndex: Int,
    )

    /**
     * 一条经验行的格式：
     * `- 2026-10-29 · tool=terminal · code=EXIT_1 · hits=2 · sh: cd: no such file`
     */
    private val ENTRY = Regex(
        "^-\\s*(\\d{4}-\\d{2}-\\d{2})\\s*·\\s*tool=([^·]+?)\\s*·\\s*code=([^·]+?)\\s*·\\s*hits=(\\d+)\\s*·\\s*(.*)$",
    )

    fun parse(body: String): List<Entry> {
        if (body.isBlank()) return emptyList()
        return body.split('\n').mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            val match = ENTRY.matchEntire(line) ?: return@mapIndexedNotNull null
            Entry(
                date = match.groupValues[1],
                tool = match.groupValues[2].trim(),
                code = match.groupValues[3].trim(),
                hits = match.groupValues[4].toIntOrNull() ?: 1,
                message = match.groupValues[5].trim(),
                lineIndex = index,
            )
        }
    }

    data class MergeResult(
        val body: String,
        val entries: Int,
        /** 本次是否真的改动了内容（写入方据此决定是否落盘）。 */
        val changed: Boolean,
    )

    /**
     * 合并一条失败记录。
     *
     * - 签名相同（工具 + 错误码）：原地把 hits +1、刷新日期与错误首行；
     * - 签名不同：追加到末尾；
     * - 条目超过 [MAX_ENTRIES]：从最旧的条目行开始丢弃，用户手写行不受影响。
     */
    fun merge(
        body: String,
        tool: String,
        code: String,
        message: String,
        date: String,
    ): MergeResult {
        val normalizedTool = sanitizeToken(tool).ifBlank { "unknown" }
        val normalizedCode = sanitizeToken(code).ifBlank { "ERR" }
        val normalizedMessage = sanitizeMessage(message)
        val existing = parse(body)
        val signature = signature(normalizedTool, normalizedCode)
        val hit = existing.lastOrNull { signature(it.tool, it.code) == signature }

        val lines = if (body.isBlank()) mutableListOf() else body.split('\n').toMutableList()
        if (hit != null) {
            lines[hit.lineIndex] = render(
                date = date,
                tool = hit.tool,
                code = hit.code,
                hits = hit.hits + 1,
                message = normalizedMessage.ifBlank { hit.message },
            )
            return MergeResult(
                body = lines.joinToString("\n"),
                entries = existing.size,
                changed = true,
            )
        }

        // 追加：先去掉尾部空行，避免每次追加都长出空白。
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        lines += render(
            date = date,
            tool = normalizedTool,
            code = normalizedCode,
            hits = 1,
            message = normalizedMessage,
        )

        var entries = existing.size + 1
        while (entries > MAX_ENTRIES) {
            val removable = parse(lines.joinToString("\n")).firstOrNull() ?: break
            lines.removeAt(removable.lineIndex)
            entries--
        }
        return MergeResult(
            body = lines.joinToString("\n"),
            entries = entries,
            changed = true,
        )
    }

    /**
     * 供系统提示注入的摘要：先给高频坑（hits 高），再补最近记录，最后按 [maxChars] 截断。
     *
     * 高频优先是因为「同一个错误第 3 次出现」比「昨天偶发一次」更值得提醒；
     * 但纯按 hits 排会永远看不到新坑，所以两段都取。
     */
    fun summarize(body: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val entries = parse(body)
        if (entries.isEmpty()) return ""
        val frequent = entries.filter { it.hits >= 2 }
            .sortedWith(compareByDescending<Entry> { it.hits }.thenByDescending { it.lineIndex })
            .take(5)
        val recent = entries.takeLast(10)
        val ordered = (frequent + recent).distinctBy { signature(it.tool, it.code) }
        val output = StringBuilder()
        for (entry in ordered) {
            val rendered = "- ${entry.tool} · ${entry.code} · ×${entry.hits}：${entry.message}"
            val separator = if (output.isEmpty()) 0 else 1
            if (output.length + separator + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
        }
        return output.toString()
    }

    /** 章节整体为空/只有说明行时，视为还没有任何经验。 */
    fun isEmptyBody(body: String): Boolean = parse(body).isEmpty()

    private fun render(date: String, tool: String, code: String, hits: Int, message: String): String =
        "- $date · tool=$tool · code=$code · hits=$hits · $message"

    private fun signature(tool: String, code: String): String =
        sanitizeToken(tool).lowercase() + "|" + sanitizeToken(code).lowercase()

    private fun sanitizeToken(text: String): String =
        text.replace(Regex("[\\s·=]+"), " ").trim().take(60)

    /** 错误首行：去掉换行/制表/控制符与重复空白，避免一条记录撑破预算。 */
    fun sanitizeMessage(text: String): String =
        text.replace(Regex("[\\r\\n\\t\\u0000-\\u001f]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(MAX_MESSAGE_CHARS)
}
