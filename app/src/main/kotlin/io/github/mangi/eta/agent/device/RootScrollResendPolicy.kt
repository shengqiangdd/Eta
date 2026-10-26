package io.github.mangi.eta.agent.device

/**
 * 坐标滑动「没动」时能不能补发一次的判据。
 *
 * 背景：`input -d swipe` 发出后，紧跟在页面切换动画里的那次滑动常常**不生效**
 * （手势打在了正在过渡的窗口上），前后两棵树里同类锚点位置完全一致。
 * 这时如实报 `ACTION_OUTCOME_UNKNOWN` 会逼模型再补一次调用；而实际上工具层自己补发
 * 一次更省事，也**不会造成重复滚动**。
 *
 * 「不会重复滚动」是这个判据存在的唯一理由：只有**看得到锚点、且一个都没动**才允许补发。
 * 若一个锚点都没匹配上（说明页面已经换了，我们根本不知道自己滚没滚），就不允许补发 ——
 * 那样补发就可能变成滚两屏。
 */
internal object RootScrollResendPolicy {
    /** 最多发几次手势（第一次 + 补发一次）。 */
    const val MAX_ATTEMPTS: Int = 2

    /** 至少要比对到这么多锚点，才敢断言「确实没动」。 */
    const val MIN_ANCHORS: Int = 2

    /**
     * @param moved 已经确认移动了吗
     * @param anchors 前后两棵树里成功配对上的锚点数量
     * @param movingAnchors 其中位置真的变了（超过最小位移阈值）的锚点数量
     * @param attempts 已经发过几次手势
     */
    fun shouldResend(
        moved: Boolean,
        anchors: Int,
        movingAnchors: Int,
        attempts: Int,
    ): Boolean {
        if (moved) return false
        if (attempts >= MAX_ATTEMPTS) return false
        return anchors >= MIN_ANCHORS && movingAnchors == 0
    }
}
