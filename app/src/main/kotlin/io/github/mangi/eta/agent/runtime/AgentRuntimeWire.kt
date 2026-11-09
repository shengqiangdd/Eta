package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AssistantScreenContextProjection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.io.File
import kotlinx.serialization.json.Json

/**
 * AgentRuntime 跨进程通信协议。
 *
 * 入口进程通过 bind + Messenger 与模块自身进程的 [AgentRuntimeService] 通信：
 * 发送一次运行请求，接收事件流和最终结果。
 *
 * 不引入 AIDL：结构化字段使用 [Bundle]，图片正文使用 [ParcelFileDescriptor]，避免占用 Binder 事务缓冲区。
 */
internal object AgentRuntimeWire {
    const val MSG_READ_CONTEXT_RESULT = 15
    const val OP_CHAT = "chat"
    const val OP_COMPACT = "compact"
    const val OP_REWRITE_REPLY = "rewrite_reply"

    const val AGENT_UI_HANDOFF_SOURCE = "agent_ui"
    const val ETA_VOICE_HANDOFF_SOURCE = "eta_voice"

    internal class PayloadTooLargeException(sizeBytes: Int) : IllegalArgumentException(
        "Agent Runtime 请求元数据过大（$sizeBytes bytes）；请缩短输入或会话历史后重试"
    )

    /** bind 获取服务端 Messenger 的 Intent action。 */
    const val ACTION_BIND = "io.github.mangi.eta.agent.runtime.BIND"

    // Messenger.what
    /** client -> service：开始一次 Agent 运行，[Message.replyTo] 携带 client Messenger。 */
    const val MSG_START_RUN = 1

    /** service -> client：推送一个 [AgentEvent]。 */
    const val MSG_EVENT = 2

    /** service -> client：最终结果。 */
    const val MSG_RESULT = 3

    /** client -> service：取消当前运行。 */
    const val MSG_CANCEL = 4

    /** client -> service：确认一个最终结果已经被入口层成功展示。 */
    const val MSG_ACK_RESULT = 5

    /** client -> service：拉取尚未被入口层确认展示的最终结果。 */
    const val MSG_DRAIN_RESULTS = 6

    /** service -> client：返回一组尚未确认展示的最终结果。 */
    const val MSG_DRAIN_RESULTS_RESPONSE = 7

    /** service -> client：请求图片已经摄取，入口进程可以关闭文件描述符并删除临时文件。 */
    const val MSG_REQUEST_INGESTED = 8

    /** client -> service：查询当前仍在执行或提交终态的 run。 */
    const val MSG_QUERY_ACTIVE_RUN = 9

    /** service -> client：返回当前 active runId；空字符串表示没有。 */
    const val MSG_QUERY_ACTIVE_RUN_RESPONSE = 10

    /** client -> service：重新订阅指定 run 的安全事件重放、实时事件和最终结果。 */
    const val MSG_ATTACH_RUN = 11

    /** service -> client：返回是否成功重新订阅指定 run。 */
    const val MSG_ATTACH_RUN_RESPONSE = 12

    /** client -> service：给正在执行的 run 追加一条用户补充指令（steering），不打断当前步骤。 */
    const val MSG_SUPPLEMENT = 16

    /** client -> service：暂停指定 run。 */
    const val MSG_PAUSE_RUN = 17
    /** client -> service：恢复指定 run。 */
    const val MSG_RESUME_RUN = 18

    /**
     * client -> service：运行中热切换模型配置（模型 / 思考等级 / provider）。
     *
     * 消息体直接复用 [toLegacyBundle] 的完整配置编码（含 systemPrompt），
     * 所以两端只有一份字段表：新加配置字段时不会出现「start 请求带、热切换丢」的分叉。
     * 语义：从下一个模型回合生效；正在飞行的那次请求不打断。
     */
    const val MSG_UPDATE_MODEL = 19

    /**
     * client -> service：枚举已接纳、尚未发布终态的运行（多会话并行）。
     *
     * 上游 3.3.5 原来用 16/17，与本地 3.3.1 的 [MSG_SUPPLEMENT]/[MSG_PAUSE_RUN] 撞号；
     * 合并时重编号为 20/21，保证两端写入的 what 值唯一 —— 撞号会让「查询活跃 run」
     * 被当成「追加补充指令」处理，且不会有任何报错。
     */
    const val MSG_QUERY_ACTIVE_RUNS = 20

    /** service -> client：返回各运行的会话归属与当前阶段。 */
    const val MSG_QUERY_ACTIVE_RUNS_RESPONSE = 21

    /**
     * service -> client：补充指令（插话）是否被当前 run 接纳。
     *
     * 只回「收没收到」：接纳后 AgentLoop 会在下一个步骤边界把这条当作用户消息追加；
     * 被丢弃（run 刚结束 / 不是对话 run）时界面必须保留草稿并说明，不能假装已发送。
     */
    const val MSG_SUPPLEMENT_ACK = 22

    /** MSG_SUPPLEMENT 的补充正文键。 */
    const val KEY_SUPPLEMENT_TEXT = "supplement_text"

    /** MSG_SUPPLEMENT_ACK 的接纳结果键。 */
    const val KEY_SUPPLEMENT_ACCEPTED = "supplement_accepted"

    /**
     * 旧版 Runtime 不会在活跃 run 响应里带执行总数。
     *
     * 缺失时按 1 处理：`primary` 为空只代表 UI 没有主 run，不代表后台没有 run 在跑；
     * 当成 0 会让恢复逻辑把仍在执行的会话误判成「被系统中断」。
     */
    const val UNKNOWN_RUN_COUNT = 1

    private const val KEY_TYPE = "type"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_RUN_PAUSED = "run_paused"
    private const val KEY_RUN_COUNT = "run_count"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_ASSISTANT_SCREEN_CONTEXT = "assistant_screen_context"
    private const val KEY_MODEL_SESSION_ID = "model_session_id"
    private const val KEY_PROVIDER_ID = "provider_id"
    private const val KEY_PROVIDER_NAME = "provider_name"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_PROVIDER_SOURCE_TYPE = "provider_source_type"
    /**
     * 认证方式必须随请求过线：漏了它，Runtime 侧重建的 ModelConfig 会回落到 api_key，
     * 订阅（OAuth）服务商就变成“有地址、无密钥” → 发消息直接报「请先配置 API Key」。
     */
    private const val KEY_AUTH_MODE = "auth_mode"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MODEL_DISPLAY_NAME = "model_display_name"
    private const val KEY_CONTEXT_WINDOW = "context_window"
    private const val KEY_AUTO_COMPACTION_ENABLED = "auto_compaction_enabled"
    private const val KEY_MODEL_MAX_RETRIES = "model_max_retries"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_ANTHROPIC_VERSION = "anthropic_version"
    private const val KEY_OPENAI_ENDPOINT_MODE = "openai_endpoint_mode"
    private const val KEY_HOSTED_WEB_SEARCH_ENABLED = "hosted_web_search_enabled"
    private const val KEY_TERMINAL_TOOLS = "terminal_tools"
    private const val KEY_BROWSER_TOOLS = "browser_tools"
    private const val KEY_DEVICE_DIRECT_TOOLS = "device_direct_tools"
    private const val KEY_DEVICE_SENSITIVE_READ_TOOLS = "device_sensitive_read_tools"
    private const val KEY_DEVICE_SENSITIVE_ACTION_TOOLS = "device_sensitive_action_tools"
    private const val KEY_THINKING_ENABLED = "thinking_enabled"
    private const val KEY_REASONING_EFFORT = "reasoning_effort"
    private const val KEY_REASONING_CAPABILITIES_JSON = "reasoning_capabilities_json"
    private const val KEY_EXTRA_BODY_JSON = "extra_body_json"
    private const val KEY_CUSTOM_HEADERS_JSON = "custom_headers_json"
    private const val KEY_CUSTOM_BODY_JSON = "custom_body_json"
    private const val KEY_IMAGES = "images"
    private const val KEY_HISTORY = "history"
    /** 同进程直传历史的句柄（§32.33）：与 [HISTORY_TRANSPORT_PROCESS] 成对出现。 */
    private const val KEY_HISTORY_HANDOFF = "history_handoff"
    private const val KEY_HISTORY_TRANSPORT = "history_transport"
    private const val HISTORY_TRANSPORT_PROCESS = "process"
    private const val KEY_CONTENT_JSON = "content_json"
    private const val KEY_TOOL_CALL_ID = "tool_call_id"
    private const val KEY_TOOL_CALLS_JSON = "tool_calls_json"
    private const val KEY_ROLE = "role"
    private const val KEY_DATA_URL = "data_url"
    private const val KEY_IMAGE_URL = "image_url"
    private const val KEY_IMAGE_FD = "image_fd"
    private const val KEY_MIME_TYPE = "mime_type"
    private const val KEY_BYTES = "bytes"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SOURCE = "source"
    private const val KEY_PRESERVE_ORIGINAL = "preserve_original"
    private const val KEY_OK = "ok"
    private const val KEY_CONTENT = "content"
    private const val KEY_REASONING_CONTENT = "reasoning_content"
    private const val KEY_ERROR = "error"
    private const val KEY_RESULT = "result"
    private const val KEY_TRANSCRIPT_JSON = "transcript_json"
    private const val KEY_HANDOFF = "handoff"
    private const val KEY_HANDOFF_ID = "handoff_id"
    private const val KEY_HANDOFF_SOURCE = "handoff_source"
    private const val KEY_HANDOFF_PAYLOAD = "handoff_payload"
    private const val KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION =
        "handoff_dismiss_entry_surface_on_foreground_operation"
    private const val LEGACY_BREENO_HANDOFF_SOURCE = "breeno"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_RESULTS = "results"
    private const val MAX_RESULT_CONTENT_CHARS = 64_000
    private const val MAX_RESULT_REASONING_CHARS = 32_000
    private const val MAX_DRAIN_CONTENT_CHARS = 16_000
    private const val MAX_DRAIN_REASONING_CHARS = 4_000
    private const val TRUNCATED_SUFFIX = "\n\n[跨进程结果过长，已截断]"
    private const val MAX_START_REQUEST_PARCEL_BYTES = 768 * 1024

    data class RunRequest(
        val runId: String,
        val prompt: String,
        val config: AgentModelClient.ModelConfig,
        val images: List<AgentModelClient.ModelImage>,
        val history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val handoff: EntryHandoff? = null,
        val modelSessionId: String = "",
        val operation: String = OP_CHAT,
        val rewriteTargetMessageId: String? = null,
        val assistantScreenContext: String = "",
    ) {
        // 旧入口沿用会话 handoff；无持久会话的入口以首个 run 为会话起点。
        val effectiveModelSessionId: String
            get() = modelSessionId.ifBlank {
                handoff?.takeIf { it.source == AGENT_UI_HANDOFF_SOURCE }
                    ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
                    ?.takeIf { it.isNotBlank() } ?: runId
            }
    }

    /** 活跃 run 的阶段；上游多会话并行用它区分「在跑 / 等 GUI / 已暂停」。 */
    enum class RunPhase(val wireValue: String) {
        Preparing("preparing"),
        Running("running"),
        WaitingForGui("waiting_for_gui"),
        Paused("paused"),
        Committing("committing");

        companion object {
            fun fromWireValue(value: String): RunPhase = entries.firstOrNull { it.wireValue == value } ?: Running
        }
    }

    data class ActiveRunSnapshot(
        val runId: String,
        val conversationId: String,
        val phase: RunPhase = RunPhase.Running,
        val operation: String = OP_CHAT,
        val handoffSource: String = "",
        val paused: Boolean = false,
    )

    /**
     * 单张图片在 IPC 层的表示。远程 URL 可直接放入 Bundle，本地或内联图片只传只读文件描述符。
     */
    data class WireImage(
        val remoteUrl: String? = null,
        val fileDescriptor: ParcelFileDescriptor? = null,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        val preserveOriginal: Boolean = false,
    )

    /** 接收端在后台完成图片物化前持有文件描述符；关闭后不可再次使用。 */
    class IncomingRunRequest internal constructor(
        val request: RunRequest,
        val images: List<WireImage>,
        private val textBundle: Bundle? = null,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        val hasDeferredPrompt: Boolean get() = textBundle?.let { AgentWireText.hasDescriptor(it, KEY_PROMPT) } == true

        fun materializeText(): RunRequest {
            check(!closed.get()) { "Request already closed" }
            return textBundle?.let { requestFromBundle(it, emptyList(), readText = true) } ?: request
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            textBundle?.let(AgentWireText::close)
            // 请求被拒绝/关闭时，未消费的直传条目要立刻丢掉，不等 TTL（§32.33）。
            textBundle?.let { AgentWireHistoryHandoff.discard(it.getString(KEY_HISTORY_HANDOFF)) }
            images.forEach { image -> runCatching { image.fileDescriptor?.close() } }
        }
    }

    @Serializable
    data class RunResult(
        val runId: String,
        val ok: Boolean,
        val content: String,
        val error: String? = null,
        val reasoningContent: String = "",
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val contextSnapshot: AgentContextSnapshot? = null,
        val contextSnapshotRef: String = "",
        val operation: String = OP_CHAT,
        val rewriteTargetMessageId: String? = null,
    )

    data class EntryHandoff(
        val id: String,
        val source: String,
        val payload: String,
        val dismissEntrySurfaceOnForegroundOperation: Boolean = false
    )

    data class CompletedRun(
        val handoff: EntryHandoff,
        val result: RunResult,
        val createdAt: Long
    )

    /**
     * 绑定**本应用**的 Runtime 服务。
     *
     * 必须按 [Context.getPackageName] 解析，不能写死发布包名：Canary 构建的
     * applicationId 是 io.github.mangi.eta.canary，写死会让界面去绑另一个应用里的
     * 服务（或被对端的调用方校验直接拒掉），现象是"点发送后没有回答、也没有报错"。
     */
    fun serviceIntent(context: Context): Intent =
        Intent(context, AgentRuntimeService::class.java).setAction(ACTION_BIND)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun toBundle(request: RunRequest, images: List<WireImage>, payloadDirectory: File? = null): Bundle {
        require(images.size == request.images.size) { "图片传输项与请求图片数量不一致" }
        val imageBundles = images.map { image ->
            require((image.remoteUrl == null) xor (image.fileDescriptor == null)) {
                "图片传输项必须且只能包含远程 URL 或文件描述符"
            }
            Bundle().apply {
                image.remoteUrl?.let { putString(KEY_IMAGE_URL, it) }
                image.fileDescriptor?.let { putParcelable(KEY_IMAGE_FD, it) }
                putString(KEY_MIME_TYPE, image.mimeType)
                putInt(KEY_BYTES, image.bytes)
                image.width?.let { putInt(KEY_WIDTH, it) }
                image.height?.let { putInt(KEY_HEIGHT, it) }
                putString(KEY_SOURCE, image.source)
                putBoolean(KEY_PRESERVE_ORIGINAL, image.preserveOriginal)
            }
        }
        return requestBundle(request, imageBundles, payloadDirectory)
    }

    /** 兼容旧客户端与协议测试；新请求不得通过 Binder 内联图片正文。 */
    fun toLegacyBundle(request: RunRequest): Bundle = requestBundle(
        request = request,
        processLocalHistory = false,
        imageBundles = request.images.map { image ->
            Bundle().apply {
                putString(KEY_DATA_URL, image.reference)
                putString(KEY_MIME_TYPE, image.mimeType)
                putInt(KEY_BYTES, image.bytes)
                image.width?.let { putInt(KEY_WIDTH, it) }
                image.height?.let { putInt(KEY_HEIGHT, it) }
                putString(KEY_SOURCE, image.source)
                putBoolean(KEY_PRESERVE_ORIGINAL, image.preserveOriginal)
            }
        },
        includeLegacyHistory = true,
    )

    private fun requestBundle(
        request: RunRequest,
        imageBundles: List<Bundle>,
        payloadDirectory: File? = null,
        /** 是否额外内联一份 [#KEY_HISTORY] 投影；仅旧客户端与协议测试需要。 */
        includeLegacyHistory: Boolean = false,
        /**
         * 历史是否走进程内直传（§32.33）。默认 true：Runtime 与界面同进程，
         * 编码成 JSON、写临时文件、再读回解析那一整圈没有任何收益。
         * 只有 [toLegacyBundle]（旧客户端与协议测试）需要 false。
         */
        processLocalHistory: Boolean = true,
    ): Bundle = Bundle().apply {
        require(request.assistantScreenContext.length <= AssistantScreenContextProjection.MAX_CHARS) {
            "助理屏幕上下文超过容量预算"
        }
        val historyStartedAt = android.os.SystemClock.elapsedRealtime()
        if (processLocalHistory) {
            // 同进程直传：清洗后的 List 直接交接，不再 JSON 编码 + 落盘 + 读回 + 解析（§32.33）。
            // 语义与 decodeTranscript(encodeTranscriptForStorage(...)) 一致（§32.25）。
            putString(KEY_HISTORY_TRANSPORT, HISTORY_TRANSPORT_PROCESS)
            putString(
                KEY_HISTORY_HANDOFF,
                AgentWireHistoryHandoff.offer(AgentConversationCodec.sanitizeTranscript(request.history)),
            )
            AndroidAgentLogger.infoPersisted(
                "request history: handoff=${android.os.SystemClock.elapsedRealtime() - historyStartedAt}ms " +
                    "messages=${request.history.size}",
            )
        } else {
            val encodeStartedAt = android.os.SystemClock.elapsedRealtime()
            val historyJson = AgentConversationCodec.encodeTranscriptForStorage(request.history)
            val encodeMs = android.os.SystemClock.elapsedRealtime() - encodeStartedAt
            val writeStartedAt = android.os.SystemClock.elapsedRealtime()
            AgentWireText.put(this, "history_json", historyJson, payloadDirectory)
            // 分段计时：把「序列化」和「落盘」拆开，才知道 100ms+ 到底花在哪一段（§32.28）。
            // 落盘（infoPersisted）：logcat 的 main ring 只有 256 KiB，请求日志几十秒就被顶掉（§32.31）。
            AndroidAgentLogger.infoPersisted(
                "request history: encode=${encodeMs}ms " +
                    "write=${android.os.SystemClock.elapsedRealtime() - writeStartedAt}ms chars=${historyJson.length}",
            )
        }
        putString(KEY_RUN_ID, request.runId)
        AgentWireText.put(this, KEY_PROMPT, request.prompt, payloadDirectory)
        putString(KEY_ASSISTANT_SCREEN_CONTEXT, request.assistantScreenContext)
        putString(KEY_MODEL_SESSION_ID, request.modelSessionId)
        putString(KEY_PROVIDER_ID, request.config.providerId)
        putString(KEY_PROVIDER_NAME, request.config.providerName)
        putString(KEY_PROVIDER_TYPE, request.config.providerType)
        putString(KEY_PROVIDER_SOURCE_TYPE, request.config.providerSourceType)
        putString(KEY_AUTH_MODE, request.config.authMode)
        putString(KEY_BASE_URL, request.config.baseUrl)
        putString(KEY_API_KEY, request.config.apiKey)
        putString(KEY_MODEL, request.config.model)
        putString(KEY_MODEL_DISPLAY_NAME, request.config.modelDisplayName)
        putString("operation", request.operation)
        request.rewriteTargetMessageId?.let { putString("rewrite_target_message_id", it) }
        request.config.contextWindow?.let { putInt(KEY_CONTEXT_WINDOW, it) }
        putBoolean(KEY_AUTO_COMPACTION_ENABLED, request.config.autoCompactionEnabled)
        putInt(KEY_MODEL_MAX_RETRIES, request.config.modelMaxRetries)
        AgentWireText.put(this, KEY_SYSTEM_PROMPT, request.config.systemPrompt, payloadDirectory)
        putString(KEY_ANTHROPIC_VERSION, request.config.anthropicVersion)
        putString(KEY_OPENAI_ENDPOINT_MODE, request.config.openAiEndpointMode)
        putBoolean(KEY_HOSTED_WEB_SEARCH_ENABLED, request.config.hostedWebSearchEnabled)
        putBoolean(KEY_TERMINAL_TOOLS, request.config.terminalTools)
        putBoolean(KEY_BROWSER_TOOLS, request.config.browserTools)
        putBoolean(KEY_DEVICE_DIRECT_TOOLS, request.config.deviceDirectTools)
        putBoolean(KEY_DEVICE_SENSITIVE_READ_TOOLS, request.config.deviceSensitiveReadTools)
        putBoolean(KEY_DEVICE_SENSITIVE_ACTION_TOOLS, request.config.deviceSensitiveActionTools)
        putBoolean(KEY_THINKING_ENABLED, request.config.effectiveReasoningEffort.enablesReasoning)
        putString(KEY_REASONING_EFFORT, request.config.effectiveReasoningEffort.wireValue)
        request.config.reasoningCapabilities?.let {
            putString(KEY_REASONING_CAPABILITIES_JSON, json.encodeToString(it))
        }
        putString(KEY_EXTRA_BODY_JSON, request.config.extraBodyJson)
        putString(KEY_CUSTOM_HEADERS_JSON, json.encodeToString(request.config.customHeaders))
        putString(KEY_CUSTOM_BODY_JSON, json.encodeToString(request.config.customBody))
        request.handoff?.let { putBundle(KEY_HANDOFF, toBundle(it)) }
        if (includeLegacyHistory) {
            // 旧客户端兼容载荷：只给 toLegacyBundle / 协议测试用。
            //
            // 正常请求曾经也无条件构造它，于是每次发消息都会对历史再做一遍
            // sanitize + 全量 JSON 编码，再把刚编出来的字符串**解析回来**，
            // 最后拆成 N 个 Bundle 内联进 Parcel —— 而读端 `history_json` 一定存在，
            // 这一整块是死数据（§32.29）。59 KB 历史时约 10 ms，
            // 且额外让 ~200 KB 走 Binder 事务（DirectChars 96k 字符 × UTF-16）。
            putParcelableArrayList(
                KEY_HISTORY,
                ArrayList(AgentConversationCodec.decodeTranscript(AgentLegacyConversationProjection.encode(request.history, AgentLegacyConversationProjection.DIRECT_CHARS)).map { message ->
                    Bundle().apply {
                        putString(KEY_ROLE, message.role)
                        putString(KEY_CONTENT, message.content)
                        putString(KEY_CONTENT_JSON, message.contentJson)
                        putString(KEY_TOOL_CALL_ID, message.toolCallId)
                        putString(KEY_REASONING_CONTENT, message.reasoningContent)
                        putString(KEY_TOOL_CALLS_JSON, message.toolCallsJson)
                    }
                })
            )
        }
        putParcelableArrayList(
            KEY_IMAGES,
            ArrayList(imageBundles)
        )
    }.also { bundle ->
        try { requireStartRequestWithinBinderBudget(bundle) } catch (failure: Exception) {
            AgentWireText.close(bundle)
            throw failure
        }
    }

    fun incomingRunRequestFromBundle(bundle: Bundle): IncomingRunRequest {
        val images = mutableListOf<WireImage>()
        try {
            bundle.getParcelableArrayList(KEY_IMAGES, Bundle::class.java).orEmpty().forEach { image ->
                val descriptor = image.getParcelable(KEY_IMAGE_FD, ParcelFileDescriptor::class.java)
                val reference = image.getString(KEY_IMAGE_URL)
                    ?: image.getString(KEY_DATA_URL) // 兼容升级前仍内联 data URL 的入口进程。
                require((reference == null) xor (descriptor == null)) {
                    "图片传输项必须且只能包含引用或文件描述符"
                }
                images += WireImage(
                    remoteUrl = reference,
                    fileDescriptor = descriptor,
                    mimeType = image.getString(KEY_MIME_TYPE).orEmpty(),
                    bytes = image.getInt(KEY_BYTES),
                    width = image.optionalInt(KEY_WIDTH),
                    height = image.optionalInt(KEY_HEIGHT),
                    source = image.getString(KEY_SOURCE).orEmpty(),
                    preserveOriginal = image.getBoolean(KEY_PRESERVE_ORIGINAL, false),
                )
            }
            return IncomingRunRequest(
                request = requestFromBundle(bundle, images = emptyList(), readText = false),
                images = images,
                textBundle = bundle,
            )
        } catch (throwable: Throwable) {
            closeImageDescriptors(bundle)
            throw throwable
        }
    }

    /** 拒绝或解析失败的请求不会进入 [IncomingRunRequest]，需显式释放其中的描述符。 */
    fun closeImageDescriptors(bundle: Bundle?) {
        bundle?.let(AgentWireText::close)
        runCatching {
            bundle?.getParcelableArrayList(KEY_IMAGES, Bundle::class.java).orEmpty().forEach { image ->
                image.getParcelable(KEY_IMAGE_FD, ParcelFileDescriptor::class.java)?.close()
            }
        }
    }

    /** 只用于无文件描述符的旧协议读取。 */
    fun runRequestFromBundle(bundle: Bundle): RunRequest =
        incomingRunRequestFromBundle(bundle).use { incoming ->
            require(incoming.images.none { it.fileDescriptor != null }) {
                "包含文件描述符的请求必须先在 Runtime 后台物化"
            }
            incoming.materializeText().copy(
                images = incoming.images.map { image ->
                    AgentModelClient.ModelImage(
                        reference = image.remoteUrl.orEmpty(),
                        mimeType = image.mimeType,
                        bytes = image.bytes,
                        width = image.width,
                        height = image.height,
                        source = image.source,
                        preserveOriginal = image.preserveOriginal,
                    )
                },
            )
        }

    private fun requestFromBundle(
        bundle: Bundle,
        images: List<AgentModelClient.ModelImage>,
        readText: Boolean,
    ): RunRequest = RunRequest(
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            prompt = if (readText) AgentWireText.read(bundle, KEY_PROMPT).orEmpty() else bundle.getString(KEY_PROMPT).orEmpty(),
            assistantScreenContext = bundle.getString(KEY_ASSISTANT_SCREEN_CONTEXT).orEmpty().also {
                require(it.length <= AssistantScreenContextProjection.MAX_CHARS) { "助理屏幕上下文超过容量预算" }
            },
            operation = bundle.getString("operation")?.also { require(it in setOf(OP_CHAT, OP_COMPACT, OP_REWRITE_REPLY)) } ?: OP_CHAT,
            rewriteTargetMessageId = bundle.getString("rewrite_target_message_id")?.also {
                require(it.isNotBlank() && it.length <= 256) { "Invalid rewrite target" }
            },
            modelSessionId = bundle.getString(KEY_MODEL_SESSION_ID).orEmpty(),
            config = AgentModelClient.ModelConfig(
                providerId = bundle.getString(KEY_PROVIDER_ID).orEmpty(),
                providerName = bundle.getString(KEY_PROVIDER_NAME).orEmpty(),
                providerType = bundle.getString(KEY_PROVIDER_TYPE).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.ProviderTypes.OPENAI_COMPATIBLE },
                providerSourceType = bundle.getString(KEY_PROVIDER_SOURCE_TYPE).orEmpty(),
                authMode = bundle.getString(KEY_AUTH_MODE).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.ProviderAuthModes.API_KEY },
                baseUrl = bundle.getString(KEY_BASE_URL).orEmpty(),
                apiKey = bundle.getString(KEY_API_KEY).orEmpty(),
                model = bundle.getString(KEY_MODEL).orEmpty(),
                modelDisplayName = bundle.getString(KEY_MODEL_DISPLAY_NAME).orEmpty(),
                contextWindow = bundle.optionalInt(KEY_CONTEXT_WINDOW),
                autoCompactionEnabled = bundle.getBoolean(
                    KEY_AUTO_COMPACTION_ENABLED,
                    Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
                ),
                modelMaxRetries = bundle.getInt(KEY_MODEL_MAX_RETRIES, 3),
                systemPrompt = if (readText) AgentWireText.read(bundle, KEY_SYSTEM_PROMPT).orEmpty() else "",
                anthropicVersion = bundle.getString(KEY_ANTHROPIC_VERSION).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
                openAiEndpointMode = bundle.getString(KEY_OPENAI_ENDPOINT_MODE).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS },
                hostedWebSearchEnabled = bundle.getBoolean(KEY_HOSTED_WEB_SEARCH_ENABLED, false),
                terminalTools = bundle.getBoolean(KEY_TERMINAL_TOOLS),
                browserTools = if (bundle.containsKey(KEY_BROWSER_TOOLS)) {
                    bundle.getBoolean(KEY_BROWSER_TOOLS)
                } else {
                    true
                },
                deviceDirectTools = if (bundle.containsKey(KEY_DEVICE_DIRECT_TOOLS)) {
                    bundle.getBoolean(KEY_DEVICE_DIRECT_TOOLS)
                } else {
                    true
                },
                deviceSensitiveReadTools =
                    bundle.getBoolean(KEY_DEVICE_SENSITIVE_READ_TOOLS, false),
                deviceSensitiveActionTools =
                    bundle.getBoolean(KEY_DEVICE_SENSITIVE_ACTION_TOOLS, false),
                thinkingEnabled = bundle.getBoolean(KEY_THINKING_ENABLED),
                reasoningEffort = if (bundle.containsKey(KEY_REASONING_EFFORT)) {
                    ReasoningEffort.fromWireValue(bundle.getString(KEY_REASONING_EFFORT))
                        ?: ReasoningEffort.DEFAULT
                } else {
                    ReasoningEffort.fromLegacy(bundle.getBoolean(KEY_THINKING_ENABLED))
                },
                reasoningCapabilities = decodeReasoningCapabilities(
                    bundle.getString(KEY_REASONING_CAPABILITIES_JSON)
                ),
                extraBodyJson = bundle.getString(KEY_EXTRA_BODY_JSON).orEmpty(),
                customHeaders = decodeCustomHeaders(bundle.getString(KEY_CUSTOM_HEADERS_JSON)),
                customBody = decodeCustomBody(bundle.getString(KEY_CUSTOM_BODY_JSON))
            ),
            history = historyFromBundle(bundle, readText),
            images = images,
            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle)
        )

    /**
     * 读回历史并解析，并把「读文件」与「JSON 解析」拆开计时（§32.33）。
     *
     * 为什么值得单独一行：服务端 `ingest` 此前只有总耗时（实测 478 KB 时 65 ms，
     * 比客户端整个 build 还大），但不知道是读文件慢、还是解析慢 ——
     * 到底是前者还是后者，决定了下一步该不该做「增量编码」。
     */
    private fun historyFromBundle(
        bundle: Bundle,
        readText: Boolean,
    ): List<AgentModelClient.ConversationMessage> {
        if (!readText) return emptyList()
        if (bundle.getString(KEY_HISTORY_TRANSPORT) == HISTORY_TRANSPORT_PROCESS) {
            // 同进程直传：取引用即可。
            // 取不到说明「写了 process 标记却不在同一进程」—— 必须立刻失败：
            // 静默回退成空历史会让对话莫名失忆，比抛错难查得多。
            val handoffStartedAt = android.os.SystemClock.elapsedRealtime()
            val handed = AgentWireHistoryHandoff.take(bundle.getString(KEY_HISTORY_HANDOFF))
                ?: error("history 进程内交接丢失：Runtime 与界面已不在同一进程")
            AndroidAgentLogger.infoPersisted(
                "run history: handoff=${android.os.SystemClock.elapsedRealtime() - handoffStartedAt}ms " +
                    "messages=${handed.size} chars=${handed.sumOf {
                        it.content.length + it.contentJson.length + it.reasoningContent.length + it.toolCallsJson.length
                    }}",
            )
            return handed
        }
        val readStartedAt = android.os.SystemClock.elapsedRealtime()
        val raw = AgentWireText.read(bundle, "history_json")
        val readMs = android.os.SystemClock.elapsedRealtime() - readStartedAt
        if (raw == null) {
            // 旧客户端（只写了内联 KEY_HISTORY）的回退路径。
            return bundle.getParcelableArrayList(KEY_HISTORY, Bundle::class.java).orEmpty().map { message ->
                AgentModelClient.ConversationMessage(
                    role = message.getString(KEY_ROLE).orEmpty(),
                    content = message.getString(KEY_CONTENT).orEmpty(),
                    contentJson = message.getString(KEY_CONTENT_JSON).orEmpty(),
                    toolCallId = message.getString(KEY_TOOL_CALL_ID).orEmpty(),
                    reasoningContent = message.getString(KEY_REASONING_CONTENT).orEmpty(),
                    toolCallsJson = message.getString(KEY_TOOL_CALLS_JSON).orEmpty(),
                )
            }
        }
        val decodeStartedAt = android.os.SystemClock.elapsedRealtime()
        val decoded = AgentConversationCodec.decodeTranscript(raw)
        AndroidAgentLogger.infoPersisted(
            "run history: read=${readMs}ms decode=${android.os.SystemClock.elapsedRealtime() - decodeStartedAt}ms " +
                "messages=${decoded.size} chars=${raw.length}"
        )
        return decoded
    }

    fun toBundle(handoff: EntryHandoff): Bundle = Bundle().apply {
        putString(KEY_HANDOFF_ID, handoff.id)
        putString(KEY_HANDOFF_SOURCE, handoff.source)
        putString(KEY_HANDOFF_PAYLOAD, handoff.payload)
        putBoolean(
            KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION,
            handoff.dismissEntrySurfaceOnForegroundOperation
        )
    }

    fun entryHandoffFromBundle(bundle: Bundle): EntryHandoff {
        val source = bundle.getString(KEY_HANDOFF_SOURCE).orEmpty()
        return EntryHandoff(
            id = bundle.getString(KEY_HANDOFF_ID).orEmpty(),
            source = source,
            payload = bundle.getString(KEY_HANDOFF_PAYLOAD).orEmpty(),
            dismissEntrySurfaceOnForegroundOperation = if (
                bundle.containsKey(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            ) {
                bundle.getBoolean(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            } else {
                source == LEGACY_BREENO_HANDOFF_SOURCE
            }
        )
    }

    fun toBundle(result: RunResult, payloadDirectory: File? = null): Bundle =
        result.toBundle(compactForDrain = false, payloadDirectory)

    private fun RunResult.toBundle(compactForDrain: Boolean, payloadDirectory: File? = null): Bundle = Bundle().apply {
        if (!compactForDrain) AgentWireText.put(this, "complete_result_json", json.encodeToString(this@toBundle), payloadDirectory)
        putString("operation", operation)
        rewriteTargetMessageId?.let { putString("rewrite_target_message_id", it) }
        if (compactForDrain && runId.isNotBlank()) {
            putString("context_snapshot_ref", runId)
        } else {
            contextSnapshot?.encode()?.takeIf { it.length <= 16_384 }?.let { putString("context_snapshot", it) }
        }
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_OK, ok)
        putString(
            KEY_CONTENT,
            content.boundedText(
                if (compactForDrain) MAX_DRAIN_CONTENT_CHARS else MAX_RESULT_CONTENT_CHARS
            ),
        )
        putString(
            KEY_REASONING_CONTENT,
            reasoningContent.boundedText(
                if (compactForDrain) MAX_DRAIN_REASONING_CHARS else MAX_RESULT_REASONING_CHARS
            ),
        )
        putString(KEY_ERROR, error?.boundedText(MAX_DRAIN_CONTENT_CHARS))
        putString(
            KEY_TRANSCRIPT_JSON,
            if (compactForDrain) {
                AgentLegacyConversationProjection.encode(transcript, AgentLegacyConversationProjection.DRAIN_CHARS)
            } else {
                AgentLegacyConversationProjection.encode(transcript, AgentLegacyConversationProjection.DIRECT_CHARS)
            },
        )
    }

    fun runResultFromBundle(bundle: Bundle): RunResult =
        AgentWireText.read(bundle, "complete_result_json")?.let { json.decodeFromString<RunResult>(it) } ?: RunResult(
            contextSnapshot = AgentContextSnapshot.decode(bundle.getString("context_snapshot")),
            contextSnapshotRef = bundle.getString("context_snapshot_ref").orEmpty(),
            operation = bundle.getString("operation") ?: OP_CHAT,
            rewriteTargetMessageId = bundle.getString("rewrite_target_message_id"),
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            ok = bundle.getBoolean(KEY_OK),
            content = bundle.getString(KEY_CONTENT).orEmpty(),
            error = bundle.getString(KEY_ERROR),
            reasoningContent = bundle.getString(KEY_REASONING_CONTENT).orEmpty(),
            transcript = AgentConversationCodec.decodeTranscript(bundle.getString(KEY_TRANSCRIPT_JSON)),
        )

    fun toBundle(completedRun: CompletedRun): Bundle = completedRun.toBundle(compactForDrain = false)

    private fun CompletedRun.toBundle(compactForDrain: Boolean): Bundle = Bundle().apply {
        putBundle(KEY_HANDOFF, toBundle(handoff))
        putBundle(KEY_RESULT, result.toBundle(compactForDrain))
        putLong(KEY_CREATED_AT, createdAt)
    }

    fun completedRunFromBundle(bundle: Bundle): CompletedRun =
        CompletedRun(
            handoff = entryHandoffFromBundle(bundle.getBundle(KEY_HANDOFF) ?: Bundle()),
            result = runResultFromBundle(bundle.getBundle(KEY_RESULT) ?: Bundle()),
            createdAt = bundle.getLong(KEY_CREATED_AT)
        )

    fun completedRunsToBundle(results: List<CompletedRun>): Bundle = Bundle().apply {
        putParcelableArrayList(
            KEY_RESULTS,
            ArrayList(results.map { it.toBundle(compactForDrain = true) }),
        )
    }

    fun completedRunsFromBundle(bundle: Bundle): List<CompletedRun> =
        bundle.getParcelableArrayList(KEY_RESULTS, Bundle::class.java)
            .orEmpty()
            .map(::completedRunFromBundle)

    fun ackBundle(runId: String): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
    }

    /**
     * 查询活跃 run 的响应：runId（空串表示没有 primary）+ 是否暂停 + 仍在执行的 run 总数。
     *
     * 总数必须独立回报：多会话并发时 primary 结束会把 [KEY_RUN_ID] 清空，
     * 而后台 run 仍在跑，只看 runId 无法区分「真的没了」和「只是没有 primary」。
     */
    fun activeRunBundle(runId: String, paused: Boolean, runningCount: Int): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_RUN_PAUSED, paused)
        putInt(KEY_RUN_COUNT, runningCount)
    }

    fun runPausedFromBundle(bundle: Bundle): Boolean =
        bundle.getBoolean(KEY_RUN_PAUSED, false)

    /** 仍在执行的 run 数量；旧版 Runtime 未写该键时返回 [UNKNOWN_RUN_COUNT]。 */
    fun runningCountFromBundle(bundle: Bundle): Int =
        bundle.getInt(KEY_RUN_COUNT, UNKNOWN_RUN_COUNT)

    fun activeRunsToBundle(runs: List<ActiveRunSnapshot>): Bundle = Bundle().apply {
        putParcelableArrayList(
            "active_runs",
            ArrayList(
                runs.map { run ->
                    Bundle().apply {
                        putString(KEY_RUN_ID, run.runId)
                        putString("conversation_id", run.conversationId)
                        putString("phase", run.phase.wireValue)
                        putString("operation", run.operation)
                        putString("handoff_source", run.handoffSource)
                        putBoolean("paused", run.paused)
                    }
                },
            ),
        )
    }

    fun activeRunsFromBundle(bundle: Bundle): List<ActiveRunSnapshot> =
        bundle.getParcelableArrayList("active_runs", Bundle::class.java).orEmpty().mapNotNull { item ->
            val runId = runIdFromBundle(item).takeIf(String::isNotBlank) ?: return@mapNotNull null
            ActiveRunSnapshot(
                runId = runId,
                conversationId = item.getString("conversation_id").orEmpty(),
                phase = RunPhase.fromWireValue(item.getString("phase").orEmpty()),
                operation = item.getString("operation") ?: OP_CHAT,
                handoffSource = item.getString("handoff_source").orEmpty(),
                paused = item.getBoolean("paused", false),
            )
        }

    fun attachRunResponseBundle(runId: String, attached: Boolean): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_OK, attached)
    }

    fun attachRunSucceeded(bundle: Bundle): Boolean = bundle.getBoolean(KEY_OK)

    fun runIdFromBundle(bundle: Bundle): String =
        bundle.getString(KEY_RUN_ID).orEmpty()

    /** 构造一条 steering 消息体：目标 run + 补充正文。 */
    fun supplementBundle(runId: String, text: String): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putString(KEY_SUPPLEMENT_TEXT, text)
    }

    /** 构造补充指令回执：目标 run + 是否被接纳。 */
    fun supplementAckBundle(runId: String, accepted: Boolean): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_SUPPLEMENT_ACCEPTED, accepted)
    }

    /** 读取补充指令回执；字段缺失一律按「未接纳」处理，避免界面误清草稿。 */
    fun supplementAcceptedFromBundle(bundle: Bundle): Boolean =
        bundle.getBoolean(KEY_SUPPLEMENT_ACCEPTED, false)

    private fun String.boundedText(maxChars: Int): String =
        if (length <= maxChars) this else take((maxChars - TRUNCATED_SUFFIX.length).coerceAtLeast(0)) + TRUNCATED_SUFFIX

    private fun requireStartRequestWithinBinderBudget(bundle: Bundle) {
        val parcel = Parcel.obtain()
        val sizeBytes = try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
        if (sizeBytes > MAX_START_REQUEST_PARCEL_BYTES) {
            throw PayloadTooLargeException(sizeBytes)
        }
    }

    /** 将 [AgentEvent] 打包为可跨进程传递的 [Bundle]。 */
    fun eventToBundle(event: AgentEvent): Bundle = Bundle().apply {
        when (event) {
            is AgentEvent.GuiControlChanged -> {
                putString(KEY_TYPE, "gui_control_changed")
                putString("status", event.status.wireValue)
            }

            is AgentEvent.RunStarted -> {
                putString(KEY_TYPE, "run_started")
                putInt("initial_images", event.initialImages)
                putInt("initial_image_bytes", event.initialImageBytes)
                putInt("tool_count", event.toolCount)
                putBoolean("terminal_tools", event.terminalTools)
            }

            is AgentEvent.RoundStarted -> {
                putString(KEY_TYPE, "round_started")
                putInt("round", event.round)
                putInt("message_count", event.messageCount)
            }

            is AgentEvent.ContextCompaction -> {
                putString(KEY_TYPE, "context_compaction")
                putString("operation_id", event.operationId)
                putString("phase", event.phase)
                event.tokensBefore?.let { putInt("tokens_before", it) }
                event.tokensAfter?.let { putInt("tokens_after", it) }
                putString("reason_code", event.reasonCode)
                putString("detail", event.detail)
                event.charsBefore?.let { putInt("chars_before", it) }
                event.charsAfter?.let { putInt("chars_after", it) }
                event.durationMs?.let { putLong("duration_ms", it) }
            }

            is AgentEvent.ModelRetryScheduled -> {
                putString(KEY_TYPE, "model_retry_scheduled")
                putInt("round", event.round)
                putInt("attempt", event.attempt)
                putInt("max_attempts", event.maxAttempts)
                putInt("delay_ms", event.delayMs)
                putString("reason_code", event.reasonCode)
            }

            is AgentEvent.ProviderRequestStarted -> {
                putString(KEY_TYPE, "provider_request_started")
                putInt("round", event.round)
                putString("model", event.model)
                putString("provider", event.provider)
                putString("effort", event.effort)
                putInt("attempt", event.attempt)
            }

            is AgentEvent.ProviderResponseStarted -> {
                putString(KEY_TYPE, "provider_response_started")
                putInt("round", event.round)
                putInt("http_code", event.httpCode)
            }

            is AgentEvent.AssistantBlockStart -> {
                putString(KEY_TYPE, "assistant_block_start")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putString("block_id", event.blockId)
                putString("name", event.name)
            }

            is AgentEvent.AssistantBlockDelta -> {
                putString(KEY_TYPE, "assistant_block_delta")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putInt("delta_chars", event.deltaChars)
                putString("delta", event.delta)
            }

            is AgentEvent.AssistantBlockEnd -> {
                putString(KEY_TYPE, "assistant_block_end")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putString("block_id", event.blockId)
                putString("name", event.name)
                putInt("content_chars", event.contentChars)
                putString("replacement_content", event.replacementContent)
            }

            is AgentEvent.SearchSuggestions -> {
                require(SearchSuggestionsPayload.isValidHtml(event.html)) { "SEARCH_SUGGESTIONS_INVALID_PAYLOAD" }
                putString(KEY_TYPE, "search_suggestions")
                putInt("round", event.round)
                putInt("index", event.index)
                putString("html", event.html)
            }

            is AgentEvent.AssistantReceived -> {
                putString(KEY_TYPE, "assistant_received")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
                putString("reasoning_content", event.reasoningContent)
                putStringArrayList("tool_names", ArrayList(event.toolNames))
            }

            is AgentEvent.UsageReceived -> {
                putString(KEY_TYPE, "usage_received")
                putInt("round", event.round)
                putTokenUsage(event.usage)
            }

            is AgentEvent.UserSupplementReceived -> {
                putString(KEY_TYPE, "user_supplement_received")
                putInt("index", event.index)
                putString("text", event.text)
            }

            is AgentEvent.ToolStarted -> {
                putString(KEY_TYPE, "tool_started")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("args_preview", event.argsPreview)
                event.command?.let { putString("command", it) }
                event.askQuestion?.let { putString("ask_question", it) }
                if (event.askOptions.isNotEmpty()) {
                    putStringArrayList("ask_options", ArrayList(event.askOptions))
                }
            }

            is AgentEvent.ToolFinished -> {
                putString(KEY_TYPE, "tool_finished")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("result_summary", event.resultSummary)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
                event.success?.let { putBoolean("success", it) }
                event.detail?.let { putString("detail", it) }
                event.elapsedMs?.let { putLong("elapsed_ms", it) }
                event.filePath?.let { putString("file_path", it) }
            }

            is AgentEvent.HostedToolStarted -> {
                putString(KEY_TYPE, "hosted_tool_started")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
            }

            is AgentEvent.HostedToolFinished -> {
                putString(KEY_TYPE, "hosted_tool_finished")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putBoolean("success", event.success)
            }

            is AgentEvent.ToolImagesAttached -> {
                putString(KEY_TYPE, "tool_images_attached")
                putInt("round", event.round)
                putString("tool_name", event.toolName)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
            }

            is AgentEvent.ToolProgress -> {
                putString(KEY_TYPE, "tool_progress")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("kind", event.kind)
                putString("detail", event.detail)
                event.elapsedMs?.let { putLong("elapsed_ms", it) }
            }

            is AgentEvent.SubagentProgress -> {
                putString(KEY_TYPE, "subagent_progress")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("subagent_id", event.subagentId)
                putString("subagent_type", event.subagentType)
                putString("snapshot_json", event.snapshotJson.boundedText(MAX_DRAIN_CONTENT_CHARS))
            }

            is AgentEvent.RunFinished -> {
                putString(KEY_TYPE, "run_finished")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
            }

            is AgentEvent.RunFailed -> {
                putString(KEY_TYPE, "run_failed")
                putString("reason", event.reason)
            }
        }
    }

    /** 将 [Bundle] 还原为 [AgentEvent]，无法识别时返回 null。 */
    fun eventFromBundle(bundle: Bundle): AgentEvent? = when (bundle.getString(KEY_TYPE)) {
        "gui_control_changed" -> GuiControlStatus.fromWireValue(bundle.getString("status").orEmpty())
            ?.let { AgentEvent.GuiControlChanged(it) }

        "run_started" -> AgentEvent.RunStarted(
            initialImages = bundle.getInt("initial_images"),
            initialImageBytes = bundle.getInt("initial_image_bytes"),
            toolCount = bundle.getInt("tool_count"),
            terminalTools = bundle.getBoolean("terminal_tools"),
        )

        "round_started" -> AgentEvent.RoundStarted(
            round = bundle.getInt("round"),
            messageCount = bundle.getInt("message_count"),
        )

        "context_compaction" -> AgentEvent.ContextCompaction(
            operationId = bundle.getString("operation_id").orEmpty(),
            phase = bundle.getString("phase").orEmpty(),
            tokensBefore = bundle.optionalInt("tokens_before"),
            tokensAfter = bundle.optionalInt("tokens_after"),
            reasonCode = bundle.getString("reason_code").orEmpty(),
            detail = bundle.getString("detail").orEmpty(),
            charsBefore = bundle.optionalInt("chars_before"),
            charsAfter = bundle.optionalInt("chars_after"),
            durationMs = bundle.optionalElapsedMs("duration_ms"),
        )
        "model_retry_scheduled" -> AgentEvent.ModelRetryScheduled(
            round = bundle.getInt("round"),
            attempt = bundle.getInt("attempt", 1),
            maxAttempts = bundle.getInt("max_attempts", 3),
            delayMs = bundle.getInt("delay_ms", 2_000),
            reasonCode = bundle.getString("reason_code").orEmpty(),
        )

        "provider_request_started" -> AgentEvent.ProviderRequestStarted(
            round = bundle.getInt("round"),
            model = bundle.getString("model").orEmpty(),
            provider = bundle.getString("provider").orEmpty(),
            effort = bundle.getString("effort").orEmpty(),
            attempt = bundle.getInt("attempt", 1),
        )

        "provider_response_started" -> AgentEvent.ProviderResponseStarted(
            round = bundle.getInt("round"),
            httpCode = bundle.getInt("http_code"),
        )

        "assistant_block_start" -> AgentEvent.AssistantBlockStart(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
        )

        "assistant_block_delta" -> AgentEvent.AssistantBlockDelta(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            deltaChars = bundle.getInt("delta_chars"),
            delta = bundle.getString("delta").orEmpty(),
        )

        "assistant_block_end" -> AgentEvent.AssistantBlockEnd(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
            contentChars = bundle.getInt("content_chars"),
            replacementContent = bundle.getString("replacement_content"),
        )

        "search_suggestions" -> bundle.getString("html")
            ?.takeIf(SearchSuggestionsPayload::isValidHtml)
            ?.let { html ->
                AgentEvent.SearchSuggestions(bundle.getInt("round"), bundle.getInt("index"), html)
                    .takeIf { it.round >= 0 && it.index >= 0 }
            }

        "assistant_received" -> AgentEvent.AssistantReceived(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
            reasoningContent = bundle.getString("reasoning_content").orEmpty(),
            toolNames = bundle.getStringArrayList("tool_names").orEmpty(),
        )

        "usage_received" -> AgentEvent.UsageReceived(
            round = bundle.getInt("round"),
            usage = bundle.getTokenUsage(),
        )

        "user_supplement_received" -> AgentEvent.UserSupplementReceived(
            index = bundle.getInt("index"),
            text = bundle.getString("text").orEmpty(),
        )

        "tool_started" -> AgentEvent.ToolStarted(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            argsPreview = bundle.getString("args_preview").orEmpty(),
            command = bundle.getString("command"),
            // 旧版 Runtime 不带这两个键；缺省即「不是提问」。
            askQuestion = bundle.getString("ask_question"),
            askOptions = bundle.getStringArrayList("ask_options")?.toList().orEmpty(),
        )

        "tool_finished" -> AgentEvent.ToolFinished(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            resultSummary = bundle.getString("result_summary").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
            // 旧版本 Runtime 不发送 success，缺省为 null 由消费端回退判断
            success = if (bundle.containsKey("success")) bundle.getBoolean("success") else null,
            detail = bundle.getString("detail"),
            elapsedMs = bundle.optionalElapsedMs("elapsed_ms"),
            filePath = bundle.getString("file_path")?.takeIf { it.isNotBlank() },
        )

        "hosted_tool_started" -> AgentEvent.HostedToolStarted(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
        )

        "hosted_tool_finished" -> AgentEvent.HostedToolFinished(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            success = bundle.getBoolean("success"),
        )

        "tool_images_attached" -> AgentEvent.ToolImagesAttached(
            round = bundle.getInt("round"),
            toolName = bundle.getString("tool_name").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
        )

        "tool_progress" -> AgentEvent.ToolProgress(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            kind = bundle.getString("kind").orEmpty(),
            detail = bundle.getString("detail").orEmpty(),
            elapsedMs = bundle.optionalElapsedMs("elapsed_ms"),
        )

        "subagent_progress" -> AgentEvent.SubagentProgress(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            subagentId = bundle.getString("subagent_id").orEmpty(),
            subagentType = bundle.getString("subagent_type").orEmpty(),
            snapshotJson = bundle.getString("snapshot_json").orEmpty(),
        )

        "run_finished" -> AgentEvent.RunFinished(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
        )

        "run_failed" -> AgentEvent.RunFailed(
            reason = bundle.getString("reason").orEmpty(),
        )

        else -> null
    }

    private fun Bundle.optionalInt(key: String): Int? =
        if (containsKey(key)) getInt(key) else null

    /**
     * 读一个可能以 int 或 long 形式存在的毫秒值。
     *
     * 为什么需要：Bundle 是强类型的 —— `putLong` 写的键用 `getInt` 读会抛，
     * `putInt` 写的键用 `getLong` 读得到 0（不抛， silently 错）。
     * 而 JSON 归档路径（AgentEventJsonCodec）无法区分 int 与 long：
     * `JSONObject(String)` 解析出的数字放得进 int 就是 Integer。
     * 于是同一个 `elapsed_ms` 键在两条路上类型不同，读取端必须两者都认。
     */
    private fun Bundle.optionalElapsedMs(key: String): Long? {
        if (!containsKey(key)) return null
        val raw = get(key)
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun Bundle.putTokenUsage(usage: AgentTokenUsage) {
        usage.contextTokens?.let { putInt("usage_context", it) }
        usage.inputTokens?.let { putInt("usage_input", it) }
        usage.outputTokens?.let { putInt("usage_output", it) }
        usage.reasoningTokens?.let { putInt("usage_reasoning", it) }
        usage.cachedTokens?.let { putInt("usage_cache", it) }
        usage.promptTokens?.let { putInt("usage_prompt", it) }
    }

    private fun Bundle.getTokenUsage(): AgentTokenUsage =
        AgentTokenUsage(
            contextTokens = optionalInt("usage_context"),
            inputTokens = optionalInt("usage_input"),
            outputTokens = optionalInt("usage_output"),
            reasoningTokens = optionalInt("usage_reasoning"),
            cachedTokens = optionalInt("usage_cache"),
            promptTokens = optionalInt("usage_prompt"),
        )

    private fun decodeCustomHeaders(raw: String?): List<CustomHeader> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<CustomHeader>>(raw) }.getOrDefault(emptyList())

    private fun decodeCustomBody(raw: String?): List<CustomBody> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<CustomBody>>(raw) }.getOrDefault(emptyList())

    private fun decodeReasoningCapabilities(raw: String?): ModelReasoningCapabilities? =
        if (raw.isNullOrBlank()) null
        else runCatching { json.decodeFromString<ModelReasoningCapabilities>(raw) }.getOrNull()

}
