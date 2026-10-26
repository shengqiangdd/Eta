package io.github.mangi.eta.agent.tool

/**
 * 「截图坐标系用不了」的错误码。
 *
 * 背景（fix162）：`coordinate_space=screenshot` 原来只按**最近一次观察**里记的
 * `CoordinateSpace`（屏宽高 + 截图宽高）换算，完全不看**这次点的是哪块屏**。
 * 于是「先看主屏截图、再去虚拟屏点坐标」这条路会用主屏的 1216x2640 当尺子：
 *
 * - 真机表现：`tap(coordinate_space=screenshot, display_id=116, 1000, 2000)`
 *   在工具层被放行，一直走到控制器才 `require(1000 in 0 until 720)` 失败，
 *   抛出的错误码是 `TOOL_ERROR`（「内部错误」），消息只说「坐标超出屏幕范围」——
 *   模型看不出真正的原因是**尺子拿错了**，只会去改坐标重试。
 * - 反方向（截图比目标屏小）不会报错，而是**静默点偏**：数值合法、落点在屏内，
 *   只是不是模型想点的地方 —— 这是最坏的一种「成功」。
 *
 * 所以两个原因分开报：没有截图 / 截图属于另一块屏。两者都不是「参数写错」，
 * 重试同一个调用不会变好（`retryable=false`）。
 */
internal object CoordinateSpaceErrors {
    /** 最近一次观察没有附图，或附图已裁剪放大（不再对应整屏截图坐标系）。 */
    const val SCREENSHOT_UNAVAILABLE: String = "SCREENSHOT_SPACE_UNAVAILABLE"

    /** 最近一次观察的截图属于另一块屏，不能用来换算这块屏的截图坐标。 */
    const val SCREENSHOT_OTHER_DISPLAY: String = "SCREENSHOT_SPACE_OTHER_DISPLAY"
}
