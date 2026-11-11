package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.trace.AgentRunTraceStore

import io.github.mangi.eta.ui.model.AgentChatMessageUi

/**
 * 会话级用量汇总。
 *
 * 为什么单独一个投影而不是在 Composable 里累加：这里的口径有几处容易算错，
 * 而算错时**界面看起来完全正常**（数字都在，只是错的）。典型三处：
 *
 * 1. **缓存命中率的分母**。必须用服务商返回的**总输入**（[TokenUsageUi.promptTokens]），
 *    但它可能缺失（旧消息）。缺失时要回退到 `inputTokens` —— 直接把原样 input 当总输入，
 *    在 Anthropic 下会漏掉全部缓存读写，命中率被系统性高估。
 * 2. **缓存命中率的分子**。只能取 `cachedTokens`，不能拿 `inputTokens - cachedTokens` 反推：
 *    OpenAI 兼容端点的 `inputTokens` 本身已含缓存，反推会在命中时算出负数。
 * 3. **会话累计是求和，不是取最后一次**。逐轮累加才有意义；取最新一条会把多轮回退成单轮。
 *
 * 输出 token **已含**推理 token（见 [TokenUsageUi.outputTokens] 的跨 provider 口径），
 * 所以推理是子集、不能再相加：相加会让总量与速度虚高（真机实测最坏 1.88x）。
 */
internal data class AgentSessionUsageUi(
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
    val cachedTokens: Int = 0,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val requestCount: Int = 0,
    /**
     * 最近一轮的首字延迟与解码耗时（毫秒）。
     *
     * 只取最近一轮而不求平均：速度受当前网络与服务商状态影响最大，
     * 历史平均反而会掩盖「现在很慢」这个最有用的信号。null = 未采集到。
     */
    val lastFirstTokenMs: Long? = null,
    val lastDecodeMs: Long? = null,
    /** 最近一轮的输出 token（含推理），用于算生成速度。 */
    val lastOutputTokens: Int? = null,
    /**
     * 速度/首字这一段的来源状态。
     *
     * 存在的理由：速度段不显示时，用户分不清是「这个会话没跑过」还是
     * 「记录被回收了」。两种情况的含义完全不同，却都表现为「什么都没有」。
     * 把状态显式带上来，界面才能给出准确的说明而不是让那一块凭空消失。
     */
    val speedSource: SpeedSource = SpeedSource.None,
    /**
     * 是否只统计了会话的一部分（更早的消息还没加载进内存）。
     *
     * 界面据此标注范围。默认为 false 而不是「未知」：多数会话本来就没被截断，
     * 每张卡片都挂一句「可能不全」会变成噪音，只在真正截断时才说。
     */
    val partialScope: Boolean = false,
) {
    /** 缓存命中率；分母为 0 或数据不足时为 null（界面显示「—」，不要显示 0%）。 */
    val cacheHitRate: Float?
        get() = if (promptTokens > 0 && cachedTokens >= 0) {
            (cachedTokens.toFloat() / promptTokens).coerceIn(0f, 1f)
        } else {
            null
        }

    /** 未走缓存的输入。命中率的分母是总输入，这里只表示「这部分没命中」。 */
    val uncachedInputTokens: Int?
        get() = if (promptTokens > 0) (promptTokens - cachedTokens).coerceAtLeast(0) else null

    /**
     * 输出总量；完全没有输出时为 null 而不是 0。
     *
     * **不含 reasoningTokens 的额外加总**：供应商把推理放在 output 的明细里
     * （`output_tokens_details.reasoning_tokens`、`thinking_output_tokens`），
     * 它是 output 的子集，不是并列项。真机 200 条样本全部满足 reasoning ≤ output。
     *
     * 早先这里写成 `outputTokens + reasoningTokens`，会把总量抬高 ——
     * 实测某个会话累计虚高 11.6%（350,914 vs 314,398），推理密集的轮次更多。
     * 卡片上那行「其中推理」显示的是 [reasoningTokens]，语义已经点明是子集。
     */
    val totalOutputTokens: Int?
        get() = if (outputTokens == 0 && reasoningTokens == 0) null else outputTokens

    val isEmpty: Boolean
        get() = contextTokens == null && promptTokens == 0 && outputTokens == 0 && reasoningTokens == 0

    companion object {
        /**
         * 聚合 [messages] 里所有 assistant 消息的用量。
         *
         * @param contextWindow 当前模型的上下文窗口，用于算占用比例；未知传 null。
         */
        fun of(
            messages: List<AgentChatMessageUi>,
            contextWindow: Int? = null,
            /**
             * 从运行追踪读到的速度/首字（调用方异步读好再传进来，这里不做 IO）。
             *
             * 为什么不在消息里存这两项：追踪在每个事件到达时打点，真机实测
             * 21 次请求 / 21 次 `first`，覆盖完整、时间点也更准；消息表那条路
             * 要靠渲染投影去猜起点，实测在多轮 + 工具调用的形态下全是 0。
             */
            runTiming: RunTiming? = null,
            /** 追踪查找的状态（来自 AgentRunTraceStore.lookup）。 */
            lookupStatus: TraceLookupStatus = TraceLookupStatus.NoConversation,
            /**
             * [messages] 在数据库里的起始下标；> 0 表示更早的消息没加载进内存。
             *
             * 卡片只能统计手上这些消息。不说明的话，大会话里那串「输入 N tok」
             * 会被当成会话全量 —— 实测 49036 条的会话里它只占约 1%。
             */
            messagesWindowStart: Int = 0,
        ): AgentSessionUsageUi {
            var cached = 0
            var prompt = 0
            var output = 0
            var reasoning = 0
            var requests = 0
            // 上下文占用取「最近一条有值的」，它表示当前窗口里的实际占用，
            // 不能求和 —— 求和会把历史每轮的占用加在一起，远超窗口大小。
            var latestContext: Int? = null
            var lastFirstTokenMs: Long? = null
            var lastDecodeMs: Long? = null
            var lastRoundOutput: Int? = null
            // 最近一次运行的 id：消息 id 形如 `run-<uuid>-thinking-<round>-<index>`，
            // 前缀就是追踪文件名。用它去找追踪，避免把 runId 再存一份到每一行。
            var lastRunId: String? = null

            for (message in messages) {
                // 首字/解码只在 ThinkingMessageUi 上，与 usage 无关，
                // 所以在 usage 判定**之前**先读，否则会漏掉没用量的轮次。
                if (message is ThinkingMessageUi) {
                    message.firstTokenMs?.let { lastFirstTokenMs = it }
                    message.decodeMs?.let { lastDecodeMs = it }
                    runIdOf(message.id)?.let { lastRunId = it }
                }
                if (message !is AgentMessageUi) continue
                val usage = message.usage ?: continue
                if (usage.isEmpty) continue
                requests += 1
                usage.cachedTokens?.let { cached += it }
                // 没给总输入时回退到 inputTokens：旧消息只有这个字段。
                usage.promptTokens?.let { prompt += it } ?: usage.inputTokens?.let { prompt += it }
                usage.outputTokens?.let { output += it }
                // 生成速度的分子：本轮输出 token。
                //
                // **不要把 reasoningTokens 再加上去**：供应商的 output_tokens 已经包含
                // 推理 token（真机 200 条样本全部满足 reasoning ≤ output）。
                // 相加会让推理密集的轮次速度虚高，实测最坏 1.88x、平均 1.04x。
                // 追踪侧的 speedOf 用的就是 outputTokens，两边口径必须一致。
                lastRoundOutput = usage.outputTokens?.takeIf { it > 0 }
                usage.reasoningTokens?.let { reasoning += it }
                usage.contextTokens?.let { latestContext = it }
            }

            // 追踪数据优先。它由事件流直接打点，不依赖渲染投影是否正确配对；
            // 消息上的 firstTokenMs/decodeMs 只作为没有追踪时的回退（旧数据）。
            var speedSource = SpeedSource.None
            if (runTiming != null) {
                runTiming.firstTokenMs?.let { lastFirstTokenMs = it }
                // 只在三项齐全时才覆盖，否则保留消息上的值（旧数据回退）。
                if (runTiming.speedTokensPerSecond != null &&
                    runTiming.decodeMs != null &&
                    runTiming.speedNumeratorTokens != null
                ) {
                    lastDecodeMs = runTiming.decodeMs
                    lastRoundOutput = runTiming.speedNumeratorTokens?.toInt()
                }
                if (lastFirstTokenMs != null || lastDecodeMs != null) speedSource = SpeedSource.Trace
            }
            // 追踪里没有，但消息表上有旧采集：说明是早先跑过的会话。
            if (speedSource == SpeedSource.None && (lastFirstTokenMs != null || lastDecodeMs != null)) {
                speedSource = SpeedSource.LegacyMessage
            }
            // 既没有数据、存储里又有别的会话的记录 → 大概率是本会话的追踪被回收了。
            //
            // 但**必须要求这个会话真的跑过**（有消息）。空会话落到 Recycled 会让界面
            // 给一个从未运行过的对话显示「记录已被清理」——那是凭空捏造的信息。
            // 这里靠「有消息」判断，而不是靠 requestCount：后者对旧数据可能为 0。
            if (speedSource == SpeedSource.None &&
                lookupStatus == TraceLookupStatus.Missing &&
                messages.isNotEmpty()
            ) {
                speedSource = SpeedSource.Recycled
            }

            return AgentSessionUsageUi(
                contextTokens = latestContext,
                contextWindow = contextWindow,
                cachedTokens = cached,
                promptTokens = prompt,
                outputTokens = output,
                reasoningTokens = reasoning,
                requestCount = requests,
                lastFirstTokenMs = lastFirstTokenMs,
                lastDecodeMs = lastDecodeMs,
                lastOutputTokens = lastRoundOutput,
                speedSource = speedSource,
                partialScope = messagesWindowStart > 0,
            )
        }
    }
}

/**
 * 从消息 id 还原 runId：`run-<uuid>-thinking-<round>-<index>` → `run-<uuid>`。
 *
 * 只认 `-thinking-` 这一段：其它消息类型的 id 结构不同，宁可返回 null
 * 也不要切出错的前缀 —— 错的 runId 会读到别的运行的追踪，比没有更糟。
 */
internal fun runIdOf(messageId: String): String? {
    val cut = messageId.indexOf("-thinking-")
    return if (cut > 0) messageId.substring(0, cut) else null
}

/**
 * 追踪查找的结果，供 UI 层使用（不直接依赖 trace 模块的枚举）。
 *
 * 与 `AgentRunTraceStore.LookupStatus` 一一对应；单独定义是为了让 model 层
 * 不必依赖存储实现，也便于将来换数据源。
 */
internal enum class TraceLookupStatus {
    /** 找到该会话最近一次运行。 */
    Found,

    /** 存储可用但没有该会话的记录。 */
    Missing,

    /** 存储不可用。 */
    NoStore,

    /** 没有会话 id。 */
    NoConversation,
}

/** 速度段的来源状态。 */
internal enum class SpeedSource {
    /** 没有可显示的数据，且原因不值得解释（会话为空、还没跑过）。 */
    None,

    /** 数据来自运行追踪，且是这次会话最近一次运行。 */
    Trace,

    /** 有数据但来自消息表上的旧采集（追踪里没有）。 */
    LegacyMessage,

    /** 存储里有追踪记录，但没有属于这个会话的 —— 大概率是跑过但被回收了。 */
    Recycled,
}

/**
 * 把追踪查询结果装配成界面要的 [TraceProbe]。
 *
 * 从 composable 里抽出来是为了可测：这段逻辑决定「有没有速度、要不要解释」，
 * 埋在 `produceState` 的 lambda 里就只能靠斜眼读代码来保证正确性。
 */
internal fun probeOf(result: AgentRunTraceStore.LookupResult?, trace: AgentRunTraceStore.Trace?): TraceProbe {
    val status = when (result?.status) {
        AgentRunTraceStore.LookupStatus.Found -> TraceLookupStatus.Found
        AgentRunTraceStore.LookupStatus.Missing -> TraceLookupStatus.Missing
        AgentRunTraceStore.LookupStatus.NoStore -> TraceLookupStatus.NoStore
        else -> TraceLookupStatus.NoConversation
    }
    val timing = trace?.let {
        RunTiming(
            firstTokenMs = it.lastFirstTokenMs,
            decodeMs = it.speed?.decodeMs,
            speedNumeratorTokens = it.speed?.tokens?.toLong(),
            speedTokensPerSecond = it.outputTokensPerSecond,
        )
    }
    return TraceProbe(status = status, timing = timing)
}

/**
 * 一次追踪查询的完整结果：查到了什么 + 为什么没查到。
 *
 * 两个字段必须一起传递。只传 `timing` 时，null 同时表示「没跑过」「被回收了」
 * 「还没加载完」三种情况，界面只能沉默；带上 status 才能给出准确说明。
 */
internal data class TraceProbe(
    val status: TraceLookupStatus = TraceLookupStatus.NoConversation,
    val timing: RunTiming? = null,
)

/** 一次运行里与「多快」有关的部分，由调用方从追踪里取。 */
data class RunTiming(
    /** 首字延迟（毫秒）：请求发出 → 第一个内容块。缺失为 null。 */
    val firstTokenMs: Long? = null,
    /**
     * 纯解码耗时（毫秒）：首字 → 本轮结束。缺失为 null。
     *
     * 名字曾经错写成 decodeTokens，于是调用方把「token 数」当毫秒用，
     * 算出来的速度差两个数量级且不报错。这里按语义改名。
     */
    val decodeMs: Long? = null,
    /** 解码期间的输出 token 数（含推理）。 */
    val speedNumeratorTokens: Long? = null,
    /** 该次运行的解码速度；仅用于判断「有没有值」，界面自己用上面两个数重算。 */
    val speedTokensPerSecond: Double? = null,
)
