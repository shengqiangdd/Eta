package io.github.mangi.eta.agent.device

/**
 * 虚拟屏上「节点动作失败后能不能换条路再做一次」的判据。
 *
 * 背景：无障碍手势（dispatchGesture）只会作用在默认屏。虚拟屏上点一个本身不接受
 * ACTION_CLICK 的节点时，`clickNode` 只能返回 GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY。
 * 以前这个错误直接丢给模型，它得自己把 bounds 换算成坐标、再补一次 tap —— 多一轮调用，
 * 而且很容易漏掉 display_id（漏了就会点到用户主屏）。
 *
 * 现在改成由工具层直接走 Root 的 `input -d` 兜底。为了防止「兜底兜到主屏」，
 * 判据必须同时成立：错误码就是那条虚拟屏硬限制、目标确实是虚拟屏、Root 可用。
 */
internal object VirtualDisplayFallbackPolicy {
    /** 唯一允许兜底的错误码：无障碍手势被"虚拟屏不支持"挡住。 */
    const val GESTURE_UNSUPPORTED: String = "GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY"

    /**
     * 默认屏编号（`Display.DEFAULT_DISPLAY`）。
     *
     * 这里刻意写字面量而不引用 android.view.Display：本对象被纯 JVM 单测直接覆盖，
     * 不碰 android.jar 才能在 `:app:test` 里跑起来。
     */
    private const val DEFAULT_DISPLAY: Int = 0

    /**
     * 是否允许改用 Root 坐标动作重做一次。
     *
     * @param errorCode 节点动作返回的错误码
     * @param displayId 节点所在显示；null 表示默认屏
     * @param rootAvailable Root 是否可用（没有 Root 就没有 `input -d` 这条路）
     */
    fun mayFallbackToRootCoordinates(
        errorCode: String,
        displayId: Int?,
        rootAvailable: Boolean,
    ): Boolean {
        if (errorCode != GESTURE_UNSUPPORTED) return false
        if (!rootAvailable) return false
        return displayId != null && displayId != DEFAULT_DISPLAY
    }
}
