package io.github.mangi.eta.agent.tool

/**
 * 「文本写哪儿」：虚拟屏注入通道只写**当前焦点输入框**，不支持按节点 index 定位。
 *
 * 背景（fix164）：`type_text` 的 schema 写着「指定 index 时写入该 editable 节点」，
 * 而虚拟屏分支在判断 index 之前就 `return` 了：
 *
 * ```kotlin
 * val displayIdArg = resolveDisplayId(args)
 * if (displayIdArg != null) {
 *     return deviceController.textOnDisplay(displayId = displayIdArg, ...)   // index 被丢掉
 * }
 * ```
 *
 * 真机取证（本机 PKX110，canary）：
 *
 * ```text
 * observe_screen()                       → 主屏节点（index=0 是 Eta 自己的 View）
 * type_text(text="fix164probe", index=0, observation_id=o…, display_id=131)
 *   → {"ok":true, ...}                  ← 文本写进了 131 的焦点输入框，index 从未被使用
 * STEP14 verdict=FAIL_INDEX_SILENTLY_IGNORED
 * ```
 *
 * 所以这种组合必须**明确拒绝**，而不是默默换一个目标写入。
 * 要把文本写进虚拟屏的某个输入框：先 `tap_element` 把它点成焦点，再不带 index 调用。
 *
 * 纯 JVM，可直接单测。
 */
internal object DisplayTextTargetPolicy {
    enum class Target {
        /** 写当前焦点输入框（主屏 IME 通道 / 虚拟屏注入通道都一样）。 */
        FOCUS_ONLY,

        /** index 与 display_id 同时出现：虚拟屏通道做不到，必须拒绝。 */
        REJECTED_INDEX,
    }

    fun resolve(hasIndex: Boolean, displayId: Int?): Target =
        if (hasIndex && displayId != null) Target.REJECTED_INDEX else Target.FOCUS_ONLY
}
