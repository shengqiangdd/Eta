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

    /**
     * 「没有可见窗口」时给模型的下一步建议，按屏的真实电源状态分流。
     *
     * 现场（2026-11-07）：一块摆了 20 多分钟的虚拟屏，`dumpsys display` 里
     * `mBaseDisplayInfo` 是 `state OFF`、`mOverrideDisplayInfo` 还是 `state ON` —— 半死状态。
     * 但工具只报「没有可见窗口（应用可能已迁回主屏）」，`virtual_display_launch` 还说
     * 「已在目标屏，直接观察即可」，三句话互相矛盾，我在同一块死屏上试了三次才想起来查 dumpsys。
     *
     * 顺带实测否掉了「调 flags 能救」的猜测：`eta_vd_extra_flags=8000` 建出的新屏
     * `canHostTasks` 仍是 false（与正常的屏一样），所以 canHostTasks 不是这个症状的判据，
     * 能判的只有 state。
     */
    fun noVisibleWindowAdvice(powerState: String?): String {
        val out = powerState?.trim()?.uppercase()
        val asleep = out != null && (out.startsWith("OFF") || out.startsWith("DOZE"))
        return if (asleep) {
            "这块屏已处于半死状态（display state=$out）：闲置后系统关掉了它，但 DisplayManager " +
                "没同步，窗口因此不可见，再 launch 也没用。**重建即可**：virtual_display_release 后 " +
                "重新 create（屏上的任务会丢，先想清楚要不要保）。"
        } else {
            "请先 virtual_display_launch 把应用带回这块屏，或用 observe_screen 确认它还在；" +
                "不要原样重试这次调用"
        }
    }
}
