package io.github.mangi.eta.ui.app

/**
 * 分叉操作的重入守卫。
 *
 * 为什么需要它：分叉是一次数据库事务，而界面在完成前**没有任何变化** ——
 * 新会话的前缀与原会话完全相同，标题又不在当前视图里。用户很容易以为没点中而反复点，
 * 每点一次就多一个「分支会话」。实测时我自己连点了三次，库里就多了三个。
 *
 * 这里只做一件事：同一时刻只允许一个分叉在跑。抽成独立类是为了能在纯 JVM 里测 ——
 * 逻辑在 `AgentAppState` 里时要构造整个 UI 状态才能验证，成本高到不会有人写。
 */
internal class BranchRequestGuard {

    private var inFlight = false

    /** 当前是否有分叉在执行。 */
    fun isInFlight(): Boolean = inFlight

    /**
     * 尝试开始一次分叉。
     *
     * @return true 表示本次调用获得了执行权；false 表示已有分叉在跑，调用方应提示并放弃。
     */
    fun tryBegin(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    /**
     * 结束一次分叉。
     *
     * 必须在 finally 里调用：失败路径若忘了复位，功能会永久卡在「正在创建」，
     * 而用户看到的只是「点了没反应」—— 与最初那个 bug 同一种不可感知的失败。
     */
    fun finish() {
        inFlight = false
    }
}
