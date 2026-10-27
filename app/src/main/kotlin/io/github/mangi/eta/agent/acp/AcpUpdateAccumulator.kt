package io.github.mangi.eta.agent.acp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 ACP 的 `session/update` 汇总成「这次委派到底做了什么」。
 *
 * 为什么需要：`acp_delegate` 以前只回 `text` + 一行 `→ 标题 (kind)` 的流水账，
 * 用户看到的就是「完成」两个字 —— 不知道跑了哪些命令、每步花了多久、失败在哪一步、
 * 用了多少 token。真实委派（PKX110，2026-10-27）跑了 90 秒、两步，卡片上只有「完成」。
 *
 * 这个类只做纯数据整理（不碰进程、不碰线程），所以能在 JVM 单测里覆盖。
 */
internal class AcpUpdateAccumulator(private val clock: () -> Long = System::currentTimeMillis) {

    /** 失败/中止这些终态之后不再接受时间更新。 */
    private val terminalStatuses = setOf("completed", "failed", "cancelled", "canceled", "error")

    internal data class Step(
        val id: String,
        val title: String,
        val kind: String,
        val status: String,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val detail: String,
    )

    private val steps = LinkedHashMap<String, Step>()

    /** `usage_update` 里的「已用 / 窗口」。代理不发就是 0。 */
    var usageUsed: Long = 0L
        private set
    var usageSize: Long = 0L
        private set

    /** plan 里的完成进度（代理给 plan 时才有）。 */
    var planDone: Int = 0
        private set
    var planTotal: Int = 0
        private set

    fun onUpdate(update: JSONObject?) {
        if (update == null) return
        when (update.optString("sessionUpdate")) {
            "tool_call" -> onToolCall(update)
            "tool_call_update" -> onToolCallUpdate(update)
            "usage_update" -> {
                usageUsed = update.optLong("used", usageUsed)
                usageSize = update.optLong("size", usageSize)
            }
            "plan" -> onPlan(update)
            else -> Unit
        }
    }

    private fun onToolCall(update: JSONObject) {
        val id = update.optString("toolCallId").ifBlank { update.optString("id") }
        val title = update.optString("title").ifBlank { update.optString("kind").ifBlank { "工具调用" } }
        val kind = update.optString("kind")
        val status = update.optString("status").ifBlank { "pending" }
        val key = id.ifBlank { "step-${steps.size + 1}" }
        val now = clock()
        // 同一个 id 重复上报（有的代理先 pending、再 in_progress）：保留起始时间，只更新状态。
        val existing = steps[key]
        steps[key] = Step(
            id = key,
            title = title,
            kind = kind,
            status = status,
            startedAtMs = existing?.startedAtMs ?: now,
            endedAtMs = if (status in terminalStatuses) now else existing?.endedAtMs,
            detail = existing?.detail.orEmpty(),
        )
    }

    private fun onToolCallUpdate(update: JSONObject) {
        val id = update.optString("toolCallId").ifBlank { update.optString("id") }
        val key = id.ifBlank { return }
        val existing = steps[key] ?: return
        val status = update.optString("status").ifBlank { existing.status }
        val now = clock()
        steps[key] = existing.copy(
            title = update.optString("title").ifBlank { existing.title },
            status = status,
            endedAtMs = when {
                status in terminalStatuses -> existing.endedAtMs ?: now
                else -> existing.endedAtMs
            },
            detail = extractText(update).ifBlank { existing.detail },
        )
    }

    private fun onPlan(update: JSONObject) {
        val entries = update.optJSONArray("entries") ?: return
        planTotal = entries.length()
        planDone = (0 until entries.length()).count { index ->
            val status = entries.optJSONObject(index)?.optString("status").orEmpty()
            status == "completed" || status == "done"
        }
    }

    /** ACP 的 `content` 既可能是对象也可能是数组，取第一段文本即可。 */
    private fun extractText(update: JSONObject): String {
        update.optJSONObject("content")?.optString("text")?.let { if (it.isNotBlank()) return it }
        val array = update.optJSONArray("content") ?: return ""
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optJSONObject("content")?.optString("text").orEmpty()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    fun snapshot(): List<Step> = steps.values.toList()

    /**
     * 回给模型/界面的步骤列表。
     *
     * `elapsed_ms` 只对有起止的步骤给；未结束的给 -1，让调用方能区分「还在跑」与「没记录时间」。
     */
    fun stepsJson(limit: Int = 20): JSONArray = JSONArray().also { array ->
        snapshot().take(limit).forEach { step ->
            val elapsed = step.endedAtMs?.let { it - step.startedAtMs }
            array.put(
                JSONObject()
                    .put("title", step.title.take(120))
                    .put("kind", step.kind)
                    .put("status", step.status)
                    .put("elapsed_ms", elapsed ?: -1L)
                    .apply {
                        if (step.detail.isNotBlank()) put("detail", step.detail.take(200))
                    },
            )
        }
    }
}
