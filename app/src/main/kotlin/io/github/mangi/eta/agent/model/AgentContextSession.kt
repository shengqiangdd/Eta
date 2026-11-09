package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private var config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private var provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    /** 摘要分批进度出口；默认静默，只在真实 App 运行里接 AndroidAgentLogger。 */
    private val onBatch: (String) -> Unit = {},
    /** 摘要请求的 token 用量出口；交给 [AgentContextCompactor]。 */
    private val onUsage: (AgentTokenUsage) -> Unit = {},
    /**
     * 压缩判定依据出口；默认静默，真实运行里接 AndroidAgentLogger。
     *
     * 只在**自动判定**这一条路径上输出（手动压缩有明确的 skipped/started 事件），
     * 目的是让「为什么没压缩」在事后可读，而不必靠反推。
     */
    private val onDecision: (String) -> Unit = {},
) {
    private val contextWindow: Int?
        get() = config.knownContextWindow

    /**
     * 运行中热切换模型后同步压缩口径。
     *
     * 压缩请求也会占用上下文窗口并按新模型计费：跨 provider 切换时若仍用启动时的
     * 编解码器，摘要请求会打到旧协议 / 旧端点上（表现为「换了模型但压缩一直报错」）。
     */
    fun updateModel(next: AgentModelClient.ModelConfig) {
        if (config == next) return
        config = next
        provider = ProviderClientFactory.getClient(next)
    }
    private var inputTokens: Int? = null
    /**
     * 本会话已跑通的峰值输入 token。
     *
     * 用于 [AgentContextBudget.absoluteLimitFor] 的峰值锚定：阈值只抬到「已验证过 × 1.5」，
     * 不会因为用户把 `context_window_override` 填得比端点实际容量大而一次性跳到溢出。
     * 压缩不回退该值，所以阈值单调不降。
     */
    private var peakInputTokens: Int? = null
    private var compacted = false
    /**
     * 上一次上报过的压缩判定原因。
     *
     * 用于去重：只在上报结论变化时才发一条日志，避免每轮都写一次。
     * 初始为 null（而不是 NONE）：第一轮无论结论如何都要上报一次，供事后对齐基准。
     */
    private var lastDecisionCause: AgentContextBudget.CompactionCause? = null
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    /** 自动压缩失败后的冷却轮数；手动压缩不受影响。 */
    private var autoCompactionCooldown = 0

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        // 0 不当作有效实测值：部分兼容端点在流式首块会回 `usage: {prompt_tokens: 0}` 占位。
        // 若当成真值，判定会以为上下文是空的，于是一直不压缩——实测同一份 40 万字符历史，
        // usage=0 得到 tokens=0 且 compact=no，而 usage=null 时能按估算正确压缩。
        inputTokens = tokens?.takeIf { it > 0 }
        inputTokens?.let { current ->
            if ((peakInputTokens ?: 0) < current) peakInputTokens = current
        }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    /**
     * 端点报 `CONTEXT_OVERFLOW` 之后钉住的上限（token）。
     *
     * 失败的那次请求不回 usage，所以「被拒的尺寸」本身没有测量值；能确证的只有
     * 「最近一次成功的入参量可以通过」。用它当新上限：保守，但绝不会再把上下文
     * 放大回刚被拒的量级。
     */
    private var overflowCeilingTokens: Int? = null
    /** 溢出救援只给一次：第二次仍溢出说明上限估错（端点真实容量比声明的窗口小），再试是死循环。 */
    private var overflowRecoveries = 0
    /** 下一轮压缩无条件执行。和用户手动压缩分开记，因为两者的失败处理不同。 */
    private var forceAfterOverflow = false

    /**
     * 溢出后能否自救。四个条件少一个都别试：
     *
     * 1. 只救一次 —— 第二次仍溢出等于上限估错，继续试就是死循环；
     * 2. 签名工具待续 —— 那种状态下强制压缩会抛，救不成只会换个错误码；
     * 3. 托管工具已开始 —— 那个失败标记了 `replayUnsafe`，不在这里判也在 Loop 里判；
     * 4. **历史本身得占大头** —— 历史很小时仍然溢出，说明是固定开销（系统提示、
     *    工具定义、单条超长消息）超过了端点容量，压历史救不回来。这时按既有契约
     *    （docs/AGENT_RUNTIME.md：「溢出直接报告失败，不自动压缩后重放」）直接失败，
     *    不白花一次摘要请求。阈值取生效上限的一半：低于它说明可压缩的部分
     *    连一次压缩能省下的量都凑不出来。
     */
    fun canRecoverFromOverflow(): Boolean {
        if (overflowRecoveries > 0) return false
        if (SignedToolContext.hasPendingResponse(messages)) return false
        val limit = AgentContextBudget.absoluteLimitFor(contextWindow, peakInputTokens)
        val historyTokens = AgentContextBudget.estimateTokens(
            AgentContextBudget.contextChars(messages, systemCount),
        )
        return historyTokens * 2 >= limit
    }

    /** 记录一次端点容量溢出：钉住上限，并要求下一轮无条件压缩。 */
    fun noteContextOverflow() {
        overflowRecoveries++
        forceAfterOverflow = true
        overflowCeilingTokens = maxOf(
            AgentContextBudget.ABSOLUTE_COMPACT_TOKENS,
            (peakInputTokens ?: inputTokens) ?: 0,
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = inputTokens
        // 溢出救援是一次性标志，取用即清；`force` 参数（用户手动压缩）语义不变。
        val overflowRecovery = forceAfterOverflow
        forceAfterOverflow = false
        val forced = force || overflowRecovery
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (forced) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        // 判据集中在 AgentContextBudget：远端 usage 可能整场缺失（端点忽略 include_usage），
        // 那时只有本地字符数能兜底，否则历史会一路涨到序列化时 OOM。
        val historyChars = AgentContextBudget.contextChars(messages, systemCount)
        // 手动压缩的下限：上下文只有一两千字符时，摘要不可能更短，却要付一次完整请求。
        // canary 实测（2026-10-25）：1,796 字符 → out 18,946 token（17,262 是 reasoning）、约 2.5 分钟，
        // 最后以 CONTEXT_NO_REDUCTION 失败。用户点「压缩上下文」本意是省，不该这样花。
        if (force && !final) {
            val skip = AgentContextBudget.manualCompactionSkipNote(historyChars)
            if (skip != null) {
                onEvent(
                    AgentEvent.ContextCompaction(
                        java.util.UUID.randomUUID().toString(),
                        AgentEvent.ContextCompaction.PHASE_SKIPPED,
                        before,
                        detail = skip,
                        charsBefore = historyChars.toInt(),
                    ),
                )
                return
            }
        }
        // 先做一步几乎免费的回收：把末尾之外的旧工具结果换成占位。
        //
        // 真机 trace：7 轮对话，每轮 in≈41 万 token、out≈200 token，缓存命中 99.8%。
        // 不是缓存问题，是上下文本身太大（1031 条消息，大多是历史工具输出）。
        //
        // **只在上下文已经很大时才做**：瘦身会改写历史，而 OpenAI 兼容端点靠逐字节
        // 前缀匹配命中缓存 —— 小会话（几轮就结束的）本来就不会超窗口，
        // 每轮都改写反而把缓存全部打碎，得不偿失。
        if (!final && historyChars >= PRUNE_MIN_CONTEXT_CHARS) {
            // 不在这里打日志：本类刻意保持无 Android 依赖（纯 JVM 可测），
            // 摘要进度已经通过 onEvent / 追踪文件可见，回收条数不必再加一条出口。
            AgentToolResultPruner.prune(
                messages = messages,
                keepTail = PRUNE_KEEP_TAIL,
                keepChars = PRUNE_KEEP_CHARS,
            )
        }
        val decision = AgentContextBudget.compactionDecision(
            force = forced,
            autoCompactionEnabled = config.autoCompactionEnabled,
            contextWindow = contextWindow,
            observedInputTokens = before,
            historyChars = historyChars,
            peakObservedTokens = peakInputTokens,
            // 溢出过就把上限夹到「被证明确实能通过」的量级，覆盖窗口比例与峰值放大。
            absoluteLimit = overflowCeilingTokens?.let { ceiling ->
                minOf(AgentContextBudget.absoluteLimitFor(contextWindow, peakInputTokens), ceiling)
            },
        )
        val shouldCompact = decision.compact
        // 只在结论或原因**发生变化**时上报：每轮都打会把 boot.log 变成第二个日志洪水源，
        // 而降级（能压 → 不能压）与升级（不能压 → 能压）才是需要事后追溯的事件。
        // 第一轮必报：那一次决定了本会话的初始基准。
        if (!forced && !final && decision.cause != lastDecisionCause) {
            lastDecisionCause = decision.cause
            onDecision(decision.describe())
        }
        // 失败一次已经花掉一次（或多次）摘要请求；下一轮立刻重放同一份历史只会再失败一次。
        val coolingDown = shouldCompact && !forced && !final && autoCompactionCooldown > 0
        if (coolingDown) autoCompactionCooldown--
        if (!shouldCompact || coolingDown) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED", charsBefore = historyChars.toInt()))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        // 计时起点取「已决定压缩」：用户点完压缩到气泡变成结果的时间，正是他感知到的等待，
        // 而之前这段耗时完全没记录 —— 只能从 logcat 时间戳倒推。
        val startedAt = System.currentTimeMillis()
        onEvent(AgentEvent.ContextCompaction(
            operation,
            AgentEvent.ContextCompaction.PHASE_STARTED,
            before,
            charsBefore = historyChars.toInt(),
        ))
        try {
            val compaction = AgentContextCompactor(
                config, provider, runController, roleplay = roleplay, onBatch = onBatch,
                onUsage = onUsage,
            ).compact(
                messages, systemCount, sensitiveIds(),
            )
            val candidate = compaction.messages
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            autoCompactionCooldown = 0
            val afterChars = AgentContextBudget.contextChars(messages, systemCount)
            // 成功时也带上「摘要多大、是否分批、上下文缩到多少」：没有这组数字，只能事后挖库才能看出
            // 摘要正在一层层涨向 12,000 字符上限（本轮失败的直接原因）。
            onEvent(AgentEvent.ContextCompaction(
                operation,
                AgentEvent.ContextCompaction.PHASE_COMPLETED,
                before,
                detail = compactionDetail(compaction, historyChars, afterChars),
                charsBefore = historyChars.toInt(),
                charsAfter = afterChars.toInt(),
                durationMs = System.currentTimeMillis() - startedAt,
            ))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            val reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"
            val detail = ((failure as? AgentModelFailure)?.message?.takeIf { it.isNotBlank() }
                ?: "${failure::class.simpleName}: ${failure.message.orEmpty()}")
                .take(MAX_FAILURE_DETAIL)
            onEvent(AgentEvent.ContextCompaction(
                operation, "failed", before,
                reasonCode = reasonCode,
                detail = detail,
                charsBefore = historyChars.toInt(),
                // 失败也要给耗时：慢到最后才报错，比一开始就报错更值得让用户看见。
                durationMs = System.currentTimeMillis() - startedAt,
            ))
            // 手动压缩与溢出救援：都是「非压不可」，失败必须让上层看见。
            // 溢出救援尤其不能静默重放 —— 请求已经证明超容量，再来一次只是再花一次费用。
            if (forced) throw failure
            // 自动压缩失败**不再中断整轮回答**：真机上这会把一个 86% 用量、本来还能跑完的
            // 回合直接变成「模型未返回完整且有界的摘要」。先保留原始上下文继续，冷却几轮再试。
            if (!final) {
                autoCompactionCooldown = AUTO_COMPACTION_COOLDOWN_ROUNDS
                return
            }
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    /** 成功事件里给人看的明细：摘要多大、分了几批、上下文缩到多少字符。 */
    private fun compactionDetail(result: CompactionResult, beforeChars: Long, afterChars: Long): String =
        buildString {
            append("摘要 ${result.summaryChars} 字符")
            if (result.batches > 1) append("（${result.batches} 批合并）")
            if (result.omittedChars > 0) append("，更早约 ${result.omittedChars} 字符未纳入")
            if (result.retainedTailChars > 0) append("，保留近期原文 ${result.retainedTailChars} 字符")
            append("；上下文 $beforeChars → $afterChars 字符")
        }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        const val TRIGGER_RATIO = AgentContextBudget.TRIGGER_RATIO

        /**
         * 上下文超过这么多字符（约 10 万 token）才开始回收旧工具结果。
         *
         * 阈值不能太小：回收本身会改写历史，而改写会让前缀缓存失效。
         * 只有那些真的会把整段上下文反复推给模型的会话（几十轮、上千条消息）
         * 才值得这个代价；短会话既不会超窗口，也不会有重复计费问题。
         */
        const val PRUNE_MIN_CONTEXT_CHARS = 400_000L

        /**
         * 末尾保留多少条消息不回收。
         *
         * 20 条大约覆盖最近 10 轮工具往返 —— 模型正在推进的任务都在这个范围里。
         * 回收它们会让模型「刚看到的结果下一轮就没了」，只能重跑一次，反而更贵。
         */
        const val PRUNE_KEEP_TAIL = 20

        /** 单个工具结果超过这么多字符才换成占位。 */
        const val PRUNE_KEEP_CHARS = 240

        /** 自动压缩失败后跳过的轮数：避开「每轮都重试同一份失败历史」。 */
        const val AUTO_COMPACTION_COOLDOWN_ROUNDS = 3

        /** 事件里携带的失败原因上限（只用于展示与追踪）。 */
        const val MAX_FAILURE_DETAIL = 160
    }
}
