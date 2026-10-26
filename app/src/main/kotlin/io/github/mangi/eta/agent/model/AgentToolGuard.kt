package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 循环卫生守卫：识别「模型在原地打转」，并把提醒附在工具结果里交回模型。
 *
 * 两条判据都是纯逻辑，便于单测：
 * - 连续用完全相同的参数调用同一个工具（第 3 / 5 / 8 次各提醒一次）；
 * - 连续拿到同一个错误码（第 2 / 4 次提醒），因为这类循环换参数也没用。
 *
 * 提醒只写进这一次的工具结果，不额外插入用户消息 —— 用户消息会被当成「用户说的话」
 * 进入会话历史和界面，那是在冒充用户。
 */
internal class AgentToolGuard(
    private val repeatGuard: AgentRepeatGuard = AgentRepeatGuard(),
    private val failureGuard: AgentRepeatedFailureGuard = AgentRepeatedFailureGuard(),
) {
    /** 返回需要追加到工具结果里的提醒；没有触发时返回 null。 */
    fun observe(call: AgentModelClient.ToolCall, resultContent: String): String? =
        listOfNotNull(
            repeatGuard.observe(call)?.text,
            failureGuard.observe(call.name, resultContent)?.text,
        ).joinToString("\n").ifBlank { null }

    companion object {
        /** 把提醒挂进工具结果：JSON 结果加一个字段，保持其余结构可解析；非 JSON 结果直接追加文本。 */
        fun attach(content: String, note: String): String {
            val json = runCatching { JSONObject(content) }.getOrNull()
                ?: return content + "\n\n" + note
            val existing = json.optString("guard_note").takeIf(String::isNotBlank)
            val merged = if (existing == null) note else existing + "\n" + note
            return json.put("guard_note", merged).toString()
        }
    }
}

/** 连续相同工具调用（工具名 + 规范化参数完全一致）的提醒。 */
internal class AgentRepeatGuard(
    private val thresholds: Set<Int> = DEFAULT_THRESHOLDS,
    private val argumentsPreviewChars: Int = 300,
) {
    data class Reminder(val count: Int, val text: String)

    private var lastKey: String? = null
    private var consecutive = 0

    fun observe(call: AgentModelClient.ToolCall): Reminder? {
        val key = call.name + '\u0000' + canonicalArguments(call.argumentsJson)
        if (key == lastKey) {
            consecutive += 1
        } else {
            lastKey = key
            consecutive = 1
        }
        if (consecutive !in thresholds) return null
        val first = thresholds.minOrNull() ?: return null
        val text = if (consecutive == first) GENTLE else describe(call, consecutive)
        return Reminder(consecutive, text)
    }

    private fun describe(call: AgentModelClient.ToolCall, count: Int): String {
        val preview = canonicalArguments(call.argumentsJson).let { canonical ->
            if (canonical.length <= argumentsPreviewChars) {
                canonical
            } else {
                canonical.take(argumentsPreviewChars) + "…（共 ${canonical.length} 字）"
            }
        }
        return "检测到重复工具调用：\n" +
            "- 工具：${call.name}\n" +
            "- 连续次数：$count\n" +
            "- 参数：$preview\n" +
            "这些调用没有带来新进展。不要再用同一组参数调用它：先看最新结果，" +
            "换一种做法或换参数；已经有足够证据时就直接结束任务。"
    }

    companion object {
        val DEFAULT_THRESHOLDS: Set<Int> = setOf(3, 5, 8)

        const val GENTLE =
            "你正在用完全相同的参数重复调用同一个工具。先仔细读上一条结果：" +
                "任务还没完成就换一种做法或换参数，不要原样重试。"

        /** 参数规范化：对象键递归排序后序列化，键顺序不同视为同一组参数。 */
        fun canonicalArguments(raw: String): String {
            val parsed = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
                ?: return raw.trim()
            return canonicalize(parsed)
        }

        private fun canonicalize(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().sorted()
                .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + canonicalize(value.opt(key))
                }

            is JSONArray -> (0 until value.length())
                .joinToString(separator = ",", prefix = "[", postfix = "]") { index ->
                    canonicalize(value.opt(index))
                }

            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }
}

/** 同一个工具连续返回同一个错误码的提醒：这类失败不会因为重试而改变。 */
internal class AgentRepeatedFailureGuard(
    private val thresholds: Set<Int> = setOf(2, 4),
) {
    data class Reminder(val count: Int, val code: String, val text: String)

    private var lastTool = ""
    private var lastCode = ""
    private var consecutive = 0

    fun observe(toolName: String, resultContent: String): Reminder? {
        val json = runCatching { JSONObject(resultContent) }.getOrNull() ?: return reset()
        if (json.optBoolean("ok", true)) return reset()
        val code = json.optString("code").trim().ifBlank { "UNKNOWN" }
        if (toolName == lastTool && code == lastCode) {
            consecutive += 1
        } else {
            lastTool = toolName
            lastCode = code
            consecutive = 1
        }
        if (consecutive !in thresholds) return null
        return Reminder(
            count = consecutive,
            code = code,
            text = "同一工具 `$toolName` 连续 $consecutive 次返回同一个错误（code=$code）。" +
                "重试不会改变这个结果：请换一种做法（换工具或换参数），" +
                "或者把这处限制如实告诉用户并结束这一步。",
        )
    }

    private fun reset(): Reminder? {
        lastTool = ""
        lastCode = ""
        consecutive = 0
        return null
    }
}
