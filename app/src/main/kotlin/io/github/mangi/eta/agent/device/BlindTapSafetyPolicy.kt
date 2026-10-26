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

    /** 节点树太小、不足以判断落点上有什么时的错误码。 */
    const val INSUFFICIENT_TREE_CODE: String = "COORDINATE_FALLBACK_INSUFFICIENT_TREE"

    /**
     * 可信判定所需的最少节点数。
     *
     * 真机教训：应用刚启动时无障碍只回 2 个容器节点，此时「中心点上没有别的节点」不是
     * 「那里没东西」，而是「我们还没看到」—— 拿这种树去盲点，等于把未知当成安全。
     */
    const val MIN_TRUSTED_NODES: Int = 4

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
        /** 开关类（Switch / CheckBox）：盲点落进它的区域 = 可能直接拨动一个设置。 */
        val toggles: Boolean = false,
    )

    /** 树够不够大、能不能拿来做安全判定。 */
    fun hasEnoughEvidence(candidates: List<Candidate>): Boolean = candidates.size >= MIN_TRUSTED_NODES

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

    /**
     * 第二层判据：目标区域里有没有**开关**（Switch / CheckBox）。
     *
     * 为什么还要这一层：第一层只看「中心点落在更小的节点上」。设置类页面常见「整行可点、
     * 行内是开关」，而目标节点的中心恰好压在行内空白处 —— 第一层不触发，但一个盲点就会
     * 拨动那个开关。这里按「开关的中心落在目标区域内」判，不要求中心点本身命中。
     *
     * 目标自己就是开关时不拦：点开关正是对的。
     *
     * @return 区域内的开关节点；null 表示区域内没有开关。
     */
    fun toggleInsideTarget(
        targetIndex: Int,
        target: Box,
        targetToggles: Boolean,
        candidates: List<Candidate>,
    ): Candidate? {
        if (targetToggles || target.isEmpty) return null
        return candidates
            .asSequence()
            .filter { it.index != targetIndex && it.toggles && !it.box.isEmpty }
            .filter { it.box.area < target.area }
            .filter { target.contains(it.box.centerX, it.box.centerY) }
            .minByOrNull { it.box.area }
    }

    /**
     * 拦截之后给模型的「一步就能做完」的替代目标。
     *
     * 拦截本身只说明了「不能点这个」，模型仍要自己猜该点谁：上一版提示里写的是
     * 「请改点 #blocked 或它的文本子节点」，而 #blocked 常常是个**不可交互的容器**
     * —— 照着点会再次被同一个判据拦住，白费一轮。所以这里直接把「可执行的那个节点」
     * 算出来：blocked 自己可交互就是它，否则在它范围内挑最合适的子控件。
     */
    data class Suggestion(
        val index: Int,
        /** [Kind.BLOCKED_NODE]：blocked 本身可交互；[Kind.CHILD_CONTROL]：范围内的子控件。 */
        val kind: Kind,
        val interactive: Boolean,
        val toggles: Boolean,
    ) {
        enum class Kind { BLOCKED_NODE, CHILD_CONTROL }

        /** 响应里用的短标识（`suggestion_kind`）。 */
        val kindName: String
            get() = when (kind) {
                Kind.BLOCKED_NODE -> "blocked_node"
                Kind.CHILD_CONTROL -> "child_control"
            }
    }

    /**
     * @return 可以真正点下去的那个节点；null 表示 blocked 范围内没有任何可交互控件
     *   （那种情况只能先重新观察，提示里就不要编一个 index 出来）。
     *
     * 子控件取**面积最大**的：整行可点（Row 带 ACTION_CLICK）比行内的小图标更容易点中，
     * 也更能表达「整行的语义」。这与 centerHitOnOtherNode 取最小面积的目标不同 ——
     * 那里要回答「盲点实际会按到谁」，这里要回答「该点哪个」。
     */
    fun suggestTarget(blocked: Candidate, candidates: List<Candidate>): Suggestion? {
        if (blocked.interactive) {
            return Suggestion(blocked.index, Suggestion.Kind.BLOCKED_NODE, interactive = true, toggles = blocked.toggles)
        }
        if (blocked.box.isEmpty) return null
        val child = candidates
            .asSequence()
            .filter { it.index != blocked.index && !it.box.isEmpty }
            .filter { it.interactive || it.toggles }
            .filter { it.box.area < blocked.box.area }
            .filter { blocked.box.contains(it.box.centerX, it.box.centerY) }
            .maxByOrNull { it.box.area }
            ?: return null
        return Suggestion(child.index, Suggestion.Kind.CHILD_CONTROL, child.interactive, child.toggles)
    }
}
