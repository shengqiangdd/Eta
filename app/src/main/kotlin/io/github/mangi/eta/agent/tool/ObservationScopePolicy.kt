package io.github.mangi.eta.agent.tool

/**
 * 「节点快照属于哪块屏」与「这次动作要操作哪块屏」必须一致。
 *
 * 背景（fix163）：`tap_element` / `long_press_element` 用上一次观察的节点快照执行动作，
 * 但目标屏由 `display_id` 决定，两者从来没有被比较过 ——
 * `requireElementObservation` 只校验 `observation_id` 是否当前快照。
 *
 * 真机取证（本机 PKX110，canary）：
 *
 * ```text
 * observe_screen(display_id=124)        → o62（com.android.settings 的设置首页）
 * tap_element(index=2, observation_id=o62, display_id=0)
 *   → {"ok":true,"executor":"accessibility","method":"ACTION_CLICK",
 *      "after":{"package":"com.android.settings", ...}}
 * ```
 *
 * 调用写着「点主屏」，动作和 after 都在虚拟屏上 —— 工具返回 ok，
 * 模型因此相信主屏已经被点过。反方向更严重：快照取自主屏、`display_id` 写虚拟屏时，
 * 动作会打在**用户正在用的主屏**上，而工具声称在操作虚拟屏。
 *
 * 这里刻意不引用 android 类型：纯 JVM 单测直接覆盖（与 [CoordinateDimensionPolicy] 同款做法）。
 */
internal object ObservationScopePolicy {
    enum class Scope {
        /** 快照就属于目标屏，index 有意义。 */
        MATCH,

        /** 快照属于另一块屏：同一 index 在目标屏上完全是别的控件，必须先重新观察。 */
        OTHER_DISPLAY,
    }

    /**
     * @param observedDisplayId 快照来自哪块屏（主屏为 null 或 0）
     * @param requestedDisplayId 本次动作的 display_id（主屏为 null）
     */
    fun check(observedDisplayId: Int?, requestedDisplayId: Int?): Scope =
        if (CoordinateDimensionPolicy.sameScreen(observedDisplayId, requestedDisplayId)) {
            Scope.MATCH
        } else {
            Scope.OTHER_DISPLAY
        }
}
