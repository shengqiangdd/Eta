package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 压缩结果：候选上下文 + 本次摘要的实际形态（分批数、被省略的早期历史量）。
 *
 * 批次与省略量此前只活在摘要内部，外面只能看到一段正文；现在它们随结果一起返回，
 * 由事件带进日志与追踪，分批到底有没有发生不再靠猜。
 */
internal data class CompactionResult(
    val messages: JSONArray,
    val summaryChars: Int,
    val batches: Int,
    val omittedChars: Long,
)

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    /** 摘要分批进度出口；默认静默，App 内接 AndroidAgentLogger（纯 JVM 单测不能碰 android.util.Log）。 */
    private val onBatch: (String) -> Unit = {},
    /** 摘要请求的 token 用量出口；原样透传给 [AgentContextSummarizer]。 */
    private val onUsage: (AgentTokenUsage) -> Unit = {},
) {
    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): CompactionResult {
        controller.throwIfCancelled()
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            throw signedAnthropicToolRoundFailure()
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val source = JSONArray(history.take(end).filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val summary = AgentContextSummarizer(config, provider, controller, roleplay, onBatch, onUsage)
            .summarize(safe)
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n${summary.text}",
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
        )))
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems))
        }
        // 用字符预算对比，不调 toString()：两边的全量序列化本身就是大分配
        // （真机上 2.79M 字符的消息数组就是一次 11,161,552 字节的 char[] 申请）。
        if (AgentContextBudget.contextChars(result) >= AgentContextBudget.contextChars(messages)) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return CompactionResult(
            messages = result,
            summaryChars = summary.text.length,
            batches = summary.batches,
            omittedChars = summary.omittedChars,
        )
    }

    companion object {
        fun signedAnthropicToolRoundFailure() = failure(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
        )

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
