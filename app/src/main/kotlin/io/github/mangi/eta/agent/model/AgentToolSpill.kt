package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 大工具结果溢写：超过预算的输出落盘，模型上下文里只留结构化字段、开头预览与定位符。
 *
 * 三条不变式，都有单测守着：
 *
 * 1. 分页读者（[SELF_BOUNDED_TOOLS]）永不溢写。它们的整页结果就是模型要的内容，再包一层定位符
 *    会把 `next_offset_bytes`、`revision`、游标一起切掉，于是「读回」变成不可能。
 *    这曾经是真缺陷：`read_file` 的一页最多 16,000 字节，转义后约 20,000 字，必然越过 12,000 预算
 *    → 每次读文件都只拿到 1,500 字预览；再按提示读定位符指向的文件，又得到新的定位符，
 *    无限套娃，全文永远进不了上下文（2026-10-21 真机一次 run 里留下 4 个溢写文件，其中 2 个是
 *    这样套出来的 `read_file` 结果）。
 * 2. 溢写保留结构化元数据。JSON 结果只移除体积最大的字段（可多个），
 *    `ok`、`code`、`exit_code`、`status`、`next_offset_bytes`、`revision` 这些字段原样留在上下文里，
 *    模型仍能续读、能按错误码换做法、能带 revision 编辑，而不是只剩一句「结果很大」。
 * 3. 预算按「转义后真正进入上下文的字符数」计算，而不是原始字符数：JSON 里的引号、反斜杠、
 *    控制字符在序列化时会变长，按原长度判定会让实际上下文悄悄超预算。
 *
 * 敏感结果不落盘（由调用方跳过）：那类数据连会话历史都要脱敏，写文件等于绕过整条脱敏链路。
 */
internal class AgentToolSpill(
    private val root: File,
    private val maxChars: Int = DEFAULT_CONTEXT_BUDGET,
    private val previewChars: Int = DEFAULT_PREVIEW_CHARS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 溢写决策的诊断出口；默认静默，App 内接 AndroidAgentLogger，便于真机核对。 */
    private val onSpill: (String) -> Unit = {},
) {
    data class Spilled(
        val content: String,
        val path: String,
        val chars: Int,
        val bytes: Long,
        /** 被移出上下文的字段名，按体积从大到小；非 JSON 结果为空。 */
        val fields: List<String> = emptyList(),
    )

    /**
     * 超过预算时落盘并返回替换后的内容；未超预算、属于分页读者或写盘失败时返回 null（保持原结果）。
     */
    fun maybeSpill(toolName: String, scopeId: String, callId: String, content: String): Spilled? {
        if (scopeId.isBlank()) return null
        val cost = contextCost(content)
        if (toolName in SELF_BOUNDED_TOOLS) {
            if (cost > maxChars) onSpill("skip tool=$toolName cost=$cost reason=paged_reader")
            return null
        }
        if (cost <= maxChars) return null
        val dir = File(root, safeToken(scopeId)).apply { if (!isDirectory && !mkdirs()) return null }
        val file = File(dir, "${clock()}-${safeToken(toolName)}-${safeToken(callId)}.txt")
        val bytes = runCatching { file.writeText(content, Charsets.UTF_8); file.length() }.getOrNull()
            ?: return null
        prune(dir)
        val preview = safePreview(content)
        val removed = mutableListOf<String>()
        val kept = keepSmallFields(content, removed)
        val detail = JSONObject()
            .put("path", file.absolutePath)
            .put("chars", content.length)
            .put("bytes", bytes)
            .put("preview", preview)
            .put("preview_chars", preview.length)
            .put("read", readContract(file, content, bytes))
            .put("retention", retention(scopeId))
            .put("hint", hint(file, content, preview, removed))
        if (removed.isNotEmpty()) detail.put("fields", JSONArray(removed))
        // 定位符单独放在 `spill` 里：结果本身可能已经有 `path`、`chars` 这类同名字段，
        // 平铺会覆盖掉工具真实返回的元数据（read_file 的 path 一度就是这样被换成溢写文件路径的）。
        val base = kept ?: JSONObject()
        if (!base.has("ok")) base.put("ok", originalOk(content))
        val envelope = base.put("spilled", true).put("spill", detail)
        onSpill(
            "spill tool=$toolName fields=${removed.joinToString(",")} chars=${content.length} " +
                "bytes=$bytes path=${file.absolutePath}",
        )
        return Spilled(envelope.toString(), file.absolutePath, content.length, bytes, removed.toList())
    }

    /**
     * 保留除「体积最大的若干字段」之外的全部字段，直到剩余部分落进预算；返回 null 表示
     * 调用方应退回整段替换（非 JSON，或没有任何字段可移）。
     */
    private fun keepSmallFields(content: String, removed: MutableList<String>): JSONObject? {
        val source = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val kept = JSONObject()
        for (key in source.keys()) kept.put(key, source.opt(key))
        if (contextCost(kept.toString()) <= maxChars) return null
        while (contextCost(kept.toString()) > maxChars) {
            val candidate = largestRemovableField(kept) ?: break
            kept.remove(candidate)
            removed += candidate
        }
        return if (removed.isEmpty()) null else kept
    }

    private fun largestRemovableField(json: JSONObject): String? =
        json.keys().asSequence()
            .filter { key -> key != OK_FIELD }
            .maxByOrNull { key -> contextCost(fieldText(json.opt(key))) }

    private fun fieldText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> value.toString()
    }

    /** 只保留最近 [MAX_FILES] 个溢写文件，避免长期使用把应用私有目录撑大。 */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { stale -> runCatching { stale.delete() } }
    }

    private fun originalOk(content: String): Boolean =
        runCatching { JSONObject(content).optBoolean("ok", true) }.getOrDefault(true)

    /** 预览不能把一个 UTF-16 代理对劈成两半，否则序列化出孤立代理，模型看到的是乱码。 */
    private fun safePreview(content: String): String {
        if (content.length <= previewChars) return content
        var end = previewChars
        if (end > 0 && Character.isHighSurrogate(content[end - 1]) && end < content.length) end += 1
        return content.substring(0, end)
    }

    private fun readContract(file: File, content: String, bytes: Long): JSONObject = JSONObject()
        .put("path", file.absolutePath)
        .put("offset_bytes", 0)
        .put("max_bytes", READ_BACK_BYTES)
        .put("total_chars", content.length)
        .put("total_bytes", bytes)

    private fun retention(scopeId: String): JSONObject = JSONObject()
        .put("max_files", MAX_FILES)
        .put("scope", scopeId)

    private fun hint(file: File, content: String, preview: String, removed: List<String>): String = buildString {
        append("完整结果 ").append(content.length).append(" 字已写入该文件；")
        if (removed.isEmpty()) {
            append("上下文里只保留开头 ").append(preview.length).append(" 字预览。")
        } else {
            append("上下文里保留了其余字段，移出体积最大的 ").append(removed.size).append(" 个字段（")
            append(removed.joinToString("、")).append("），只留开头 ").append(preview.length).append(" 字预览。")
        }
        append("需要后文时用 read_file（path=").append(file.absolutePath)
        append("，offset_bytes=0，max_bytes=").append(READ_BACK_BYTES).append("）分页读取；")
        append("该目录只保留最近 ").append(MAX_FILES).append(" 个文件，旧的会被删除，需要就尽快读。")
    }

    private fun safeToken(raw: String): String {
        val cleaned = raw.map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
        return cleaned.take(48).ifBlank { "x" }
    }

    companion object {
        private const val OK_FIELD = "ok"

        /** 一次结果进入上下文的预算，按转义后的字符数算；约 4000 token。 */
        const val DEFAULT_CONTEXT_BUDGET = 12_000
        /**
         * 溢出后保留在上下文里的预览长度。
         *
         * 1,500 偏少：实测十几次「读代码 / 看日志」都卡在「摘要不够判断、
         * 只能再 read_file 一次」。4,000 字符约 1,300 token，通常够看出结论，
         * 而整页预算（[DEFAULT_CONTEXT_BUDGET]）不变，上下文压力没有增加。
         */
        const val DEFAULT_PREVIEW_CHARS = 4_000
        const val MAX_FILES = 40

        /** 与 `read_file` 的单次字节上限一致：定位符给出的读回参数必须真的能执行。 */
        const val READ_BACK_BYTES = 16_000

        /**
         * 自带分页、条数或字节上限的只读工具：它们的整页结果就是模型要的内容，
         * 再溢写只会切掉续读游标（`next_offset_bytes`、`next_cursor`、`revision`）。
         * 这些工具的返回都已在 16,000 字符量级封顶，留在上下文里不会失控。
         */
        val SELF_BOUNDED_TOOLS = setOf(
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "project_info",
        )

        /**
         * 估算这段文本进入上下文后的字符数：`"`、`\`、常见控制字符会变成两个字符，
         * 其余控制字符写成 `\u00XX`（6 个字符）。
         */
        fun contextCost(text: String): Int {
            var cost = text.length
            for (char in text) {
                when {
                    char == '"' || char == '\\' -> cost += 1
                    char == '\n' || char == '\r' || char == '\t' ||
                        char == '\b' || char == '\u000C' -> cost += 1
                    char < ' ' -> cost += 5
                }
            }
            return cost
        }

        fun forApp(context: android.content.Context): AgentToolSpill =
            AgentToolSpill(
                root = File(context.applicationContext.filesDir, "agent/spill"),
                onSpill = { message -> AndroidAgentLogger.info("agent_$message") },
            )
    }
}
