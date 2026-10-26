package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次运行里被 [io.github.mangi.eta.agent.tool.AgentLocalTools] 自己改过的文件。
 *
 * 与 dsh 的 deliverables 对齐，但**边界更窄**：dsh 在 git 工作树上做快照，能声称「这一轮改了哪些文件」；
 * Eta 跑在整台手机上、没有工作区，shell 重定向、插件或其他应用写的文件都看不见。
 * 所以这里只记录「工具改过什么」，界面文案也必须这么说 —— 不能把账本当成完整的工作区变更。
 *
 * 同名文件多次编辑合并成一条：增删行累加、次数累加、diff 保留最后一次
 * （用户要看的是「现在是什么样」，不是中间态）。
 */
internal data class RunFileChange(
    val path: String,
    val tool: String,
    val added: Int,
    val removed: Int,
    val count: Int,
    val at: Long,
    val diff: String,
)

internal class AgentRunFileChanges(private val root: File) {

    fun fileFor(runId: String): File? {
        val token = safeToken(runId) ?: return null
        return File(root, "$token.json")
    }

    /** 记录一次写入/编辑；返回写入后的条目，账本不可用（无作用域、写失败）时返回 null。 */
    fun record(
        runId: String,
        path: String,
        tool: String,
        added: Int,
        removed: Int,
        diff: String,
        at: Long = System.currentTimeMillis(),
    ): RunFileChange? {
        val file = fileFor(runId) ?: return null
        if (path.isBlank()) return null
        synchronized(LOCK) {
            val entries = load(runId).toMutableList()
            val index = entries.indexOfFirst { it.path == path }
            val merged = if (index >= 0) {
                val previous = entries[index]
                RunFileChange(
                    path = path,
                    tool = tool,
                    added = previous.added + added.coerceAtLeast(0),
                    removed = previous.removed + removed.coerceAtLeast(0),
                    count = previous.count + 1,
                    at = at,
                    diff = diff.take(MAX_DIFF_CHARS),
                ).also { entries[index] = it }
            } else {
                RunFileChange(
                    path = path,
                    tool = tool,
                    added = added.coerceAtLeast(0),
                    removed = removed.coerceAtLeast(0),
                    count = 1,
                    at = at,
                    diff = diff.take(MAX_DIFF_CHARS),
                ).also { entries.add(it) }
            }
            // 只保留最近的一批：运行详情是给人看的，不是审计日志，太长反而找不到重点。
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            if (!save(file, entries)) return null
            // 每次运行一份小文件，不做清理会随运行次数无界增长；按数量与总量各留一条上限。
            prune(file.parentFile)
            return merged
        }
    }

    fun load(runId: String): List<RunFileChange> {
        val file = fileFor(runId) ?: return emptyList()
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val path = json.optString("path").trim()
            if (path.isEmpty()) return@mapNotNull null
            RunFileChange(
                path = path,
                tool = json.optString("tool").ifBlank { "write_file" },
                added = json.optInt("added", 0).coerceAtLeast(0),
                removed = json.optInt("removed", 0).coerceAtLeast(0),
                count = json.optInt("count", 1).coerceAtLeast(1),
                at = json.optLong("at", 0L),
                diff = json.optString("diff"),
            )
        }
    }

    private fun prune(dir: File?) {
        val files = dir?.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            // 刚写的那份排在最前，永远在 keep 之内。
            if (index >= MAX_FILES || total > MAX_TOTAL_BYTES) runCatching { file.delete() }
        }
    }

    private fun save(file: File, entries: List<RunFileChange>): Boolean = runCatching {
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("path", entry.path)
                    .put("tool", entry.tool)
                    .put("added", entry.added)
                    .put("removed", entry.removed)
                    .put("count", entry.count)
                    .put("at", entry.at)
                    .put("diff", entry.diff),
            )
        }
        val text = array.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    private fun safeToken(runId: String): String? {
        val trimmed = runId.trim()
        if (trimmed.isEmpty()) return null
        val token = trimmed.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_'
        }.joinToString("")
        return token.take(64).ifBlank { null }
    }

    companion object {
        const val MAX_ENTRIES = 60
        const val MAX_DIFF_CHARS = 2_000
        const val MAX_BYTES = 128 * 1024
        const val DIGEST_MAX_CHARS = 20_000
        const val MAX_FILES = 200
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

        private val LOCK = Any()

        fun forApp(context: Context): AgentRunFileChanges =
            AgentRunFileChanges(File(context.applicationContext.filesDir, "agent/run_changes"))

        /**
         * 复制给用户的纯文本摘要。刻意不叫「补丁」：每条 diff 是带行号的展示格式（便于核对落点），
         * 不是 `git apply` 能接受的 unified patch，用错名字比没有功能更糟。
         */
        fun digest(changes: List<RunFileChange>, header: String = ""): String {
            if (changes.isEmpty()) return ""
            val added = changes.sumOf { it.added }
            val removed = changes.sumOf { it.removed }
            val builder = StringBuilder()
            if (header.isNotBlank()) builder.appendLine(header)
            builder.appendLine("本次运行改动了 ${changes.size} 个文件，+$added/-$removed 行")
            for (change in changes) {
                builder.appendLine()
                builder.append(change.path)
                builder.append("  +${change.added}/-${change.removed}")
                if (change.count > 1) builder.append("，${change.count} 次")
                builder.appendLine()
                if (change.diff.isNotBlank()) {
                    change.diff.lineSequence().forEach { line -> builder.appendLine("  $line") }
                }
                if (builder.length >= DIGEST_MAX_CHARS) {
                    builder.appendLine("…（摘要过长，后面 ${changes.size} 个文件已省略）")
                    break
                }
            }
            return builder.toString().take(DIGEST_MAX_CHARS)
        }
    }
}
