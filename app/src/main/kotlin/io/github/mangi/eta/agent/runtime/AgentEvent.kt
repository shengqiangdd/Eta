package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.toSafeLogToken
import java.util.Locale

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class GuiControlChanged(val status: GuiControlStatus) : AgentEvent {
        override fun toLogLine(): String = "gui_control_changed status=${status.wireValue}"
    }

    data class ContextCompaction(
        val operationId: String,
        val phase: String,
        val tokensBefore: Int? = null,
        val tokensAfter: Int? = null,
        val reasonCode: String = "",
        /** 失败时的具体原因（截断 / 超长 / 工具调用等），只用于展示与追踪；旧版 Runtime 不带。 */
        val detail: String = "",
        /**
         * 压缩前后的上下文字符数。
         *
         * 为什么用字符而不是 token：字符数是本地精确值，压缩后要等下一次模型响应才有 token 用量。
         * 此前只记 token，手动压缩时 before 恒为 null、after 从未赋值，追踪里永远是 0 → 0。
         */
        val charsBefore: Int? = null,
        val charsAfter: Int? = null,
        /**
         * 本次压缩实际耗时（含摘要请求）。
         *
         * 为什么要记：压缩慢的时候用户只看到「正在压缩上下文…」和空转的界面，
         * 事后也没法从对话里判断是模型慢还是本地整理慢。旧版 Runtime 不带该字段，读端按 null 处理。
         */
        val durationMs: Long? = null,
    ) : AgentEvent {
        /**
         * 给人看的压缩状态。
         *
         * 完成时优先用 [detail]（里面有摘要字符数与压缩前后上下文大小）——
         * 原来这里写死成「上下文已压缩，用量将在下一次模型响应后更新」，
         * 把 Runtime 已经算好的数字全丢了，用户点完压缩看不出省了多少。
         */
        val displayMessage: String get() = when (phase) {
            PHASE_STARTED -> RUNNING_DETAIL
            PHASE_COMPLETED -> buildString {
                append(detail.ifBlank { "上下文已压缩" })
                // 压缩后的 token 只能在下一次模型响应里拿到；写明这一点，别让用户以为数字被吞了。
                append("；压缩后用量待下一次响应")
                elapsedText()?.let { append("，用时 $it") }
            }
            PHASE_SKIPPED -> if (detail.isBlank()) "上下文已经很小，无需压缩。" else detail
            else -> buildString {
                append(
                    if (detail.isBlank()) "上下文压缩失败，原始上下文已保留。"
                    else "上下文压缩失败：$detail 原始上下文已保留。",
                )
                elapsedText()?.let { append("（用时 $it）") }
            }
        }

        /** 耗时可读化：不到 1 秒不显示 —— 正常压缩远快于此，写出来只是噪声。 */
        private fun elapsedText(): String? {
            val millis = durationMs ?: return null
            if (millis < MIN_VISIBLE_DURATION_MS) return null
            val seconds = millis / 1000.0
            return if (seconds < 60) {
                String.format(Locale.ROOT, "%.1f 秒", seconds)
            } else {
                "${millis / 60_000} 分 ${millis % 60_000 / 1000} 秒"
            }
        }

        override fun toLogLine(): String =
            "context_compaction phase=${phase.toSafeLogToken()}, before=$tokensBefore, after=$tokensAfter, " +
                "chars=$charsBefore->$charsAfter, duration=${durationMs}ms, " +
                "code=${reasonCode.toSafeLogToken()}, detail=${detail.take(80).toSafeLogToken()}"

        companion object {
            const val PHASE_STARTED = "started"
            const val PHASE_COMPLETED = "completed"
            /** 手动压缩被下限拦住：没发请求，也没有失败 —— 用中性胶囊说明原因。 */
            const val PHASE_SKIPPED = "skipped"
            /** 进行中的展示文案同时是 UI 判定运行态的依据，改动必须与 UI 侧同步。 */
            const val RUNNING_DETAIL = "正在压缩上下文…"
            /** 低于这个耗时不写进文案：短压缩写出来只是噪声。 */
            private const val MIN_VISIBLE_DURATION_MS = 1_000L
        }
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
    ) : AgentEvent {
        val displayMessage: String
            get() = "模型请求暂时中断，" + (if (delayMs >= 1000) "${delayMs / 1000} 秒" else "${delayMs} 毫秒") +
                "后重试（$attempt/$maxAttempts）；此前工具结果已保留。"

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}"
    }

    data class ProviderRequestStarted(
        val round: Int,
        /**
         * 这次请求实际使用的模型 / provider / 思考等级，以及它是本回合的第几次尝试。
         *
         * 为什么写在这里：运行中可热切换模型，重试也可能改成新模型。只有「发出这个请求时
         * 到底用的是哪个模型」写进日志与追踪，事后才能回答「为什么这轮回答风格变了」。
         * 旧调用点 / 旧线路消息不带这些字段 → 默认空值，不影响语义。
         */
        val model: String = "",
        val provider: String = "",
        val effort: String = "",
        val attempt: Int = 1,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round, attempt=$attempt, " +
                "model=${model.toModelLogToken()}, provider=${provider.toSafeLogToken()}, " +
                "effort=${effort.toSafeLogToken()}"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class SearchSuggestions(
        val round: Int,
        val index: Int,
        val html: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "search_suggestions round=$round, index=$index, chars=${html.length}"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, ctx=${usage.contextTokens}, in=${usage.inputTokens}, out=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}, prompt=${usage.promptTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
        /** 仅 ask_user：问题原文。界面据此显示「等你回答」卡片，历史归档不恢复该字段。 */
        val askQuestion: String? = null,
        /** 仅 ask_user：可点选的候选答案。 */
        val askOptions: List<String> = emptyList(),
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
        /** 仅文件编辑类工具：可展开的变更细节（diff），不是给人看的一行摘要。 */
        val detail: String? = null,
        /** 工具执行耗时（毫秒）；拒绝调用或旧版 Runtime 缺省为 null。 */
        val elapsedMs: Long? = null,
        /** 文件编辑类工具改的完整路径；聊天工具卡据此打开只读预览。 */
        val filePath: String? = null,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes"
    }

    data class SubagentProgress(
        val round: Int,
        val toolCallId: String,
        val subagentId: String,
        val subagentType: String,
        val snapshotJson: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "subagent_progress round=$round, id=${subagentId.toSafeLogToken()}, " +
                "type=${subagentType.toSafeLogToken()}, chars=${snapshotJson.length}"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    /**
     * 长时工具的执行中进度。
     *
     * 存在的理由：`acp_delegate` 把整个外部编码代理的回合包在**一次**工具调用里，
     * 阻塞几十秒到几分钟。这期间界面只有一张转圈的卡片，用户既看不到代理在做什么，
     * 也无法判断它是卡住了还是在正常推进 —— 与"看不到就等于没有"是同一类缺陷。
     *
     * 与 [AssistantBlockDelta] 的分工：那条事件承载**本 Agent** 的流式输出；
     * 这条事件承载**被监听的外部执行**（目前是 ACP 代理）的中间过程，
     * 通过 `toolCallId` 挂到同一张工具卡片上，因此不需要新的消息类型。
     */
    data class ToolProgress(
        val round: Int,
        val toolCallId: String,
        /** 被监听的外部执行；目前固定为 acp_delegate。 */
        val name: String,
        /** 增量类型：tool_call / text / thought / failed。 */
        val kind: String,
        /** 人类可读的一行：代理正在调用的工具标题，或最近一段文本。 */
        val detail: String,
        val elapsedMs: Long? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_progress round=$round, name=${name.toSafeLogToken()}, " +
                "kind=${kind.toSafeLogToken()}, chars=${detail.length}, ms=${elapsedMs ?: -1}"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}

/**
 * 模型名日志 token：额外允许 `/` 与 `:`（`vendor/model:tag` 这种形式很常见），
 * 其余不可见字符统一换成 `_`，保证每条日志仍是单行、低基数。
 *
 * 不复用 [toSafeLogToken]：它会把带 `/` 的模型名整段判成 `unknown`，
 * 那样「这一轮到底用了哪个模型」又查不出来了。
 */
internal fun String.toModelLogToken(maxLength: Int = 64): String = buildString {
    for (character in this@toModelLogToken.take(maxLength)) {
        append(
            when {
                character in 'a'..'z' -> character
                character in 'A'..'Z' -> character
                character in '0'..'9' -> character
                character == '.' || character == '_' || character == '-' -> character
                character == ':' || character == '/' -> character
                else -> '_'
            }
        )
    }
}
