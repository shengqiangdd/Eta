package io.github.mangi.eta.agent.tool

/**
 * 「虚拟屏 + 节点 index」这类**参数被静默忽略**的错误码。
 *
 * 背景（fix164）：`type_text` / `clear_text` 的 schema 写着「指定 index 时写入该 editable 节点，
 * 不必先点击」，但虚拟屏分支直接走进程内注入通道（`input -d`），**从不看 index**：
 *
 * ```kotlin
 * val displayIdArg = resolveDisplayId(args)
 * if (displayIdArg != null) {
 *     return deviceController.textOnDisplay(displayId = displayIdArg, ...)   // index 被丢掉
 * }
 * ```
 *
 * 于是 `type_text(text="x", index=29, display_id=116)` 会写进 116 当前**焦点**输入框 ——
 * 多半是另一处（甚至可能是上一屏留下的焦点），而返回还是 `verified`。
 * 这是典型的「参数被忽略 + 结果说成功」：模型以为写到了 29 号框。
 *
 * 虚拟屏上要写指定输入框，正确做法是先 `tap_element` 把它点成焦点，再不带 index 写入。
 */
internal object DisplayTextErrors {
    /** 虚拟屏写入不支持按节点 index 定位。 */
    const val INDEX_ON_VIRTUAL_DISPLAY: String = "INDEX_NOT_SUPPORTED_ON_VIRTUAL_DISPLAY"
}
