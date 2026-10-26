package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * `edit_file` 未命中或多处命中时的可操作提示。
 *
 * 只读取已经加载的原文，回答"文件里实际是什么"，让模型一次修正，
 * 而不是反复 `read_file` 试错。所有比较都按行进行，避免把 512 KiB 文件做全量模糊匹配。
 */
internal object FileToolMatchHint {
    data class Candidate(
        val line: Int,
        val similarity: Double,
        val preview: String,
    )

    const val WHITESPACE_ONLY = "whitespace_only"
    const val SIMILAR_FOUND = "similar_found"
    const val AMBIGUOUS = "ambiguous_locations"

    /** 行数窗口上限：old_text 再长也只比较前 8 行，避免提示本身消耗大量 CPU。 */
    private const val MAX_WINDOW_LINES = 8

    /** 锚点行最短长度；太短的行（如 "}"）在代码里到处都有，无法定位。 */
    private const val MIN_ANCHOR_CHARS = 4

    private const val MAX_PREVIEW_CHARS = 320

    /** 去掉空白后的精确命中：绝大多数 `MATCH_NOT_FOUND` 只是缩进或行尾空白不同。 */
    fun whitespaceOnly(original: String, oldText: String, limit: Int = 3): List<Candidate>? {
        val target = normalize(oldText)
        if (target.length < MIN_ANCHOR_CHARS) return null
        val haystack = normalize(original)
        val matchStart = haystack.indexOf(target)
        if (matchStart < 0) return null
        val lines = original.split('\n')
        val normalizedLines = lines.map(::normalize)
        var consumed = 0
        var startLine = -1
        var endLine = -1
        for (index in normalizedLines.indices) {
            val length = normalizedLines[index].length
            val lineStart = consumed
            val lineEnd = consumed + length
            consumed = lineEnd
            if (lineEnd <= matchStart) continue
            if (startLine < 0) startLine = index
            endLine = index
            if (lineEnd >= matchStart + target.length) break
        }
        if (startLine < 0) return null
        return listOf(
            Candidate(
                line = startLine + 1,
                similarity = 1.0,
                preview = preview(lines.subList(startLine, (endLine + 1).coerceAtMost(lines.size))),
            ),
        )
    }

    /** 在原文里找与 oldText 最相似的行窗口，按相似度降序返回。 */
    fun similar(original: String, oldText: String, limit: Int = 3): List<Candidate> {
        val target = oldText.trim()
        if (target.isEmpty()) return emptyList()
        val lines = original.split('\n')
        if (lines.isEmpty()) return emptyList()
        val targetLines = oldText.split('\n')
        val window = targetLines.size.coerceIn(1, MAX_WINDOW_LINES)
        val anchors = targetLines
            .map { it.trim() }
            .filter { it.length >= MIN_ANCHOR_CHARS }
            .distinct()
            .sortedByDescending { it.length }
            .take(2)
        if (anchors.isEmpty()) return emptyList()
        val best = LinkedHashMap<Int, Candidate>()
        for (index in lines.indices) {
            val line = lines[index]
            if (anchors.none { anchor -> lineSimilarity(line, anchor) >= 0.55 }) continue
            for (start in intArrayOf(index, (index - 1).coerceAtLeast(0), index + 1)) {
                if (start >= lines.size) continue
                val end = (start + window).coerceAtMost(lines.size)
                if (end <= start) continue
                val text = lines.subList(start, end).joinToString("\n")
                val score = similarity(text, target)
                val current = best[start]
                if (current == null || score > current.similarity) {
                    best[start] = Candidate(start + 1, score, preview(lines.subList(start, end)))
                }
            }
        }
        return best.values
            .sortedWith(compareByDescending<Candidate> { it.similarity }.thenBy { it.line })
            .filter { it.similarity >= 0.35 }
            .take(limit)
    }

    /** `AMBIGUOUS_MATCH` 时列出每一处命中所在行，便于模型补充上下文。 */
    fun ambiguous(original: String, oldText: String, limit: Int = 5): List<Candidate> {
        if (oldText.isEmpty()) return emptyList()
        val lines = original.split('\n')
        val out = ArrayList<Candidate>()
        var searchFrom = 0
        while (out.size < limit) {
            val index = original.indexOf(oldText, searchFrom)
            if (index < 0) break
            val line = lineNumberAt(original, index)
            val text = lines.getOrNull(line - 1).orEmpty().trim()
            out += Candidate(
                line = line,
                similarity = 1.0,
                preview = text.take(MAX_PREVIEW_CHARS),
            )
            searchFrom = index + oldText.length.coerceAtLeast(1)
        }
        return out
    }

    fun toJson(reason: String, candidates: List<Candidate>): JSONObject {
        val array = JSONArray()
        candidates.forEach { candidate ->
            array.put(
                JSONObject()
                    .put("line", candidate.line)
                    .put("similarity", (candidate.similarity * 100).toInt() / 100.0)
                    .put("actual", candidate.preview),
            )
        }
        return JSONObject().put("reason", reason).put("candidates", array)
    }

    fun summarize(reason: String, candidates: List<Candidate>): String = when {
        candidates.isEmpty() -> ""
        reason == WHITESPACE_ONLY ->
            "原文第 ${candidates.first().line} 行起存在仅空白/缩进不同的相同文本，请照抄实际缩进。"
        reason == AMBIGUOUS ->
            "old_text 在以下位置重复出现：${candidates.joinToString("、") { "第 ${it.line} 行" }}；请补充上下文或明确 replace_all=true。"
        else -> "最相近的实际文本在第 ${candidates.first().line} 行（相似度 ${
            (candidates.first().similarity * 100).toInt()
        }%）。"
    }

    private fun preview(lines: List<String>): String {
        val text = lines.joinToString("\n").trimEnd()
        return if (text.length <= MAX_PREVIEW_CHARS) text else text.take(MAX_PREVIEW_CHARS) + "…"
    }

    private fun lineNumberAt(text: String, index: Int): Int {
        var line = 1
        for (position in 0 until index.coerceAtMost(text.length)) {
            if (text[position] == '\n') line++
        }
        return line
    }

    private fun normalize(value: String): String = value.filterNot { it.isWhitespace() }

    private fun lineSimilarity(line: String, anchor: String): Double {
        val left = normalize(line)
        val right = normalize(anchor)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        if (shorter.length >= MIN_ANCHOR_CHARS && longer.contains(shorter)) return 1.0
        return similarity(left, right)
    }

    /** 字符二元组 Dice 系数：对中英文与代码都稳定，且不需要分词。 */
    private fun similarity(left: String, right: String): Double {
        val a = left.filterNot { it.isWhitespace() }
        val b = right.filterNot { it.isWhitespace() }
        if (a == b) return 1.0
        if (abs(a.length - b.length) > maxOf(a.length, b.length)) return 0.0
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var intersection = 0
        for (token in x) if (token in y) intersection++
        return 2.0 * intersection / (x.size + y.size)
    }

    private fun bigrams(value: String): Set<String> {
        if (value.length < 2) return if (value.isEmpty()) emptySet() else setOf(value)
        val out = HashSet<String>(value.length)
        for (index in 0 until value.length - 1) {
            out += value.substring(index, index + 2)
        }
        return out
    }
}
