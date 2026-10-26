package io.github.mangi.eta.agent.memory

/**
 * 记忆检索的章节归属。
 *
 * `memory_get` 命中时只返回零散片段，模型很难判断"这条事实属于哪个主题"。
 * 这里按 Markdown 标题向上回溯，给每个命中附上最近的章节名，且保持文档顺序。
 */
internal object AgentMemoryHeadings {
    private val HEADING = Regex("^#{1,6}\\s+(.+?)\\s*$")
    private const val MAX_HEADING_CHARS = 120

    /** 从 [index] 向上找最近的标题行；没有标题时返回 null。 */
    fun nearest(lines: List<String>, index: Int): String? {
        if (lines.isEmpty()) return null
        var cursor = index.coerceAtMost(lines.lastIndex)
        while (cursor >= 0) {
            val heading = HEADING.matchEntire(lines[cursor].trimEnd())
            if (heading != null) {
                return heading.groupValues[1].trim().take(MAX_HEADING_CHARS).ifBlank { null }
            }
            cursor--
        }
        return null
    }

    /** 命中行对应的章节名，去重后按文档顺序返回，最多 [limit] 个。 */
    fun collect(lines: List<String>, indices: List<Int>, limit: Int = 6): List<String> {
        if (indices.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        indices.forEach { index ->
            val heading = nearest(lines, index) ?: return@forEach
            if (out.size < limit) out += heading
        }
        return out.toList()
    }
}
