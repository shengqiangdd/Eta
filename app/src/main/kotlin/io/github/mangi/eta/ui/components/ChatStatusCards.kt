package io.github.mangi.eta.ui.components

/**
 * 聊天页底部那几张常驻卡（目标 / 待办 / 子代理台账 / 后台任务）**该不该出现**。
 *
 * 为什么单独抽出来：这几张卡原来各自判断，条件是「数据非空就画」，
 * 于是跑过一次任务之后，即使全都结束了、也没有任何在跑的东西，卡片依然占着
 * 输入框上方的位置。用户读到的信号是「还有事没完」，实际上什么都没在做。
 *
 * 这里的判据统一成一条：**只有需要用户注意的东西才占常驻位**。
 * - 有东西在跑 → 出现（不然用户没法知道它在跑）
 * - 全部结束但没看过的失败/停滞 → 出现（这是需要处理的）
 * - 全部成功且已收尾 → 收起成一行摘要或直接不画
 *
 * 纯函数：不碰 Compose、不读磁盘，单测可直接断言。
 */
internal object ChatStatusCards {

    /**
     * 台账卡是否还有「现在就该处理」的内容。
     *
     * 注意 `RUNNING` 之外的状态：失败与停滞都值得留一行 —— 它们是**还没被处理**的结果，
     * 用户可能正要重派。已完成的没有这个性质，所以全部完成时不再占位。
     */
    fun ledgerNeedsAttention(
        running: Int,
        failed: Int,
        stalled: Int,
    ): Boolean = running > 0 || failed > 0 || stalled > 0

    /**
     * 台账卡最终的可见性。
     *
     * @param alwaysShow 用户偏好：始终显示台账（关掉「自动收起」时用）
     * @param hasEntries 台账里是否还有条目（空台账本来就不该画）
     */
    fun ledgerVisible(
        hasEntries: Boolean,
        alwaysShow: Boolean,
        running: Int,
        failed: Int,
        stalled: Int,
    ): Boolean {
        if (!hasEntries) return false
        if (alwaysShow) return true
        return ledgerNeedsAttention(running, failed, stalled)
    }

    /**
     * 后台任务卡是否该出现。
     *
     * 它回答的是「现在还有什么在跑」：有运行中的任务就画。
     * 全部结束时**不再常驻** —— 一条已经跑完的记录留在输入框上方，读起来像「还有事没完」，
     * 而实际什么都没在做。只保留一个短窗口：刚结束、用户还可能点「继续」的那一小段时间
     * （[resumableRecently]），窗口一过卡片自动消失。
     */
    fun backgroundVisible(
        hasRecords: Boolean,
        runningTotal: Int,
        resumableRecently: Boolean,
    ): Boolean = hasRecords && (runningTotal > 0 || resumableRecently)
}
