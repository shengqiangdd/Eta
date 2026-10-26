package io.github.mangi.eta.agent.tool

/**
 * 动作后的观察该看哪块屏、能不能与动作前的快照比较（fix165）。
 *
 * 背景：`afterAction` 原先用「最近一次观察的屏」去观察，而不是「这次动作真正作用的屏」。
 * 两者只在「动作沿用快照屏」时才会一致，一旦显式传了不同的 `display_id` 就会错：
 *
 * 真机取证（本机 PKX110，主包，修复前）：
 *
 * ```text
 * observe_screen(148)                    → 快照属于虚拟屏 148
 * press_key(button=BACK, display_id=0)   → 动作在**主屏**
 * after.package=com.oplus.account, after.window_id=w2848   ← 那是虚拟屏 148 的窗口
 * ```
 *
 * 动作写着主屏、反馈却是虚拟屏，而 `after` 里原本连「这是哪块屏」都没有，
 * 模型完全无从察觉 —— 它据此判断「生效了没有」。
 *
 * 这里刻意不引用 android 类型：纯 JVM 单测直接覆盖（与 [ObservationScopePolicy] 同款做法）。
 */
internal object AfterActionScopePolicy {

    /**
     * @param observeDisplayId 动作结束后要观察哪块屏
     * @param compareWithPrevious 能否拿动作前的快照做差异比较
     * @param keepScreenshotSpace 旧快照的截图坐标系是否还能用
     * @param snapshotDisplayId 新快照应记录成哪块屏的
     */
    data class Plan(
        val observeDisplayId: Int?,
        val compareWithPrevious: Boolean,
        val keepScreenshotSpace: Boolean,
        val snapshotDisplayId: Int?,
    )

    /**
     * @param actionDisplayId 本次动作实际作用的屏（主屏为 null）
     * @param snapshotDisplayId 当前快照属于哪块屏（主屏为 null 或 0）
     */
    fun plan(actionDisplayId: Int?, snapshotDisplayId: Int?): Plan {
        // 0 与 null 都表示主屏：调用方可能显式写 display_id=0，也可能省略。
        // 不归一化就会把「主屏动作」当成跨屏，白丢一份可比较的快照。
        val action = actionDisplayId?.takeIf { it != 0 }
        val snapshot = snapshotDisplayId?.takeIf { it != 0 }
        val same = ObservationScopePolicy.check(snapshot, action) ==
            ObservationScopePolicy.Scope.MATCH
        return Plan(
            observeDisplayId = action,
            // 不是同一个界面就没有可比性：跨屏 diff 会把「换了一块屏」渲染成
            // 「屏幕上所有东西都变了」，比不给 diff 更容易误导。
            compareWithPrevious = same,
            // 截图坐标系属于旧快照那块屏的整屏截图，跨屏复用会换算到错位置。
            keepScreenshotSpace = same,
            snapshotDisplayId = if (same) snapshot else action,
        )
    }
}
