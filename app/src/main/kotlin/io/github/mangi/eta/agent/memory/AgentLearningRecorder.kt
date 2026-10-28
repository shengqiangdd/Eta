package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 工具失败 → 「经验教训」章节的旁路记账。
 *
 * 三条硬约束：
 * 1. **绝不影响工具结果**：任何解析/写盘异常都吞掉（经验是可选的，工具调用不是）。
 * 2. **同签名不刷屏**：一条 run 内同「工具 + 错误码」只记一次；跨 run 60 秒冷却，
 *    避免模型循环重试时把 hits 从 1 直接刷到 50，让「高频坑」失去区分度。
 * 3. **只记客观字段**：工具名、错误码、错误首行；不记录参数、返回值正文，避免把用户数据搬进记忆。
 */
internal object AgentLearningRecorder {

    private const val COOLDOWN_MS = 60_000L
    private const val MAX_CONTENT_CHARS = 8_000
    private const val FALLBACK_CODE = "ERR"

    private val lastWriteAt = ConcurrentHashMap<String, Long>()

    /**
     * @param tool 工具名。
     * @param content 工具返回的 JSON 字符串（约定 `ok=false` 表示失败）。
     * @return 是否真的写入了记忆（供测试/日志使用）。
     */
    fun record(tool: String, content: String): Boolean {
        return runCatching { recordOrThrow(tool, content) }.getOrDefault(false)
    }

    private fun recordOrThrow(tool: String, content: String): Boolean {
        if (!AgentMemoryLearnings.shouldRecord(tool)) return false
        if (content.isBlank() || content.length > MAX_CONTENT_CHARS) return false
        if (content.trimStart().firstOrNull() != '{') return false
        val json = JSONObject(content)
        if (json.optBoolean("ok", true)) return false
        val (code, message) = describeFailure(json)
        val signature = tool.lowercase() + "|" + code.lowercase()
        val now = System.currentTimeMillis()
        val previous = lastWriteAt[signature]
        if (previous != null && now - previous < COOLDOWN_MS) return false
        lastWriteAt[signature] = now
        return AgentMemoryRepository.recordLearning(
            tool = tool,
            code = code,
            message = message,
            date = LocalDate.now().toString(),
        )
    }

    /**
     * 从工具结果里抽出「错误码 + 错误首行」。
     *
     * 真机实测：`terminal` 这类工具失败时只给 `exit_code` / `stderr`，既没有 `code` 也没有 `message`，
     * 早期实现退化成 `ERR · 工具返回失败` —— 记忆里只留下「有条坑」却不知道是什么坑，
     * 等于白记。所以这里按「显式 code → exit_code → stderr 首行 → stdout 首行」逐级回退。
     */
    fun describeFailure(json: JSONObject): Pair<String, String> {
        val exitCode = json.optInt("exit_code", Int.MIN_VALUE)
        val code = json.optString("code").trim()
            .ifBlank { if (exitCode > 0) "EXIT_$exitCode" else FALLBACK_CODE }
        val message = json.optString("message").trim()
            .ifBlank { json.optString("error").trim() }
            .ifBlank { firstLine(json.optString("stderr")) }
            .ifBlank { firstLine(json.optString("stdout")) }
            .ifBlank { "工具返回失败" }
        return code to message
    }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** 进程/测试隔离用：清掉冷却表，避免上一个用例影响下一个。 */
    fun resetCooldownForTest() {
        lastWriteAt.clear()
    }
}