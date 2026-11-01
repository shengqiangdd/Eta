package io.github.mangi.eta.agent.terminal

/**
 * 「后台任务结束后要不要接着跑」的判定。
 *
 * 这一层只做纯逻辑：给一批记录、已经续过的 id、卡片打开的时刻与已用次数，回答
 * 「现在该不该自动发一条、发哪一条」。真正发消息、拼文案（要本地化）都在界面层。
 *
 * 为什么需要这么一条判定：自动续跑会在用户没看着的时候花掉额度，而「模型派任务 → 任务结束 →
 * 自动续跑 → 模型又派任务」是一个能自我维持的循环。所以除了开关默认关，还要有：
 * 次数上限、打开卡片前的旧任务不自动续、同一条任务只续一次。
 */

/** 一次会话里最多自动续跑几次。超了只提示，不再自动发。 */
internal const val AutoResumeMaxPerSession = 2

/** 自动续跑的判定结果。 */
internal data class AutoResumePlan(
    /** 本轮该自动发出的那一条；null 表示不发起。 */
    val next: DetachedTask?,
    /** 有候选、但因为次数上限没排上的条数（界面可以据此提示「还有 N 条没自动续」）。 */
    val skipped: Int,
)

/**
 * 挑出本轮可以自动续跑的那一条。
 *
 * @param records 当前记录（与提示段、卡片同源）
 * @param scopeId 本会话的作用域键（`conversationId ?: runId`）。**只认归属本会话的任务**：
 *   记录里的任务属于别的会话（或旧记录没有归属）时，不自动续 —— 否则当前显示着的会话
 *   会替用户没看着的会话花额度。空串表示无会话，此时永不会命中。
 * @param handled 已经续过的任务 id（手动点「继续」也算，避免刚点完又自动发一条）
 * @param finishedAfterMs 只认这个时刻**之后**结束的任务。传「卡片打开的时刻」：
 *   否则一进一个旧会话，历史里所有已结束任务都会被当成新事件续一遍。
 * @param alreadyResumed 本会话已经自动续过几次
 * @param maxCount 上限（默认 [AutoResumeMaxPerSession]）
 */
internal fun planAutoResume(
    records: List<DetachedTask>,
    scopeId: String,
    handled: Set<String>,
    finishedAfterMs: Long,
    alreadyResumed: Int,
    maxCount: Int = AutoResumeMaxPerSession,
): AutoResumePlan {
    val candidates = records
        .filter { task ->
            val done = task.completedAt
            scopeId.isNotBlank() && task.sessionScope == scopeId &&
                done != null && done > finishedAfterMs && task.id !in handled
        }
        .sortedBy { it.completedAt }
    if (candidates.isEmpty()) return AutoResumePlan(next = null, skipped = 0)
    val budget = (maxCount - alreadyResumed).coerceAtLeast(0)
    if (budget == 0) return AutoResumePlan(next = null, skipped = candidates.size)
    // 一次只发一条：第二条要等这一轮跑完、界面重新空闲，否则等于把两条提问挤在一起。
    return AutoResumePlan(next = candidates.first(), skipped = candidates.size - 1)
}

/**
 * 卡片里那条手动「继续」该不该出现。
 *
 * 与 [planAutoResume] 共用同一条归属规则：**任务必须属于本会话**。抽成函数是因为
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
