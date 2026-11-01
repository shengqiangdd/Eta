package io.github.mangi.eta.agent.terminal

import java.util.Locale

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

/**
 * 时钟回拨的容忍值。
 *
 * 系统时间小幅跳动（NTP 校正、手动校准几秒）不该触发重建，否则每次校准都可能
 * 把一批已经结束的任务重新当成事件。超过这个量级才认为「时间轴断了」：
 * 水位线比现在还晚，之后结束的任务全部落在水位线之前 —— 那是静默失效，
 * 不报错、也不自愈，直到真实时间重新追上旧水位线。
 */
internal const val ClockSkewToleranceMs = 60_000L

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

    /**
     * 系统时间被往回拨了：水位线比「现在」还晚出 [ClockSkewToleranceMs]。
     *
     * 处理办法是把时间轴重新立到 [at]（现在），并把 [absorb] 里那些
     * **`completed_at` 比现在还晚**的已完成任务记成「已处理」—— 它们是回拨前的历史，
     * 不吸收就会被当成「未来事件」反复选中（发两次、链满、水位线推到更远的未来，再次卡死）。
     *
     * 代价说清楚：回拨期间结束、还没被处理的任务，时间上已经无法与历史区分，
     * 会被当成历史（漏一次自动续跑）；手动「继续」照常可用。
     */
    data class Rebaseline(val at: Long, val absorb: List<String>) : AutomationTick

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
    // 时间轴断了：水位线在「现在」之后一个容忍值以上。此时按时间比较，之后结束的任务
    // 全都落在水位线之前，永远不会被选中 —— 必须重建，否则自动续跑会一直哑着。
    if (seenUntil > nowMs + ClockSkewToleranceMs) {
        val at = nowMs.coerceAtLeast(1L)
        return AutomationTick.Rebaseline(
            at = at,
            absorb = records
                .filter { it.sessionScope == scopeId && (it.completedAt ?: 0L) > at }
                .map { it.id },
        )
    }
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
 * 把水位线推过「从最早开始、连续都已经处理过」的那一段。
 *
 * 手动「继续」只记 id、不推水位线 —— 推了会跳过更早的未处理任务（静默丢任务比重复更糟）。
 * 代价是：id 被 `MAX_IDS` 挤掉之后，它就重新变成候选，再发一遍。这里补一个只做安全部分的
 * 压缩：遇到第一条没处理过的任务立刻停，因此不会越过任何尚未处理的事件。
 *
 * @return 新的水位线；不需要推进（或没有连续已处理段）时返回 null。
 */
internal fun advanceSeenUntilOverHandled(
    records: List<DetachedTask>,
    scopeId: String,
    handled: Set<String>,
    seenUntil: Long,
): Long? {
    if (scopeId.isBlank()) return null
    val pending = records
        .filter { it.sessionScope == scopeId && (it.completedAt ?: 0L) > seenUntil }
        .sortedBy { it.completedAt ?: 0L }
    var through: Long? = null
    // 按结束时刻分组：**同一毫秒的必须全部处理过**才能越过 —— Dispatch 用 `-1ms` 是为了
    // 让同毫秒的其余任务仍是候选；这里是批量宣告，越过就没机会了，所以整组一起看。
    for ((at, group) in pending.groupBy { it.completedAt ?: 0L }.toSortedMap()) {
        if (group.any { it.id !in handled }) break
        through = at
    }
    val at = through ?: return null
    return at.takeIf { it > seenUntil }
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

/**
 * 续跑的提示词：**手动「继续」与自动续跑必须用同一份**（第十九批）。
 *
 * 两个入口各拼一套的代价已经发生过：卡片那条把 `%2$s` 填成「job」\/「daemon」类型词，
 * 自动续跑填的是任务描述 —— 同一句话里同一个占位符的含义随入口变，用户在自己的
 * 聊天记录里看到的就是「后台任务 job_ab12（job）已结束」，模型拿到的也是这句。
 *
 * `%2$s` 的语义定在这里：**任务描述，没写描述就用命令本身**。
 * 用 [Locale.ROOT] 格式化：只换占位符，不引入本地化数字与分隔符。
 */
internal fun backgroundResumePrompt(template: String, task: DetachedTask): String =
    String.format(
        Locale.ROOT,
        template,
        task.id,
        task.description.ifBlank { task.command },
    )

/**
 * 一次判定的单行诊断文本（日志用）。
 *
 * 抽成纯函数是为了能单测：日志只知道「有一行」，测试要能断言行里的内容，
 * 否则「日志加了」和「日志里写了什么」就只能靠眼睛。作用域是会话 id，不是内容。
 */
internal fun AutomationTick.logLine(scopeId: String): String = when (this) {
    is AutomationTick.Idle -> "idle scope=$scopeId"
    is AutomationTick.Initialize -> "initialize scope=$scopeId at=$at"
    is AutomationTick.Dispatch -> "dispatch scope=$scopeId task=${task.id}"
    is AutomationTick.Discard -> "discard scope=$scopeId skipped=$skipped through=$through"
    is AutomationTick.Rebaseline -> "rebaseline scope=$scopeId absorbed=${absorb.size}"
}
