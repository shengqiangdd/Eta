package io.github.mangi.eta.data.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个记忆章节（Markdown 标题 + 它管辖的正文）。
 *
 * 范围到「下一个同级或更高级标题」为止：`## 用户偏好` 的正文不会吞掉后面的 `## 项目`，
 * 但 `###` 子节属于它。
 */
internal data class AgentMemorySection(
    val title: String,
    val level: Int,
    /** 1-based，标题所在行。 */
    val startLine: Int,
    /** 1-based，包含；等于最后一行（可能是正文最后一行，也可能只是标题行）。 */
    val endLine: Int,
    /** 不含标题行的正文。 */
    val body: String,
) {
    val headingLine: String get() = "#".repeat(level) + " " + title

    /** 章节完整文本（标题 + 正文），用于定点读取。 */
    val text: String
        get() = if (body.isBlank()) headingLine else headingLine + "\n\n" + body

    val charCount: Int get() = text.length

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("level", level)
        .put("start_line", startLine)
        .put("end_line", endLine)
        .put("chars", charCount)
}

/**
 * MEMORY.md 的结构化视图。
 *
 * 为什么需要它：整篇替换（replace_all）在长记忆上代价高、风险大 ——
 * 模型只要漏抄一段就是数据丢失；而「按章节替换」能把改动限定在目标段落内。
 * 解析保持宽松：不是合法标题的行原样留给上一个章节，绝不因为格式问题丢内容。
 */
internal object AgentMemoryDocument {

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")

    fun sections(content: String): List<AgentMemorySection> {
        if (content.isBlank()) return emptyList()
        val lines = content.split('\n')
        val headings = ArrayList<Heading>()
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            val match = HEADING.matchEntire(line) ?: return@forEachIndexed
            val title = match.groupValues[2].trim()
            if (title.isEmpty()) return@forEachIndexed
            headings += Heading(index = index, level = match.groupValues[1].length, title = title)
        }
        return headings.mapIndexed { position, heading ->
            var endExclusive = lines.size
            for (next in position + 1 until headings.size) {
                // `#` 顶级标题在 MEMORY.md 里表示「独立页」：核心记忆不会被后面的 `##` 吞掉。
                // 这与 Markdown 的标准层级不同，但和 AgentMemoryContextBuilder.extractCore 的边界一致，
                // 否则「核心记忆」会把整份记忆都算成自己的正文，章节编辑也会改坏其余段落。
                if (heading.level == 1 || headings[next].level <= heading.level) {
                    endExclusive = headings[next].index
                    break
                }
            }
            val body = if (endExclusive > heading.index + 1) {
                lines.subList(heading.index + 1, endExclusive).joinToString("\n").trimEnd('\n')
            } else {
                ""
            }
            AgentMemorySection(
                title = heading.title,
                level = heading.level,
                startLine = heading.index + 1,
                endLine = endExclusive,
                body = body,
            )
        }
    }

    /** 章节名匹配：精确 → 忽略大小写 → 唯一前缀；有歧义时返回 null，交给调用方报错。 */
    fun find(sections: List<AgentMemorySection>, name: String): AgentMemorySection? {
        val target = name.trim().trimStart('#').trim()
        if (target.isEmpty()) return null
        sections.firstOrNull { it.title == target }?.let { return it }
        sections.firstOrNull { it.title.equals(target, ignoreCase = true) }?.let { return it }
        return sections.filter { it.title.startsWith(target, ignoreCase = true) }.singleOrNull()
    }

    fun outlineJson(sections: List<AgentMemorySection>): JSONArray =
        JSONArray().also { array -> sections.forEach { array.put(it.toJson()) } }

    /**
     * 按章节替换正文，保留原标题行。
     *
     * 只改 [section] 覆盖的行，其余字节原样保留；空 [body] 等于只留标题。
     */
    fun replaceSection(content: String, section: AgentMemorySection, body: String): String {
        val lines = content.split('\n').toMutableList()
        val from = (section.startLine - 1).coerceIn(0, lines.size)
        val to = section.endLine.coerceIn(from, lines.size)
        val replacement = ArrayList<String>()
        replacement += lines[from]
        val normalizedBody = body.trimEnd('\n')
        if (normalizedBody.isNotBlank()) {
            // 保留原有的「标题后是否空一行」格式：原本紧接正文的不要凭空插空行，
            // 否则每次编辑都会让文档慢慢长出空白。
            if (to > from + 1 && lines[from + 1].isBlank()) replacement += ""
            normalizedBody.split('\n').forEach { replacement += it }
        }
        lines.subList(from, to).clear()
        lines.addAll(from, replacement)
        return lines.joinToString("\n")
    }

    private data class Heading(val index: Int, val level: Int, val title: String)
}
