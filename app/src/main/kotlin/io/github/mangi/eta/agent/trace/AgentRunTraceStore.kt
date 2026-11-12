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
        /** 服务商口径的总输入（含缓存读写）；旧追踪没有这个键时为 null。 */
        val promptTokens: Int? = null,
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
        /** 所属会话；旧追踪没有这个键时为 null。 */
        val conversationId: String? = null,
    ) {
        val rounds: Int get() = entries.filter { it.kind == KIND_ROUND }.size
        val toolCalls: Int get() = entries.count { it.kind == KIND_TOOL }
        val toolFailures: Int get() = entries.count { it.kind == KIND_TOOL && it.ok == false }
        val retries: Int get() = entries.count { it.kind == KIND_RETRY }
        val totalTokens: Int
            get() = entries.filter { it.kind == KIND_USAGE }
                .sumOf { (it.promptTokens ?: it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
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
         * 工具越慢数字越假；而工具记录写在**执行结束**时，边界必须用 `at - elapsedMs`
         * 还原成它开始的那一刻（2026-11-02 实测：438 轮的一次运行里有 47% 被算成"模型时间"）。
         *
         * 速度本身不直接用这个值：见 [speedOf]，从首字起算、不含 TTFT（与 dsh 的 decodeMs 同口径）。
         *
         * 因此这个值仍包含首字等待，只用做「模型耗时」展示；tok/s 另按 [speedOf] 算。
         */
        val modelMs: Long
            get() {
                if (entries.isEmpty()) return 0
                return modelMsOf(entries, entries.indices, finishedAt ?: entries.last().at)
            }

        /**
         * 输出速度（token/秒）。
         *
         * 口径与 dsh 的 decode 速度一致：分母只算「首字 → 生成结束」，不含请求发出到首字的
         * 等待，也不含工具耗时；分子只取同一批请求自己的 output token。
         * 没有可配对的用量/时刻时返回 null —— 界面显示「—」，而不是拿 0 或猜测值糊上去。
         */
        val outputTokensPerSecond: Double? get() = speed.tokensPerSecond

        /** 纯解码速度的分子分母（口径见 [speedOf]）。 */
        val speed: SpeedRead get() = speedOf(entries, entries.indices)

        /**
         * 最近一次请求的首字延迟（TTFT）：`request → first`，不含任何排队或重试退避。
         *
         * 取**最后一次有配对**的轮次，与 [speed] 的口径一致 —— 用户关心的是此刻多快，
         * 历史平均会把「现在变慢」这个最该看见的信号糊掉。
         *
         * 没有配对时返回 null（界面不显示那一行），不返回 0：0 的含义是「瞬间响应」，
         * 那是看起来很确定的错误答案。
         */
        val lastFirstTokenMs: Long?
            get() {
                for (index in entries.indices.reversed()) {
                    if (entries[index].kind != KIND_REQUEST) continue
                    val round = entries[index].round
                    // 只在这一请求之后的记录里找首字：同一轮号出现在多次尝试时，
                    // 取离本次请求最近的那条，避免把上一次尝试的首字算进来。
                    val first = (index + 1 until entries.size)
                        .asSequence()
                        .map { entries[it] }
                        .takeWhile { it.kind != KIND_REQUEST }
                        .firstOrNull { it.kind == KIND_FIRST && it.round == round }
                        ?: continue
                    return (first.at - entries[index].at).takeIf { it >= 0 }
                }
                return null
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
        /** 所属会话；旧追踪没有这个键时为 null（未知，不是「属于某个会话」）。 */
        val conversationId: String? = null,
    ) {
        val durationMs: Long
            get() = (finishedAt ?: startedAt) - startedAt
    }

    private val lock = Any()

    /**
     * 哪些 run 的**当前请求**还没记到第一个内容块（2026-11-02）。
     *
     * 只活在内存：用途只有「每次请求只写一条 `first`」。进程重启后旧追踪里的 `first`
     * 已经落盘，新请求走到 [markAwaitingFirstToken] 会重新标记。
     */
    private val awaitingFirstToken = HashSet<String>()

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

    /**
     * 仅给测试用：回到未初始化状态，后续测试各自 init。
     *
     * 不提供的话，测试只能“重置”成某个具体目录；而 JUnit 的 TemporaryFolder
     * 跑完会删目录，下一个测试就拿到一个已不存在的根。未初始化时写入本就静默跳过
     * （见 [dirOrNull]），所以置空是安全的。
     */
    internal fun resetForTests() {
        directory = null
        retention = Retention()
        synchronized(lock) { awaitingFirstToken.clear() }
    }

    /** 是否已经初始化；未初始化时所有写入静默跳过（例如纯单元测试环境）。 */
    private fun dirOrNull(): File? = directory

    fun begin(
        runId: String,
        model: String,
        provider: String,
        purpose: String = "",
        contextWindow: Int? = null,
        /** 本次运行所属会话；空串表示未知（旧调用方 / 子代理）。 */
        conversationId: String = "",
    ) {
        val target = dirOrNull() ?: return
        val safeId = safeRunId(runId) ?: return
        synchronized(lock) {
            // 新的一份追踪必须从「还没看到首字」开始：同名 run 被复用时不能沿用上一份的标记。
            awaitingFirstToken.remove(runId)
            runCatching {
                target.mkdirs()
                prune(target, conversationId)
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
                    // 会话归属：没有它，UI 只能靠消息 id 前缀反推 runId（隐式约定）。
                    // 空串不写，保持旧文件格式不变；读取端把缺失当「未知」，不当成某个会话。
                    .apply { if (conversationId.isNotBlank()) put("conv", conversationId.take(120)) }
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
            is AgentEvent.ProviderRequestStarted -> {
                markAwaitingFirstToken(runId)
                append(
                    runId,
                    JSONObject()
                        .put("k", KIND_REQUEST)
                        .put("r", event.round)
                        .put("try", event.attempt)
                        .put("model", event.model.take(120))
                        .put("provider", event.provider.take(120))
                        .put("effort", event.effort.take(24)),
                )
            }
            is AgentEvent.ProviderResponseStarted -> append(
                runId,
                JSONObject()
                    .put("k", KIND_RESPONSE)
                    .put("r", event.round)
                    .put("http", event.httpCode),
            )
            // 只有「用户能看到字」的块才算首字：思考块和正文块。
            //
            // 工具调用块不算 —— 模型直接调工具、一个字都不输出时，若把工具参数生成的
            // 起点当首字，TTFT 会偏小，而且这段耗时会被算进解码速度（`speedOf` 分子
            // 仍取该请求的 output token），速度跟着虚高。
            //
            // 真机实测：451 条 `first` 记录后面紧跟 usage 再紧跟 tool，即这些轮次
            // 根本没有可见输出，纯属污染分母。这类轮次**不该有** first 记录。
            is AgentEvent.AssistantBlockStart -> when (event.kind) {
                AgentEvent.AssistantBlockKind.THINKING,
                AgentEvent.AssistantBlockKind.TEXT,
                -> noteFirstToken(runId, event.round)

                AgentEvent.AssistantBlockKind.TOOL_CALL -> Unit
            }
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
            is AgentEvent.UsageReceived -> {
                val usage = event.usage
                val json = JSONObject()
                    .put("k", KIND_USAGE)
                    .put("r", event.round)
                    .put("ctx", usage.contextTokens ?: 0)
                    .put("in", usage.inputTokens ?: 0)
                    .put("out", usage.outputTokens ?: 0)
                    .put("cache", usage.cachedTokens ?: 0)
                    .put("reason", usage.reasoningTokens ?: 0)
                // 只有服务商真的给了总输入才写：缺省表示旧记录，读取时回退到 in + out。
                usage.promptTokens?.let { json.put("prompt", it) }
                append(runId, json)
            }
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
        synchronized(lock) { awaitingFirstToken.remove(runId) }
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

    /**
     * 某个会话最近一次「带会话标记」的运行追踪 id。
     *
     * 存在的理由：界面要用「这次对话跑了多快」。追踪文件按 runId 命名，
     * 而 meta 里以前没有会话字段 —— 上层只能拿消息 id 前缀去猜（`run-x-thinking-1-0`）。
     * 那是隐式约定：改一次消息 id 格式就静默失效，界面上速度段凭空消失且不报错。
     *
     * 这里按 meta.conv 精确匹配。旧追踪没有该字段（null），不会被误认成任何一个会话。
     */
    fun latestRunIdFor(conversationId: String, limit: Int = DEFAULT_LOOKUP_LIMIT): String? =
        lookup(conversationId, limit).runId

    /**
     * 同上，但带上「为什么没有」。
     *
     * 界面需要在「这个会话没跑过」和「追踪被回收了」之间说清楚 —— 两者的处理方式
     * 完全不同：前者无须解释，后者说明数据存在过但已被清理。只返回 null 时
     * 调用方分不出来，只能沉默地什么都不显示，用户看到的是「有时有、有时没有」。
     */
    /**
     * 按「新→旧」排。
     *
     * 只按 mtime 不够稳：文件系统时间戳往往是毫秒甚至秒级粒度，短时间内连续写出的
     * 多份追踪会拿到**相同**的 mtime，而 `sortedByDescending` 对并列元素的次序取决于
     * 目录遍历顺序——换个文件系统或换次运行就会变，表现为「最新一份」有时是上一份。
     * 真实后果：同一会话连跑多轮时，速度段可能挂到前一轮的追踪上。
     * 所以并列时用文件名末尾的序号做次级键，保证可重现。
     */
    private val newestFirst = compareByDescending<File> { it.lastModified() }
        .thenByDescending { trailingNumber(it.nameWithoutExtension) }

    /** 取文件名末尾的数字（`run-19` → 19）；没有数字时返回 Long.MIN_VALUE，不影响主序。 */
    private fun trailingNumber(name: String): Long =
        name.takeLastWhile { it.isDigit() }.toLongOrNull() ?: Long.MIN_VALUE

    fun lookup(conversationId: String, limit: Int = DEFAULT_LOOKUP_LIMIT): LookupResult {
        if (conversationId.isBlank()) return LookupResult(LookupStatus.NoConversation)
        val target = dirOrNull() ?: return LookupResult(LookupStatus.NoStore)
        val files = runCatching {
            target.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
                ?.sortedWith(newestFirst)
                ?: emptyList()
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return LookupResult(LookupStatus.NoStore)
        // 不要用 MAX_RUNS 当前缀扫描的上限。
        //
        // MAX_RUNS 是**写入侧**的保留配额（旧文件会被删掉），拿它当读取上限意味着
        // 「找得到」与「文件数」耦合在一起：一旦磁盘上有超过 80 份（多会话交替、
        // 或将来调大保留策略），更早的那份就永远找不到了，而且调用方传多大的 limit
        // 都会被 coerceIn 悄悄压回 80。
        //
        // 保留一个绝对值上限只是为了不让病态目录把 IO 拖死（每份 meta 约 0.4ms）。
        // 候选：属于本会话的追踪。
        val mine = mutableListOf<File>()
        for (file in files.take(limit.coerceAtLeast(1))) {
            // 只读第一行（meta）判断归属，不解析整份文件：
            // 单份追踪可达 1.8MB，为了找一个 id 而全量解析 80 份是不可接受的。
            val meta = firstJson(file) ?: continue
            if (meta.optString("conv") == conversationId) mine += file
        }
        if (mine.isEmpty()) {
            // 目录里有追踪，但没有一份属于这个会话：要么它没跑过，要么跑过但被回收了。
            // 这里分不出这两种，所以归为 Missing —— 文案只能说「没有可用的运行记录」，
            // 不能断言「被清理了」（那是没有根据的确定语气）。
            return LookupResult(LookupStatus.Missing)
        }
        // 优先取**已完成**的那一份。
        //
        // 不能直接用最新 mtime：mtime 在每次追加时都会更新，所以「最新」往往正是
        // **此刻还在跑的这个 run**。它可能还没写 `first`（这一轮还没吐出第一个字），
        // 于是速率先落到上一次运行上去 —— 界面上看到的数字属于上一轮，
        // 而用户以为那是这一轮的。
        //
        // 现状：还在跑的 run 里没有 `finish`，有 `finish` 的才是跑完的。
        // 都已完成时，最新那份仍是正确答案。
        val newestFirst = mine.take(MAX_LOOKUP_READ)
        newestFirst.firstOrNull { runFinished(it) }?.let { file ->
            // 已完成里挑最新：mine 已按 mtime 降序，第一个完成的就是最近完成的。
            return LookupResult(LookupStatus.Found, file.nameWithoutExtension)
        }
        // 一份都没完成（首次运行仍在进行）：退回最新的一份，让界面至少有个进度值。
        return LookupResult(LookupStatus.Found, mine.first().nameWithoutExtension)
        // 目录里有追踪，但没有一份属于这个会话：要么它没跑过，要么跑过但被回收了。
        // 这里分不出这两种，所以归为 Missing —— 文案只能说「没有可用的运行记录」，
        // 不能断言「被清理了」（那是没有根据的确定语气）。
        return LookupResult(LookupStatus.Missing)
    }

    /**
     * 这次运行是否已经结束。
     *
     * 读整份文件判断 `finish` —— 只在「最近这几份里挑一份」时用，
     * 而且调用方限制在 [MAX_LOOKUP_READ] 份内，代价可控。
     */
    private fun runFinished(file: File): Boolean = runCatching {
        file.useLines { lines -> lines.any { it.contains("\"k\":\"${KIND_DONE}\"") } }
    }.getOrDefault(false)

    /** [lookup] 的结果状态。 */
    enum class LookupStatus {
        /** 找到该会话最近一次运行。 */
        Found,

        /** 存储可用，但没有该会话的记录（没跑过，或已被回收）。 */
        Missing,

        /** 存储本身不可用（目录未初始化 / 无文件）。 */
        NoStore,

        /** 调用方没给出会话 id。 */
        NoConversation,
    }

    data class LookupResult(
        val status: LookupStatus,
        val runId: String? = null,
    )

    fun list(limit: Int = 50): List<Summary> {
        val target = dirOrNull() ?: return emptyList()
        val files = runCatching {
            target.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
                ?.sortedWith(newestFirst)
                ?: emptyList()
        }.getOrDefault(emptyList())
        return files.take(limit.coerceAtLeast(1)).mapNotNull { file ->
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

    /** 标记：下一次内容块开始就是这次请求的首字（每个请求只记一次）。 */
    private fun markAwaitingFirstToken(runId: String) {
        synchronized(lock) { awaitingFirstToken.add(runId) }
    }

    /**
     * 记下本次请求的第一个内容块到达时刻 —— 首字延迟（TTFT）的分界点。
     *
     * 内容块（思考 / 正文 / 工具调用）开始都会来一次；这里只认本次请求的第一次，
     * 因为速度的分母从首字起算（见 [speedOf]），再往后记只是白占追踪体积。
     */
    private fun noteFirstToken(runId: String, round: Int) {
        val first = synchronized(lock) { awaitingFirstToken.remove(runId) }
        if (!first) return
        append(runId, JSONObject().put("k", KIND_FIRST).put("r", round))
    }

    /**
     * 按容量回收旧追踪。
     *
     * [keepConversationId] 是**马上要写**的这次运行所属会话：它的历史记录优先保留。
     *
     * 不这么做的话，多轮交替使用时会出问题：A 会话用了一阵，切到 B 会话跑几十轮，
     * A 的追踪就被挤没了；回到 A 时速度段消失，而且**没有任何提示**。
     * 用户看到的是「这功能有时灵有时不灵」，这类沉默的退化最难排查。
     *
     * 只保该会话最近的一份：保留它是指标要用的（`latestRunIdFor` 取最近一次），
     * 没必要为它囤积全部历史，否则一个会话就能把配额吃光。
     */
    private fun prune(target: File, keepConversationId: String = "") {
        val files = target.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?.sortedByDescending { file -> file.lastModified() }
            ?: return
        // 每个会话各保最近一份。
        //
        // 只保「当前会话」是不够的：A 跑一次，切到 B 跑几十轮，A 的追踪照样被挤掉；
        // 等切回 A 时，它才成为「当前会话」，但那时记录已经没了 —— 保不回来。
        // 所以要在**任何会话被挤掉之前**就替它留一份，这才是「切回来还能看到」的前提。
        //
        // 空 conv（旧追踪、子代理）不参与：它们的会话归不下来，替它们留位会白占配额。
        val pinnedConv = mutableSetOf<String>()
        val pinned = mutableSetOf<File>()
        for (file in files) {
            val conv = runCatching { firstJson(file)?.optString("conv") }.getOrNull()
            if (conv.isNullOrBlank()) continue
            if (pinnedConv.add(conv)) pinned += file
        }
        // pin 的份数要设上限：会话数量没有上界，若是每个会话都永久留一份，
        // 配额会被无限多的「各一份」撑爆，等于保留策略失效。
        //
        // 上限取配额的一半：剩下一半留给「最近用过的」，让目录里始终有历史可查，
        // 也不至于因为会话多就把常用记录挤出去。
        val pinBudget = (retention.maxRuns / 2).coerceAtLeast(1)
        if (pinned.size > pinBudget) {
            // 超了就只保**最近活跃**的那几个会话（files 已按 mtime 降序）。
            val keepFiles = pinned.take(pinBudget).toSet()
            pinned.retainAll(keepFiles)
        }
        // 配额太小时，当前会话（马上要看的这份）优先。
        if (keepConversationId.isNotBlank()) {
            val current = files.firstOrNull { file ->
                runCatching { firstJson(file)?.optString("conv") }.getOrNull() == keepConversationId
            }
            if (current != null && current !in pinned) {
                pinned += current
                val overflow = pinned.size - pinBudget.coerceAtLeast(1)
                if (overflow > 0) {
                    // 挤掉最久未活跃的那份，保住当前会话与配额。
                    val victim = pinned.firstOrNull { it != current }
                    if (victim != null) pinned -= victim
                }
            }
        }
        // 先把 pinned 计入配额，后面按「最新优先」填满剩余额度。
        // 否则 pinned 排在后面时会先被前面的文件挤掉，等于没保。
        var total = pinned.sumOf { it.length() }
        // 调用方紧接着要写一份新文件：保留 maxRuns - 1 份旧的，总数才不会超。
        var remaining = (retention.maxRuns - 1).coerceAtLeast(0) - pinned.size
        if (remaining < 0) remaining = 0
        files.forEach { file ->
            if (file in pinned) return@forEach
            val fits = remaining > 0 && total + file.length() <= retention.maxTotalBytes
            if (fits) {
                total += file.length()
                remaining--
            } else {
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
                KIND_USAGE -> {
                    // 新追踪用服务商口径的总输入；旧追踪没有 prompt 键，回退到 in + out。
                    val prompt = if (json.has("prompt")) json.optInt("prompt", 0) else json.optInt("in", 0)
                    tokens += prompt + json.optInt("out", 0)
                }
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
            conversationId = meta.optString("conv").takeIf(String::isNotBlank),
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
            conversationId = firstJson(file)?.optString("conv")?.takeIf(String::isNotBlank),
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
        promptTokens = optIntOrNull("prompt"),
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

    /** 本次请求的第一个内容块（首字时刻）：速度的分母从这里开始，不含 TTFT。 */
    const val KIND_FIRST = "first"
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

    /**
     * `latestRunIdFor` 默认扫描的文件数上限。
     *
     * 与 [MAX_RUNS] 分开：那是保留策略（决定删什么），这是查找范围（决定能找多远）。
     * 两者绑在一起时，保留策略一收紧，历史会话的指标就会莫名其妙地消失。
     */
    private const val DEFAULT_LOOKUP_LIMIT = 512

    /**
     * 判断「是否已完成」时最多读几份追踪。
     *
     * 每份都要扫全文找 `finish`（1.8MB 左右），所以只在这个小窗口里找，
     * 不随目录规模增长。窗口内的都属于同一会话，正常情况下第一份就是。
     */
    private const val MAX_LOOKUP_READ = 8
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
    private const val MAX_RUN_BYTES = 3L * 1024 * 1024
    private const val MAX_RUN_ID_LENGTH = 80
    private const val MAX_ARGUMENT_CHARS = 4_000
    private const val MAX_RESULT_CHARS = 12_000
    private const val MAX_ERROR_CHARS = 2_000

    /**
     * 一次请求的「生成结束」时刻。
     *
     * 边界仍是工具 / 下一次请求 / 运行结束三者最早者，但工具记录写在**执行结束**时，
     * 所以取 `at - elapsedMs`（开始执行的那一刻）。直接用 `at` 会把整段工具墙钟算成
     * "模型生成耗时" —— 2026-11-02 实测：438 轮的一次运行里 3,046,548ms 的 47% 是工具。
     */
    internal fun modelEndMs(entries: List<Entry>, requestIndex: Int, endOfRun: Long): Long {
        val index = boundaryIndex(entries, requestIndex + 1)
        if (index >= entries.size) return endOfRun
        val boundary = entries[index]
        return if (boundary.kind == KIND_TOOL) {
            (boundary.at - (boundary.elapsedMs ?: 0L)).coerceAtLeast(entries[requestIndex].at)
        } else {
            boundary.at
        }
    }

    /** 从 [from] 起第一条切断本次请求的记录（工具 / 下一次请求 / 运行结束）；没有则返回 size。 */
    private fun boundaryIndex(entries: List<Entry>, from: Int): Int =
        (from until entries.size).firstOrNull { index ->
            val kind = entries[index].kind
            kind == KIND_TOOL || kind == KIND_REQUEST || kind == KIND_DONE
        } ?: entries.size

    /** 一组记录里「模型在生成」的耗时合计；总览与单轮共用同一套规则。 */
    internal fun modelMsOf(entries: List<Entry>, indices: Iterable<Int>, endOfRun: Long): Long {
        var sum = 0L
        indices.forEach { index ->
            if (entries[index].kind != KIND_REQUEST) return@forEach
            sum += (modelEndMs(entries, index, endOfRun) - entries[index].at).coerceAtLeast(0)
        }
        return sum
    }

    /**
     * 纯解码窗口：分子是 output token，分母是「首字 → 生成结束」，按请求逐个配对。
     *
     * 口径对齐 dsh（`ui-chat/src/client/contract/turn-metrics.ts` 的 `decodeMs`）：
     * 不含请求发出到首字之间的等待（TTFT），也不含工具耗时；只有**既有首字、又有用量**
     * 的请求才进分母 —— 失败重试不会把速度摊薄。
     * 旧追踪没有 `first` 记录，退回响应头时刻（`response`），仍比「请求发出」更接近解码。
     */
    internal fun speedOf(entries: List<Entry>, indices: Iterable<Int>): SpeedRead {
        var ms = 0L
        var tokens = 0
        indices.forEach { index ->
            if (entries[index].kind != KIND_REQUEST) return@forEach
            val segment = entries.subList(index + 1, boundaryIndex(entries, index + 1))
            val start = segment.firstOrNull { it.kind == KIND_FIRST }?.at
                ?: segment.firstOrNull { it.kind == KIND_RESPONSE }?.at
                ?: return@forEach
            val finish = segment.lastOrNull { it.kind in CONTENT_KINDS }?.at ?: return@forEach
            // 用量可能被供应商放在工具调用之后（同一请求内）：扫描到下一次请求为止，
            // 不跟着「生成结束」的边界截断 —— 否则这段 token 会被悄悄丢掉，速度就会虚高。
            val output = entries.subList(index + 1, nextRequestIndex(entries, index + 1))
                .filter { it.kind == KIND_USAGE }
                .sumOf { it.outputTokens ?: 0 }
            if (output <= 0 || finish <= start) return@forEach
            ms += finish - start
            tokens += output
        }
        return SpeedRead(ms, tokens)
    }

    /** 从 [from] 起下一次请求的位置；没有则返回 size。 */
    private fun nextRequestIndex(entries: List<Entry>, from: Int): Int =
        (from until entries.size).firstOrNull { entries[it].kind == KIND_REQUEST } ?: entries.size

    /** 生成过程中会出现的记录类型：取最后一条当作「生成结束」。 */
    private val CONTENT_KINDS = setOf(KIND_RESPONSE, KIND_FIRST, KIND_THINKING, KIND_TEXT, KIND_USAGE)
}

/** 纯解码速度的分子与分母；[tokensPerSecond] 在数据不足时为 null（界面显示「—」）。 */
internal data class SpeedRead(val decodeMs: Long, val tokens: Int) {
    val tokensPerSecond: Double?
        get() = if (tokens > 0 && decodeMs > 0) tokens * 1000.0 / decodeMs else null
}
