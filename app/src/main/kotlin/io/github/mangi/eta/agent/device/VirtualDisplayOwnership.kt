package io.github.mangi.eta.agent.device

/**
 * 虚拟屏归属判定（纯逻辑，可单测）。
 *
 * 背景：虚拟屏是系统资源，会跨运行存活；而工具的 controller 每次运行都是**新实例**。
 * 只按 controller 自有的 map 判断「这块屏是不是我的」，新运行就会把旧屏当成「不存在」，
 * 于是释放变成静默假成功、列表又看不到 —— 用户看到的现象是「另一个对话的屏，我动不了」。
 *
 * 归属规则（身份越具体越优先）：
 * 1. 屏带会话 id：当前也在同一会话 → 自己的；在别的会话、或当前没有会话（语音 / 一次性入口）→ 别人的。
 * 2. 屏只有运行 id：同一次运行 → 自己的；否则别人的。
 * 3. 两者都没有（更早版本建的屏）：无法判定，一律 UNKNOWN —— 不冒充自己的，也不冒充别人的。
 */
internal object VirtualDisplayOwnership {
    enum class Match {
        /** 本对话（或本次运行）建的屏，可以自由操作、释放。 */
        SELF,

        /** 另一个对话 / 另一次运行建的屏，默认不列、不释放。 */
        OTHER,

        /** 建屏时没有带任何归属信息（旧版本遗留），只能按「未知」处理。 */
        UNKNOWN,
    }

    fun match(
        ownerConversationId: String,
        ownerRunId: String,
        currentConversationId: String,
        currentRunId: String,
    ): Match {
        if (ownerConversationId.isNotEmpty()) {
            return if (currentConversationId.isNotEmpty() && ownerConversationId == currentConversationId) {
                Match.SELF
            } else {
                Match.OTHER
            }
        }
        if (ownerRunId.isNotEmpty()) {
            return if (currentRunId.isNotEmpty() && ownerRunId == currentRunId) Match.SELF else Match.OTHER
        }
        return Match.UNKNOWN
    }

    /** 工具结果里给模型看的归属标签。 */
    fun label(match: Match): String = when (match) {
        Match.SELF -> "self"
        Match.OTHER -> "other"
        Match.UNKNOWN -> "unknown"
    }
}
