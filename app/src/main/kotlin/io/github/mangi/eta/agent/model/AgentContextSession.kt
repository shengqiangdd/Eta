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
    private var compacted = false
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
        inputTokens = tokens?.takeIf { it >= 0 }
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

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = inputTokens
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            if (force) {
                throw AgentContextCompactor.signedAnthropicToolRoundFailure()
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
        val shouldCompact = AgentContextBudget.shouldCompact(
            force = force,
            autoCompactionEnabled = config.autoCompactionEnabled,
            contextWindow = contextWindow,
            observedInputTokens = before,
            historyChars = historyChars,
            triggerRatio = TRIGGER_RATIO,
        )
        // 失败一次已经花掉一次（或多次）摘要请求；下一轮立刻重放同一份历史只会再失败一次。
        val coolingDown = shouldCompact && !force && !final && autoCompactionCooldown > 0
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
            ))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            val reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"
            val detail = ((failure as? AgentModelFailure)?.message?.takeIf { it.isNotBlank() }
                ?: "${failure::class.simpleName}: ${failure.message.orEmpty()}")
                .take(MAX_FAILURE_DETAIL)
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = reasonCode, detail = detail, charsBefore = historyChars.toInt()))
            // 手动压缩：用户主动点的，必须把失败告诉用户。
            if (force) throw failure
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

        /** 自动压缩失败后跳过的轮数：避开「每轮都重试同一份失败历史」。 */
        const val AUTO_COMPACTION_COOLDOWN_ROUNDS = 3

        /** 事件里携带的失败原因上限（只用于展示与追踪）。 */
        const val MAX_FAILURE_DETAIL = 160
    }
}
