package io.github.mangi.eta.agent.browser

/**
 * 浏览器会话的保留策略（纯逻辑，不碰 Android）。
 *
 * 为什么单独抽出来：真正执行回收要销毁 WebView，只能发生在主线程，也只能在
 * Robolectric / 真机上测——而本机（Linux aarch64）跑不了 Robolectric。
 * 把「哪些会话可以回收、该淘汰谁」这部分判定提出来，就能在纯 JVM 单测里固定住，
 * 不用等真机才敢确认逻辑没写反。
 *
 * 背景：`BrowserSessionRegistry` 的类注释写着「运行引用与挂载容器共同决定何时
 * 可以回收」，但两条判定原先都没实现——`requestRemove` 在生产代码里从未被调用，
 * 挂载引用根本不存在，于是 entries 只增不减，每个会话背后可能挂着一个 WebView。
 */
internal object BrowserSessionRetention {

    /** 判定所需的会话快照，避免把可变状态传进来。 */
    data class Candidate(
        val key: String,
        val runs: Int,
        val mounted: Int,
        val lastUsedAt: Long,
    )

    /**
     * 这个会话能不能就地销毁。
     *
     * 三个条件缺一不可：
     * - 有人明确要求移除（会话被删、或一次性运行结束）
     * - 没有正在跑的运行（否则会把工具执行到一半的页面抽掉）
     * - 没有界面挂着（否则用户眼前的页面会变成空白）
     */
    fun canDispose(candidate: Candidate, removalRequested: Boolean): Boolean =
        removalRequested && candidate.runs == 0 && candidate.mounted == 0

    /**
     * 空闲会话超限时该淘汰谁，按「最久未用优先」返回要移除的键。
     *
     * 只考虑 runs == 0 且 mounted == 0 的会话；正在跑的、正在被看的永远不动。
     * 如果可淘汰的数量不够，返回的列表会比需要的短——宁可暂时超限，也不抽走
     * 用户正在用的页面。
     *
     * @param candidates 当前全部会话快照
     * @param maxIdle 允许保留的空闲会话数
     * @return 要移除的键，按移除顺序排列
     */
    fun selectEvictions(candidates: List<Candidate>, maxIdle: Int): List<String> {
        if (maxIdle < 0) return emptyList()
        // 超出的数量 = 总数 - 上限。注意分母是「全部会话」而不是「空闲会话」：
        // 正在跑的会话也占位置，不能因为它们占位就把空闲额度算得更多。
        var excess = candidates.size - maxIdle
        if (excess <= 0) return emptyList()
        val idle = candidates
            .filter { it.runs == 0 && it.mounted == 0 }
            .sortedWith(compareBy({ it.lastUsedAt }, { it.key }))
        val evicted = ArrayList<String>(minOf(excess, idle.size))
        for (candidate in idle) {
            if (excess <= 0) break
            evicted.add(candidate.key)
            excess -= 1
        }
        return evicted
    }

    /**
     * 一次性会话的键（`run:<runId>`）跑完就该回收。
     *
     * 这类键出现在没有 handoff 的运行里（见 AgentConversationIdentity.fromHandoff）。
     * 浏览器面板只会用会话 id 或 MANUAL_SESSION_KEY 挂载，永远碰不到 `run:` 开头的键，
     * 所以留着没有任何用处——而它们会随运行次数无限累积。
     */
    fun isRunScoped(key: String): Boolean = key.startsWith("run:")
}
