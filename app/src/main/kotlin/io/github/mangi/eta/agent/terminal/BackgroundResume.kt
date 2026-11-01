package io.github.mangi.eta.agent.terminal

/**
 * 「后台任务结束后要不要接着跑」的判定。
 *
 * 这一层只做纯逻辑：给一批记录、已经续过的 id、事件水位线与已用链长，回答
 * 「现在该初始化水位线、该发哪一条、还是该明确放弃」。真正发消息、拼文案（要本地化）在别处。
 *
 * 为什么需要这么一条判定：自动续跑会在用户没看着的时候花掉额度，而「模型派任务 → 任务结束 →
 * 自动续跑 → 模型又派任务」是一个能自我维持的循环。所以除了开关默认关，还要有：
 * - **链长上限**：一条链（从用户最后一次说话算起）最多自动续几次；
 * - **事件水位线**：只处理水位线之后的结束事件，历史任务不会被翻出来重放；
 * - **同一条任务只续一次**：手动点「继续」也算。
 */

/**
 * 一条自动续跑链最多几次。超了只提示，不再自动发。
 *
 * 名字从 `AutoResumeMaxPerSession` 改过来（第十二批）：它其实不是「每个会话一辈子两次」，
 * 用户主动发一条消息就会清零并开始新的一条链。
 */
internal const val AutoResumeMaxPerChain = 2

/** 一次扫描里，单个会话作用域该做什么。 */
internal sealed interface AutomationTick {
    /**
     * 第一次见到这个作用域：只把水位线立在 [at]，**不补发**此前的历史任务。
     *
     * 没有这一步，升级后第一次启动会把作用域里所有已完成任务当成新事件续一遍。
     */
    data class Initialize(val at: Long) : AutomationTick

    /** 该发这一条。 */
    data class Dispatch(val task: DetachedTask) : AutomationTick

    /**
     * 有候选但链已经满了：明确放弃到 [through]，不再每 30 秒重算同一批。
     *
     * [skipped] 是这次放弃的条数，界面可以据此说明「还有 N 条没自动续」。
     */
    data class Discard(val through: Long, val skipped: Int) : AutomationTick

    /** 没有该做的事。 */
    data object Idle : AutomationTick
}

/**
 * 决定本轮这个作用域的动作。
 *
 * @param records 当前记录（与提示段、卡片同源）
 * @param scopeId 本会话的作用域键（`conversationId ?: runId`）。**只认归属本会话的任务**：
 *   记录里的任务属于别的会话（或旧记录没有归属）时不动 —— 否则当前会话会替用户没看着的
 *   会话花额度。空串表示无会话，此时永远返回 [AutomationTick.Idle]。
 * @param handled 已经续过的任务 id（手动点「继续」也算）
 * @param seenUntil 事件水位线：只处理**严格晚于**它的结束事件；null 表示还没立过水位线
 * @param chain 这条链已经自动续了几次
 * @param maxChain 上限（默认 [AutoResumeMaxPerChain]）
 * @param nowMs 首次初始化水位线用的「现在」
 */
internal fun planAutomationTick(
    records: List<DetachedTask>,
    scopeId: String,
    handled: Set<String>,
    seenUntil: Long?,
    chain: Int,
    nowMs: Long,
    maxChain: Int = AutoResumeMaxPerChain,
): AutomationTick {
    if (scopeId.isBlank()) return AutomationTick.Idle
    if (seenUntil == null) return AutomationTick.Initialize(nowMs.coerceAtLeast(1L))
    val candidates = records
        .filter { task ->
            val done = task.completedAt
            task.sessionScope == scopeId && done != null && done > seenUntil && task.id !in handled
        }
        .sortedBy { it.completedAt ?: 0L }
    if (candidates.isEmpty()) return AutomationTick.Idle
    if (chain >= maxChain) {
        return AutomationTick.Discard(
            through = candidates.mapNotNull { it.completedAt }.maxOrNull() ?: seenUntil,
            skipped = candidates.size,
        )
    }
    // 一次只发一条：第二条要等这一轮跑完、界面重新空闲，否则等于把两条提问挤在一起。
    return AutomationTick.Dispatch(candidates.first())
}

/**
 * 卡片里那条手动「继续」该不该出现。
 *
 * 与 [planAutomationTick] 共用同一条归属规则：**任务必须属于本会话**。抽成函数是因为
 * 两处判定分居两个文件时很容易只改一处 —— 2026-11-01 真机就是这样：自动路径加了
 * `sessionScope` 过滤，卡片只判「没续过」，于是别的会话（以及第九批之前没有归属的
 * 旧记录）一旦排进「最近结束」，当前会话里就长出一个「继续」，点一下等于用本会话的
 * 额度替别人干活。
 *
 * @param scopeId 本会话作用域键；空串表示无会话，此时永不提供手动续跑
 * @param handled 本作用域已经续过的任务 id（手动 + 自动同集合）
 */
internal fun canOfferManualResume(
    task: DetachedTask,
    scopeId: String,
    buttonEnabled: Boolean,
    canSend: Boolean,
    handled: Collection<String>,
): Boolean = buttonEnabled && canSend &&
    scopeId.isNotBlank() && task.sessionScope == scopeId &&
    task.id !in handled
