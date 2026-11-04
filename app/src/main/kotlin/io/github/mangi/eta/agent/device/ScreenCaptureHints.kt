package io.github.mangi.eta.agent.device

/**
 * 截图失败码 → 建议怎么做。
 *
 * 为什么需要这一层：`observe_screen` 的 `screenshot` 段以前只有一个大写错误码。
 * 模型看到 `WINDOW_SCREENSHOT_UNSUPPORTED` 只能猜——而它猜错方向的代价很具体：
 * 要么反复重试同一个注定失败的调用，要么以为「这台设备不能截图」而放弃视觉核对，
 * 退回去盲点坐标。
 *
 * 这里只解释「为什么」和「接下来能做什么」，不改任何行为：
 * 截图能不能成功仍然由平台决定，本类不参与判定。
 */
internal object ScreenCaptureHints {

    /** 逐窗口截图是 Android 14（UPSIDE_DOWN_CAKE）才有的 API。 */
    private const val WINDOW_SCREENSHOT_UNSUPPORTED = "WINDOW_SCREENSHOT_UNSUPPORTED"
    private const val SCREENSHOT_UNAVAILABLE = "SCREENSHOT_UNAVAILABLE"
    private const val VIRTUAL_FRAME_NOT_READY = "VIRTUAL_FRAME_NOT_READY"

    fun forCode(code: String?): String? = when (code) {
        WINDOW_SCREENSHOT_UNSUPPORTED ->
            "这台设备（Android 14 以下）没有逐窗口截图能力；而当时有需要排除的 Eta 浮层，" +
                "用整屏截屏会把浮层本身截进去，所以宁可不给图。" +
                "可选：① 只用 UI 节点工作（本次结果的 ui_nodes 仍然有效，tap_element 可直接用，不需要截图）；" +
                "② 先收起浮层（停止当前会话的浮层显示）再重截；" +
                "③ 确实需要看图时由用户手动截屏后提供。"

        SCREENSHOT_UNAVAILABLE ->
            "这次没取到截图，但 UI 节点（若有）不受影响，可以先按节点操作；" +
                "需要图像时再观察一次；反复失败就改用节点或由用户手动截屏。"

        VIRTUAL_FRAME_NOT_READY ->
            "虚拟屏还没有可读帧。先在这块屏上触发一次界面变化（点击/滑动），再重新观察。"

        else -> null
    }
}
