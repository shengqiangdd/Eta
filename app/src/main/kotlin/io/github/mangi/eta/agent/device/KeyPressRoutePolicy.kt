package io.github.mangi.eta.agent.device

/**
 * `press_key` 该走哪条注入通道（fix166）。
 *
 * 背景：`display_id=0` 会被归一成 `null`（=主屏），而 `pressKey()` 在 `null` 时走
 * 无障碍 `globalAction` —— 它作用于**焦点窗口**，焦点在哪块屏就打到哪块屏。
 * 于是「显式写主屏」的 BACK 可能退掉虚拟屏上的界面，而返回值只说
 * `executor=accessibility`，调用方无从知晓落点。
 *
 * 真机取证（本机 PKX110，主包，fix165 取证时发现）：
 *
 * ```text
 * observe_screen(148)                    → 快照属于虚拟屏 148
 * press_key(button=BACK, display_id=0)   → {"executor":"accessibility"} ← 落点不受控
 * ```
 *
 * 这里刻意不引用 android 类型：纯 JVM 单测直接覆盖。
 */
internal object KeyPressRoutePolicy {

    enum class Route {
        /** 无障碍全局动作 / imeEnter：落点是「当前焦点」，不保证是哪块屏。 */
        ACCESSIBILITY_GLOBAL,

        /** `input -d <display> keyevent`：明确作用于虚拟屏。 */
        ROOT_ON_DISPLAY,

        /** `input -d 0 keyevent`：明确作用于主屏（显式要求主屏时才走）。 */
        ROOT_DEFAULT_DISPLAY,
    }

    /**
     * @param explicitDisplayId 调用方**显式写下的** display_id（0 表示明确主屏）；未写为 null
     * @param routedDisplayId `resolveDisplayId` 归一后的目标屏（虚拟屏 id，主屏为 null）
     * @param accessibilityAvailable 无障碍服务当前可用
     * @param rootAvailable Root 是否可用
     */
    fun decide(
        explicitDisplayId: Int?,
        routedDisplayId: Int?,
        accessibilityAvailable: Boolean,
        rootAvailable: Boolean,
    ): Route = when {
        // 虚拟屏一定要落到具体 display：globalAction 只跟焦点走，可能打到主屏。
        routedDisplayId != null -> Route.ROOT_ON_DISPLAY
        // 显式写主屏 = 明确要求「就打主屏」：有 Root 就按 display 0 注入，不交给焦点漂移。
        explicitDisplayId != null -> if (rootAvailable) {
            Route.ROOT_DEFAULT_DISPLAY
        } else {
            // 没 Root 只能用无障碍：退化为「焦点在哪就打哪」，由调用方在响应里标 landed=unknown。
            Route.ACCESSIBILITY_GLOBAL
        }
        // 没写 display_id：沿用旧行为（无障碍优先，更快也不需要 Root）。
        accessibilityAvailable -> Route.ACCESSIBILITY_GLOBAL
        rootAvailable -> Route.ROOT_DEFAULT_DISPLAY
        else -> Route.ACCESSIBILITY_GLOBAL
    }
}
