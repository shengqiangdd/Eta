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
    private fun sameScreen(publishedDisplayId: Int?, requestedDisplayId: Int?): Boolean =
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
}
