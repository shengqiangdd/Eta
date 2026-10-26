package io.github.mangi.eta.agent.device

/**
 * 盲点坐标（`input -d tap X Y`）前的安全判据。
 *
 * 事故背景（fix155 真机）：虚拟屏上点一个本身不接受 ACTION_CLICK 的容器节点时，
 * 工具层会用「节点中心坐标」兜底。探针点了设置首页的根 ScrollView（center 360,640），
 * 那个点正好落在快捷开关行上 —— 把飞行模式打开了，设备当场断网、任务中断。
 *
 * 结论：**容器的中心点不是「这个容器的中心」，而是「容器内部随机某个控件」**。
 * 语义未知的盲点，在设置、支付、拨号这类界面上等于随机拨开关。所以判据改成：
 * 只要中心点落在另一个节点的范围内，就不盲点，而是把那个节点报回去，
 * 让模型明确点它（或其中一个带文本的子节点）—— 一步就能修正，不必再试错。
 *
 * 刻意不引用任何 android 类型：本对象被纯 JVM 单测直接覆盖。
 */
internal object BlindTapSafetyPolicy {
    /** 目标节点的中心点落在别处时返回该错误码，而不是把动作发出去。 */
    const val UNSAFE_CODE: String = "COORDINATE_FALLBACK_UNSAFE_TARGET"

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val isEmpty: Boolean get() = right <= left || bottom <= top

        /** 半开区间：right / bottom 是排他边界，与 `Rect` 的惯例一致。 */
        fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

        val area: Long get() = (right - left).toLong() * (bottom - top)
    }

    data class Candidate(
        val index: Int,
        val box: Box,
        /** 可点 / 可勾选 / 可输入：命中这类节点风险最高，提示里要点明。 */
        val interactive: Boolean,
    )

    /**
     * @return 中心点命中的那个节点；null 表示这个落点上没有别的节点（盲点安全）。
     *
     * 只把**面积小于目标**的节点算作命中：那是「点下去真正会被按到的控件」。
     * 反过来，全屏根 ScrollView 这类祖先容器覆盖一切，若也算命中，
     * 就等于永远禁止盲点 —— 而它本身不是被点到的东西。
     */
    fun centerHitOnOtherNode(
        targetIndex: Int,
        target: Box,
        candidates: List<Candidate>,
    ): Candidate? {
        if (target.isEmpty) return null
        val x = target.centerX
        val y = target.centerY
        return candidates
            .asSequence()
            .filter { it.index != targetIndex && !it.box.isEmpty }
            .filter { it.box.area < target.area }
            .filter { it.box.contains(x, y) }
            // 取面积最小者：那才是「点下去真正会被按到」的控件，提示也最具体。
            .minByOrNull { it.box.area }
    }
}
