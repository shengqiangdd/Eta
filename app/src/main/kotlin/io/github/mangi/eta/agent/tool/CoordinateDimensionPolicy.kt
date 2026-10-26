package io.github.mangi.eta.agent.tool

/**
 * 「这次点哪块屏」与「拿哪个尺寸换算坐标」必须是同一块屏。
 *
 * 背景（fix161）：`convertPoint` 原来只读「最近一次观察」的尺寸，完全不看本次操作的
 * `display_id`。于是常见顺序（先在主屏观察过，再去虚拟屏点坐标）会用主屏尺寸
 * （真机 1216x2640）去校验虚拟屏坐标（720x1280）：
 *
 * - 越界坐标被放行，一直走到控制器才被拦住，错误码从 `INVALID_ARGUMENT` 变成 `TOOL_ERROR`，
 *   模型看到「内部错误」会原样重试；
 * - 反过来（虚拟屏比主屏大）更糟：虚拟屏里合法的坐标会被判成越界，模型于是去改一个本来就对的参数。
 *
 * 所以尺寸来源要按优先级取，并且「最近一次观察」只有在**同一个 display** 上才算数。
 * 这里刻意不引用 android 类型：纯 JVM 单测直接覆盖。
 */
internal object CoordinateDimensionPolicy {
    /** 主屏在任务栈/观察里的 displayId 既可能是 null 也可能是 0，两者视为同一块屏。 */
    internal fun sameScreen(publishedDisplayId: Int?, requestedDisplayId: Int?): Boolean =
        if (requestedDisplayId == null) {
            publishedDisplayId == null || publishedDisplayId == 0
        } else {
            publishedDisplayId == requestedDisplayId
        }

    private fun usable(size: Pair<Int, Int>?): Pair<Int, Int>? =
        size?.takeIf { it.first > 0 && it.second > 0 }

    /**
     * @param requested 目标屏自己的尺寸（虚拟屏取会话记录）；取不到就 null
     * @param published 最近一次观察的尺寸
     * @param publishedDisplayId 那次观察属于哪块屏
     * @param requestedDisplayId 本次操作的 display_id（主屏为 null）
     * @return 用来换算/校验的尺寸；null 表示「两块都拿不到」，由调用方回退主屏尺寸
     */
    fun pick(
        requested: Pair<Int, Int>?,
        published: Pair<Int, Int>?,
        publishedDisplayId: Int?,
        requestedDisplayId: Int?,
    ): Pair<Int, Int>? {
        usable(requested)?.let { return it }
        if (!sameScreen(publishedDisplayId, requestedDisplayId)) return null
        return usable(published)
    }

    /** 「最近一次观察的截图」能不能用来换算**这块屏**的 screenshot 坐标。 */
    enum class ScreenshotSpaceUse {
        /** 最近一次观察没有附图，或附图被裁剪放大过（不再对应整屏截图）。 */
        NO_SCREENSHOT,

        /** 附图属于另一块屏；尺寸、比例、裁剪全都可能不同，换算结果没有意义。 */
        OTHER_DISPLAY,

        /** 附图就是这块屏的整屏截图，可以换算。 */
        USABLE,
    }

    /**
     * 截图坐标系也要跟「这次点哪块屏」对齐（fix162）。
     *
     * 与 [pick] 不同的是这里**没有回退**：尺寸可以退到主屏，截图不行 ——
     * 另一块屏的截图连「一像素等于多少屏幕像素」的对应关系都不成立，
     * 换算出来的点要么越界（在控制器层被报成 TOOL_ERROR），要么落在屏内的错位置
     * （静默点偏，最坏的一种「成功」）。
     */
    fun screenshotUse(
        hasSpace: Boolean,
        publishedDisplayId: Int?,
        requestedDisplayId: Int?,
    ): ScreenshotSpaceUse = when {
        !hasSpace -> ScreenshotSpaceUse.NO_SCREENSHOT
        !sameScreen(publishedDisplayId, requestedDisplayId) -> ScreenshotSpaceUse.OTHER_DISPLAY
        else -> ScreenshotSpaceUse.USABLE
    }
}
