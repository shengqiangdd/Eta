package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryDocument

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 高频/最近的失败经验摘要；空串表示还没记录过。 */
    val learnings: String = "",
    /** 「经验教训」章节里的条目总数，用于告诉模型有多少条没展示。 */
    val learningsTotal: Int = 0,
    /** 本次注入的条目数。 */
    val learningsShown: Int = 0,
    /**
     * 「我最近哪些工具常失败、该怎么改」的自评；空串表示没数据或不值得注入。
     *
     * 与 [learnings] 的区别：[learnings] 是**跨对话**累积的经验教训（写进 MEMORY.md），
     * 这一份是**近期 trace 的现算投影**，不进记忆文件，也不该被 memory_write 影响。
     * 会话内必须保持不变（见 [ConversationMemoryPins]），否则系统提示每轮都变、前缀缓存失效。
     */
    val selfBrief: String = "",
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val core = extractCore(snapshot.content)
        val headings = snapshot.content.lineSequence()
            .filter { line -> HEADING.matches(line.trimEnd()) }
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        val learningBody = extractSection(snapshot.content, AgentMemoryLearnings.SECTION_TITLE)
        val learningBudget = (coreBudget / LEARNINGS_BUDGET_DIVISOR)
            .coerceIn(MIN_LEARNINGS_CHARS, MAX_LEARNINGS_CHARS)
        val learnings = AgentMemoryLearnings.summarize(learningBody, learningBudget)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
            learnings = learnings,
            learningsTotal = AgentMemoryLearnings.parse(learningBody).size,
            learningsShown = learnings.lineSequence().count { it.isNotBlank() },
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** 章节体按标题取；找不到就是空串（章节可能被用户删掉）。 */
    private fun extractSection(content: String, title: String): String =
        AgentMemoryDocument.sections(content)
            .firstOrNull { it.title == title }
            ?.body
            .orEmpty()

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val LEARNINGS_BUDGET_DIVISOR = 3
    private const val MIN_LEARNINGS_CHARS = 600
    private const val MAX_LEARNINGS_CHARS = 1_800
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
