package io.github.mangi.eta.agent.device

/**
 * 「这块屏现在没法执行动作」的两个错误码。
 *
 * 背景（fix156）：`resolveDisplayId` 会在两种情况下拦下动作，避免 `input -d` 落到主屏上
 * 把用户的屏幕点了 —— 虚拟屏已经释放，或那块屏上没有可见窗口（应用迁回主屏 / 退到后台）。
 *
 * 问题是这两种情况原来都抛 `InvalidToolArgumentException`，工具层统一 catch 成
 * `INVALID_ARGUMENT`。对模型来说那意味着「参数写错了」：它会去改参数重来，
 * 或者以为 `scroll` 需要别的前置条件 —— 真机日志里就是这么反复绕的
 * （`STEP3b` 直接 INVALID_ARGUMENT，实际原因是设置被一条无条件 BACK 退到了后台）。
 *
 * 现在改成各自带编码，模型一眼能分清「参数错」与「屏不对」，也就知道该 observe/launch
 * 而不是原样重试。
 */
internal object DisplayScopeErrors {
    /** 虚拟屏已被释放或从来不存在。 */
    const val RELEASED: String = "DISPLAY_RELEASED"

    /** 屏还在，但上面没有可见窗口（应用可能已迁回主屏或退到后台）。 */
    const val NO_VISIBLE_WINDOW: String = "DISPLAY_NO_VISIBLE_WINDOW"
}
