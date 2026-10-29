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

    /**
     * 屏在系统里确实存在，但**不是本进程创建的**（典型：主应用建了一块屏，另一个进程里的 Agent
     * 用它传来的 display_id 去观察/点击）。
     *
     * 为什么值得单独一个码：原来这种情况一律报 RELEASED，模型会当成「屏没了」去重新建一块 ——
     * 重建之后目标应用不在新屏上，剧本就反复断在同一步（2026-10-29 真机实测）。
     * 文案必须直接给可执行路径：在本进程内 create + launch。
     */
    const val FOREIGN: String = "DISPLAY_FOREIGN"

    /** 屏还在，但上面没有可见窗口（应用可能已迁回主屏或退到后台）。 */
    const val NO_VISIBLE_WINDOW: String = "DISPLAY_NO_VISIBLE_WINDOW"
}
