package io.github.mangi.eta.agent.tool

/**
 * 「节点快照属于另一块屏」的错误码。
 *
 * 背景（fix163）：`tap_element` / `long_press_element` 用的节点快照来自**上一次观察**，
 * 而动作目标屏由 `display_id` 决定。两者可以不一致：观察过虚拟屏，再写 `display_id=0`
 * （主屏）去点一个 index。
 *
 * 旧实现只比较 `observation_id`，不比较屏，于是动作按**快照自己的窗口**执行：
 *
 * - 快照在虚拟屏、`display_id=0`：动作打在虚拟屏上，工具返回 ok ——
 *   模型以为点了主屏，实际什么都没发生（「说点了主屏」的假成功）；
 * - 快照在主屏、`display_id=虚拟屏`：动作打在**用户的主屏**上 ——
 *   用户会看到自己的屏幕被点，而工具声称它在操作虚拟屏。这条比上一条严重得多。
 *
 * 所以节点动作一旦跨屏就拒绝，并说清两块屏各是谁。
 */
internal object ObservationScopeErrors {
    /** 这次快照不是目标屏的：节点 index 只在它的原屏上有意义。 */
    const val OTHER_DISPLAY: String = "OBSERVATION_OTHER_DISPLAY"
}
