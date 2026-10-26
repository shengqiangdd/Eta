package io.github.mangi.eta.agent.device

/**
 * 「这次还能不能滚」的判据。
 *
 * 背景（fix156）：`scroll` 在无障碍那条路走不通时（虚拟屏、或拿不到活动窗口），
 * 会退回 Root 的 `input -d swipe`。它的目标区域取自「面积最大的可滚动节点」，
 * **取不到就退回整屏 10%~90% 做盲滑**。这在界面上没有滚动容器时等于把一次全屏滑动
 * 随机丢给页面：可能触发下拉刷新、翻页、甚至关掉当前弹层，而且事后无法证明滑到了哪里。
 *
 * 所以先判定，再动手：
 * - 有可滚动节点 → 正常滚；
 * - 节点数太少 → 界面还没画完，取消（`SCROLL_TREE_UNREADABLE`）；
 * - 节点够多但没有一个可滚动 → 取消（`NO_SCROLLABLE_TARGET`）。
 *
 * 两种取消都带 `retryable=false` 与「改用 swipe 指定起止点 / 先 observe_screen」的下一步，
 * 因为原样重试一定得到同样的结果 —— 只是白费一轮与一次全屏手势。
 *
 * 刻意不引用 android 类型：本对象被纯 JVM 单测直接覆盖。
 */
internal object ScrollTargetPolicy {
    /** 节点树里没有任何可滚动节点。 */
    const val NO_TARGET_CODE: String = "NO_SCROLLABLE_TARGET"

    /** 节点树太小，不足以判断有没有可滚动区域。 */
    const val TREE_UNREADABLE_CODE: String = "SCROLL_TREE_UNREADABLE"

    /**
     * 可信判定所需的最少节点数，与盲点判据共用同一个门槛。
     *
     * 真机教训（fix155）：应用刚启动时无障碍只回 2 个容器节点，
     * 「没看到可滚动节点」并不等于「页面不可滚动」，而是「我们还没看到」。
     */
    const val MIN_TRUSTED_NODES: Int = BlindTapSafetyPolicy.MIN_TRUSTED_NODES

    enum class Decision { PROCEED, TREE_UNREADABLE, NO_TARGET }

    fun decide(nodeCount: Int, scrollableCount: Int): Decision = when {
        scrollableCount > 0 -> Decision.PROCEED
        nodeCount < MIN_TRUSTED_NODES -> Decision.TREE_UNREADABLE
        else -> Decision.NO_TARGET
    }

    /**
     * 取消动作时给模型的错误码；`PROCEED` 不是错误，返回 null。
     *
     * 以前这里对 `PROCEED` 也返回 NO_TARGET_CODE。调用点虽然先判了「不是 PROCEED」才用它，
     * 但 API 本身在说谎：一次能正常滚动的调用，只要有人换个写法（比如先取值再判空），
     * 就会被写成「页面不可滚动」。改成可空，让它在类型上就取不到错误码。
     */
    fun errorCodeFor(decision: Decision): String? = when (decision) {
        Decision.PROCEED -> null
        Decision.NO_TARGET -> NO_TARGET_CODE
        Decision.TREE_UNREADABLE -> TREE_UNREADABLE_CODE
    }

    /**
     * 给模型看的说明。
     *
     * 必须同时回答三件事：为什么取消、界面上实际有什么、下一步该用哪个工具。
     * 只说「失败」会诱导模型用同样的参数再试一次（实测反复发生）。
     */
    fun messageFor(decision: Decision, nodeCount: Int, scrollableCount: Int): String = when (decision) {
        Decision.PROCEED -> "界面树里有 $scrollableCount 个可滚动节点"
        Decision.TREE_UNREADABLE ->
            "这次只读到 $nodeCount 个节点（界面可能还在加载或切换），无法确认哪里可以滚动：" +
                "已取消发送滑动，避免整屏盲滑误触。请先 observe_screen 确认界面，" +
                "再决定是否滚动；不要原样重试这次调用"
        Decision.NO_TARGET ->
            "界面树共 $nodeCount 个节点，但没有任何可滚动节点（scrollable=true）：" +
                "已取消整屏盲滑，避免触发翻页、下拉刷新这类页面手势。" +
                "要按方向滑动请改用 swipe 指定起止点，或先 observe_screen 找到目标列表；" +
                "不要原样重试这次调用"
    }
}
