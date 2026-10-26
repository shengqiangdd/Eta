package io.github.mangi.eta.agent.trace

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Agent 运行追踪：一次 run 一个 JSONL 文件（`files/run-traces/<runId>.jsonl`）。
 *
 * 与消息流的区别：消息里只保留给人看的摘要（工具结果截断到十几行），
 * 这里保留工具参数 / 结果的完整文本（有上限）、每一步耗时与重试原因，
 * 用于「运行详情」回答「Agent 到底做了什么、慢在哪一步、为什么重试」。
 *
 * 设计取舍与 [io.github.mangi.eta.agent.usage.AgentUsageLedger] 一致：
 * 追踪是**可丢失的观测数据** —— 损坏一行只丢一条记录，写失败静默，
 * 任何情况下都不能影响对话本身。
 */
internal object AgentRunTraceStore {

    data class Entry(
        val kind: String,
        val at: Long,
        val round: Int? = null,
        val messageCount: Int? = null,
        val toolCount: Int? = null,
        val httpCode: Int? = null,
        val chars: Int? = null,
        val name: String? = null,
        val ok: Boolean? = null,
        val elapsedMs: Long? = null,
        val arguments: String? = null,
        val result: String? = null,
        val resultChars: Int? = null,
        val imageCount: Int? = null,
        val attempt: Int? = null,
        val maxAttempts: Int? = null,
        val delayMs: Long? = null,
        val code: String? = null,
        val phase: String? = null,
        val contextTokens: Int? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val cachedTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val before: Int? = null,
        val after: Int? = null,
        /** 该次请求实际使用的模型 / 思考等级（热切换与重试后可变，所以不能只信 meta 里的启动值）。 */
        val model: String? = null,
        val effort: String? = null,
        /** 压缩前后的上下文字符数：本地精确值，token 只有远端回传时才有。 */
        val charsBefore: Int? = null,
        val charsAfter: Int? = null,
        val error: String? = null,
    )

    data class Trace(
        val runId: String,
        val model: String,
        val provider: String,
        val purpose: String,
        val startedAt: Long,
        val finishedAt: Long?,
        val ok: Boolean?,
        val error: String?,
        val entries: List<Entry>,
        val truncated: Boolean,
    ) {
        val rounds: Int get() = entries.filter { it.kind == KIND_ROUND }.size
        val toolCalls: Int get() = entries.count { it.kind == KIND_TOOL }
        val toolFailures: Int get() = entries.count { it.kind == KIND_TOOL && it.ok == false }
        val retries: Int get() = entries.count { it.kind == KIND_RETRY }
        val totalTokens: Int
            get() = entries.filter { it.kind == KIND_USAGE }
                .sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
        val durationMs: Long get() = (finishedAt ?: entries.lastOrNull()?.at ?: startedAt) - startedAt

        /** 本次运行的**输出** token 合计（输入不计入速度）。 */
        val outputTokens: Int
            get() = entries.filter { it.kind == KIND_USAGE }.sumOf { it.outputTokens ?: 0 }

        /**
         * 模型生成耗时（估算）。
         *
         * 口径：每个请求从发出到「这一轮的产物结束」。结束点取该轮**第一条工具记录**、
         * 下一次请求、运行结束三者中最早的那个 ——
         * 工具耗时不属于模型速度，把它算进去会让 tok/s 变成"这轮一共跑了多久"的倒数，
         * 工具越慢数字越假。
         *
         * 因此这个值包含首字延迟（TTFT），是"端到端生成速度"，不是纯解码速度。
         */
        val modelMs: Long
            get() {
                if (entries.isEmpty()) return 0
                val endOfRun = finishedAt ?: entries.last().at
                var sum = 0L
                entries.forEachIndexed { index, entry ->
                    if (entry.kind != KIND_REQUEST) return@forEachIndexed
                    val boundary = entries.subList(index + 1, entries.size).firstOrNull {
                        it.kind == KIND_TOOL || it.kind == KIND_REQUEST || it.kind == KIND_DONE
                    }?.at ?: endOfRun
                    sum += (boundary - entry.at).coerceAtLeast(0)
                }
                return sum
            }

        /**
         * 输出速度（token/秒）。
         *
         * 没有 usage（部分供应商不回传）或没有耗时数据时返回 null ——
         * 界面显示「—」，而不是拿 0 或猜测值糊上去。
         */
        val outputTokensPerSecond: Double?
            get() {
                val tokens = outputTokens
                val ms = modelMs
                if (tokens <= 0 || ms <= 0) return null
                return tokens * 1000.0 / ms
            }

        /** 每一步相对开始的毫秒数，供时间线展示；缺失时回退 0。 */
        fun offsetOf(entry: Entry): Long = (entry.at - startedAt).coerceAtLeast(0)
    }

    data class Summary(
        val runId: String,
        val model: String,
        val provider: String,
        val purpose: String,
        val startedAt: Long,
        val finishedAt: Long?,
        val ok: Boolean?,
        val rounds: Int,
        val toolCalls: Int,
        val toolFailures: Int,
        val retries: Int,
        val totalTokens: Int,
        val error: String?,
        val sizeBytes: Long,
    ) {
        val durationMs: Long
            get() = (finishedAt ?: startedAt) - startedAt
    }

    private val lock = Any()

    @Volatile
    private var directory: File? = null

    @Volatile
    private var retention: Retention = Retention()

    data class Retention(
        val maxRuns: Int = MAX_RUNS,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        val maxRunBytes: Long = MAX_RUN_BYTES,
    )

    fun init(context: Context) {
        init(File(context.applicationContext.filesDir, DIRECTORY_NAME))
    }

    fun init(dir: File, policy: Retention = Retention()) {
        directory = dir
        retention = policy
    }

    /** 是否已经初始化；未初始化时所有写入静默跳过（例如纯单元测试环境）。 */
    private fun dirOrNull(): File? = directory

    fun begin(
        runId: String,
        model: String,
        provider: String,
        purpose: String = "",
        contextWindow: Int? = null,
    ) {
        val target = dirOrNull() ?: return
        val safeId = safeRunId(runId) ?: return
        synchronized(lock) {
            runCatching {
                target.mkdirs()
                prune(target)
                val file = File(target, "$safeId.jsonl")
                val meta = JSONObject()
                    .put("k", KIND_META)
                    .put("v", FORMAT_VERSION)
                    .put("id", runId.take(120))
                    .put("at", System.currentTimeMillis())
                    .put("model", model.take(120))
                    .put("provider", provider.take(120))
                    .put("purpose", purpose.take(24))
                    .put("window", contextWindow ?: 0)
                FileOutputStream(file, false).use { stream ->
                    stream.write((meta.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    fun record(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.RunStarted -> append(
                runId,
                JSONObject()
                    .put("k", KIND_START)
                    .put("tools", event.toolCount)
                    .put("images", event.initialImages),
            )
            is AgentEvent.RoundStarted -> append(
                runId,
                JSONObject()
                    .put("k", KIND_ROUND)
                    .put("r", event.round)
                    .put("msgs", event.messageCount),
            )
            is AgentEvent.ProviderRequestStarted -> append(
                runId,
                JSONObject()
                    .put("k", KIND_REQUEST)
                    .put("r", event.round)
                    .put("try", event.attempt)
                    .put("model", event.model.take(120))
                    .put("provider", event.provider.take(120))
                    .put("effort", event.effort.take(24)),
            )
            is AgentEvent.ProviderResponseStarted -> append(
                runId,
                JSONObject()
                    .put("k", KIND_RESPONSE)
                    .put("r", event.round)
                    .put("http", event.httpCode),
            )
            is AgentEvent.AssistantBlockEnd -> {
                val kind = when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> KIND_THINKING
                    AgentEvent.AssistantBlockKind.TEXT -> KIND_TEXT
                    AgentEvent.AssistantBlockKind.TOOL_CALL -> null
                }
                if (kind != null) {
                    append(
                        runId,
                        JSONObject()
                            .put("k", kind)
                            .put("r", event.round)
                            .put("chars", event.contentChars),
                    )
                }
            }
            is AgentEvent.UsageReceived -> append(
                runId,
                JSONObject()
                    .put("k", KIND_USAGE)
                    .put("r", event.round)
                    .put("ctx", event.usage.contextTokens ?: 0)
                    .put("in", event.usage.inputTokens ?: 0)
                    .put("out", event.usage.outputTokens ?: 0)
                    .put("cache", event.usage.cachedTokens ?: 0)
                    .put("reason", event.usage.reasoningTokens ?: 0),
            )
            is AgentEvent.ModelRetryScheduled -> append(
                runId,
                JSONObject()
                    .put("k", KIND_RETRY)
                    .put("r", event.round)
                    .put("try", event.attempt)
                    .put("max", event.maxAttempts)
                    .put("delay", event.delayMs)
                    .put("code", event.reasonCode.take(60)),
            )
            is AgentEvent.ContextCompaction -> append(
                runId,
                JSONObject()
                    .put("k", KIND_COMPACT)
                    .put("phase", event.phase)
                    .put("code", event.reasonCode.take(60))
                    .put("detail", event.detail.take(120))
                    // 缺失就不要写 0：伪造的 0 会让「本次压缩」在追踪里长成 0 → 0。
                    .apply {
                        event.tokensBefore?.let { put("before", it) }
                        event.tokensAfter?.let { put("after", it) }
                        event.charsBefore?.let { put("chars_before", it) }
                        event.charsAfter?.let { put("chars_after", it) }
                        // 复用通用的 "ms" 键（Entry.elapsedMs）：压缩耗时是「为什么慢」的关键证据。
                        event.durationMs?.let { put("ms", it) }
                    },
            )
            // 结束状态由 [finish] 统一写入：执行层（executor）在失败/取消路径上不一定
            // 走得到 RunFailed 事件，而「运行结束」必须在追踪里恰好出现一次。
            else -> Unit
        }
    }

    fun finish(runId: String, ok: Boolean, error: String = "", contentChars: Int = 0) {
        append(
            runId,
            JSONObject()
                .put("k", KIND_DONE)
                .put("ok", ok)
                .put("chars", contentChars.coerceAtLeast(0))
                .put("err", error.take(MAX_ERROR_CHARS)),
        )
    }

    /**
     * 工具调用的完整记录：事件里的摘要不够用，这里保留参数与结果的原文（有上限）与耗时。
     * [arguments] / [result] 只在长度允许时写入，超长会截断并保留原始字符数。
     */
    fun recordTool(
        runId: String,
        round: Int,
        name: String,
        arguments: String,
        result: String,
        elapsedMs: Long,
        ok: Boolean,
        imageCount: Int = 0,
        sensitive: Boolean = false,
    ) {
        // 与消息落库同一条隐私边界：敏感工具的参数与原始结果不写进任何持久化副本，
        // 追踪里只留下「调用过、耗时多少、成功与否」。
        val args = if (sensitive) "" else arguments.take(MAX_ARGUMENT_CHARS)
        val res = if (sensitive) "" else result.take(MAX_RESULT_CHARS)
        append(
            runId,
            JSONObject()
                .put("k", KIND_TOOL)
                .put("r", round)
                .put("name", name.take(120))
                .put("ms", elapsedMs.coerceAtLeast(0))
                .put("ok", ok)
                .put("args", args)
                .put("args_chars", if (sensitive) 0 else arguments.length)
                .put("res", res)
                .put("res_chars", if (sensitive) 0 else result.length)
                .put("imgs", imageCount)
                .put("redacted", sensitive),
        )
    }

    /** 运行中热切换模型：追踪里单独记一条，才能回答「从哪轮开始换了模型」。 */
    fun recordModelChange(
        runId: String,
        model: String,
        provider: String,
        effort: String,
        source: String = "hot_swap",
    ) {
        append(
            runId,
            JSONObject()
                .put("k", KIND_MODEL)
                .put("model", model.take(120))
                .put("provider", provider.take(120))
                .put("effort", effort.take(24))
                .put("src", source.take(24)),
        )
    }

    /** 运行停止（用户取消）不是失败，但要在时间线上收尾。 */
    fun recordStopped(runId: String, reason: String = "已停止") {
        append(
            runId,
            JSONObject()
                .put("k", KIND_DONE)
                .put("ok", false)
                .put("stopped", true)
                .put("err", reason.take(MAX_ERROR_CHARS)),
        )
    }

    fun list(limit: Int = 50): List<Summary> {
        val target = dirOrNull() ?: return emptyList()
        val files = runCatching {
            target.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }.getOrDefault(emptyList())
        return files.take(limit.coerceIn(1, MAX_RUNS)).mapNotNull { file ->
            runCatching { summarize(file) }.getOrNull()
        }
    }

    fun read(runId: String): Trace? {
        val target = dirOrNull() ?: return null
        val safeId = safeRunId(runId) ?: return null
        val file = File(target, "$safeId.jsonl")
        if (!file.isFile) return null
        return runCatching { parse(file) }.getOrNull()
    }

    fun delete(runId: String) {
        val target = dirOrNull() ?: return
        val safeId = safeRunId(runId) ?: return
        synchronized(lock) {
            runCatching { File(target, "$safeId.jsonl").delete() }
        }
    }

    fun clear() {
        val target = dirOrNull() ?: return
        synchronized(lock) {
            runCatching {
                target.listFiles()?.forEach { file -> file.delete() }
            }
        }
    }

    fun directory(): File? = dirOrNull()

    private fun append(runId: String, payload: JSONObject) {
        val target = dirOrNull() ?: return
        val safeId = safeRunId(runId) ?: return
        synchronized(lock) {
            runCatching {
                val file = File(target, "$safeId.jsonl")
                if (!file.isFile) return
                if (file.length() >= retention.maxRunBytes) return
                payload.put("at", System.currentTimeMillis())
                FileOutputStream(file, true).use { stream ->
                    stream.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun prune(target: File) {
        val files = target.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        // 调用方紧接着要写一份新文件：保留 maxRuns - 1 份旧的，总数才不会超。
        val keep = (retention.maxRuns - 1).coerceAtLeast(0)
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= keep || total > retention.maxTotalBytes) {
                runCatching { file.delete() }
            }
        }
    }

    private fun summarize(file: File): Summary? {
        val meta = firstJson(file) ?: return null
        if (meta.optString("k") != KIND_META) return null
        var finishedAt: Long? = null
        var ok: Boolean? = null
        var error: String? = null
        var rounds = 0
        var tools = 0
        var failures = 0
        var retries = 0
        var tokens = 0
        readLines(file) { json ->
            when (json.optString("k")) {
                KIND_ROUND -> rounds += 1
                KIND_TOOL -> {
                    tools += 1
                    if (!json.optBoolean("ok", true)) failures += 1
                }
                KIND_RETRY -> retries += 1
                KIND_USAGE -> tokens += json.optInt("in", 0) + json.optInt("out", 0)
                KIND_DONE -> {
                    finishedAt = json.optLong("at", 0).takeIf { it > 0 }
                    ok = json.optBoolean("ok", false)
                    error = json.optString("err").takeIf(String::isNotBlank)
                }
            }
        }
        return Summary(
            runId = meta.optString("id").ifBlank { file.nameWithoutExtension },
            model = meta.optString("model"),
            provider = meta.optString("provider"),
            purpose = meta.optString("purpose"),
            startedAt = meta.optLong("at", file.lastModified()),
            finishedAt = finishedAt,
            ok = ok,
            rounds = rounds,
            toolCalls = tools,
            toolFailures = failures,
            retries = retries,
            totalTokens = tokens,
            error = error,
            sizeBytes = file.length(),
        )
    }

    private fun parse(file: File): Trace {
        var model = ""
        var provider = ""
        var purpose = ""
        var startedAt = file.lastModified()
        var finishedAt: Long? = null
        var ok: Boolean? = null
        var error: String? = null
        var truncated = false
        val entries = ArrayList<Entry>()
        readLines(file) { json ->
            when (json.optString("k")) {
                KIND_META -> {
                    model = json.optString("model")
                    provider = json.optString("provider")
                    purpose = json.optString("purpose")
                    startedAt = json.optLong("at", startedAt)
                }
                KIND_DONE -> {
                    finishedAt = json.optLong("at", 0).takeIf { it > 0 }
                    ok = json.optBoolean("ok", false)
                    error = json.optString("err").takeIf(String::isNotBlank)
                    entries += json.toEntry()
                }
                KIND_TRUNCATED -> truncated = true
                "" -> Unit
                else -> entries += json.toEntry()
            }
        }
        return Trace(
            runId = file.nameWithoutExtension,
            model = model,
            provider = provider,
            purpose = purpose,
            startedAt = startedAt,
            finishedAt = finishedAt,
            ok = ok,
            error = error,
            entries = entries,
            truncated = truncated,
        )
    }

    private fun JSONObject.toEntry(): Entry = Entry(
        kind = optString("k"),
        at = optLong("at", 0),
        round = optIntOrNull("r"),
        messageCount = optIntOrNull("msgs"),
        toolCount = optIntOrNull("tools"),
        httpCode = optIntOrNull("http"),
        chars = optIntOrNull("chars"),
        name = optString("name").takeIf(String::isNotBlank),
        ok = if (has("ok")) optBoolean("ok") else null,
        elapsedMs = if (has("ms")) optLong("ms") else null,
        arguments = optString("args").takeIf(String::isNotBlank),
        result = optString("res").takeIf(String::isNotBlank),
        resultChars = optIntOrNull("res_chars"),
        imageCount = optIntOrNull("imgs"),
        attempt = optIntOrNull("try"),
        maxAttempts = optIntOrNull("max"),
        delayMs = if (has("delay")) optLong("delay") else null,
        code = optString("code").takeIf(String::isNotBlank),
        phase = optString("phase").takeIf(String::isNotBlank),
        contextTokens = optIntOrNull("ctx"),
        inputTokens = optIntOrNull("in"),
        outputTokens = optIntOrNull("out"),
        cachedTokens = optIntOrNull("cache"),
        reasoningTokens = optIntOrNull("reason"),
        before = optIntOrNull("before"),
        after = optIntOrNull("after"),
        charsBefore = optIntOrNull("chars_before"),
        charsAfter = optIntOrNull("chars_after"),
        model = optString("model").takeIf(String::isNotBlank),
        effort = optString("effort").takeIf(String::isNotBlank),
        error = optString("err").takeIf(String::isNotBlank),
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, 0) else null

    private fun firstJson(file: File): JSONObject? = runCatching {
        FileInputStream(file).bufferedReader(Charsets.UTF_8).use { reader ->
            val line = reader.readLine() ?: return null
            JSONObject(line)
        }
    }.getOrNull()

    private fun readLines(file: File, onJson: (JSONObject) -> Unit) {
        runCatching {
            FileInputStream(file).bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    // 单行损坏只跳过这一条，不能让整份追踪读不出来。
                    runCatching { JSONObject(line) }.getOrNull()?.let(onJson)
                }
            }
        }
    }

    /** runId 会被用作文件名：只接受安全字符，其余一律拒绝（不做静默替换，避免撞名）。 */
    private fun safeRunId(runId: String): String? {
        val trimmed = runId.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_RUN_ID_LENGTH) return null
        if (!trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return null
        if (trimmed == "." || trimmed == "..") return null
        return trimmed
    }

    const val KIND_META = "meta"
    const val KIND_START = "start"
    const val KIND_ROUND = "round"
    const val KIND_REQUEST = "request"
    const val KIND_RESPONSE = "response"
    const val KIND_THINKING = "thinking"
    const val KIND_TEXT = "text"
    const val KIND_TOOL = "tool"
    const val KIND_RETRY = "retry"
    const val KIND_USAGE = "usage"
    const val KIND_COMPACT = "compact"
    const val KIND_MODEL = "model"
    const val KIND_DONE = "done"
    const val KIND_TRUNCATED = "truncated"

    private const val FORMAT_VERSION = 1
    private const val DIRECTORY_NAME = "run-traces"
    private const val MAX_RUNS = 80
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
    private const val MAX_RUN_BYTES = 3L * 1024 * 1024
    private const val MAX_RUN_ID_LENGTH = 80
    private const val MAX_ARGUMENT_CHARS = 4_000
    private const val MAX_RESULT_CHARS = 12_000
    private const val MAX_ERROR_CHARS = 2_000
}
