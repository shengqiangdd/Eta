package io.github.mangi.eta.agent.tool

/**
 * 「没传 display_id 时，这次动作作用在哪块屏」。
 *
 * 规则一直是「沿用最近一次观察所在的屏」，但主屏的 displayId 就是 0，
 * 而 0 在这套代码里同时表示「显式指定主屏」。两者混在一起时（fix176）：
 *
 * ```text
 * observe_screen()                     → 主屏，published.displayId = 0
 * type_text(text="中文种子")            ← 没传 display_id，也没传 index
 *   → 被当成「指定了屏 0」→ 走虚拟屏通路 textOnDisplay(0)
 *   → 那条通路用 input 注入 + 自己的验证口径，文本已经落地却报
 *     ok=false / ACTION_OUTCOME_UNKNOWN
 * ```
 *
 * 报告里最刺眼的一行是「text=中文种子后缀，但 write=false」：
 * 文本真的写进去了，工具却说自己没写成 —— 模型会重试，内容变成两遍。
 *
 * 真机取证（本机 PKX110 / Android 16，canary）：
 *
 * ```text
 * 修前 TEXT3c write=false both=24 seedOnly=24 text=中文种子后缀
 * 修后 TEXT3c write=true verify=verified/verified scope=default/0 both=18
 * ```
 *
 * 纯 JVM，可直接单测（见 [ImplicitDisplayPolicyTest]）。
 */
internal object ImplicitDisplayPolicy {
    /**
     * @param publishedDisplayId 最近一次 observe_screen 所在的屏（主屏是 0，没观察过是 null）
     * @return 本次动作要作用的屏；null 表示主屏
     */
    fun inherit(publishedDisplayId: Int?): Int? = publishedDisplayId?.takeIf { it != 0 }
}
