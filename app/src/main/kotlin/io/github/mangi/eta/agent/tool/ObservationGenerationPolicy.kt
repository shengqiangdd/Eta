package io.github.mangi.eta.agent.tool

/**
 * 观察快照何时因「GUI 代号变化」而作废。
 *
 * 代号（generation）来自 GUI 控制锁：[GuiControlCoordinator] 每块屏各有一把锁，
 * 用户暂停/恢复、所有权换手时会推进代号。**不同屏的代号天生不相等**，
 * 所以不能只比代号就作废快照。
 *
 * 真机缺陷就在这儿：`virtual_display_create` / `virtual_display_launch` 的参数里还没有
 * `display_id`，它们的 scope 是 `main`；紧接着的 `tap_element` 带 `display_id=<新屏>`，
 * scope 是 `display:N`。两把锁代号不等 → 旧实现清空快照 → `NO_OBSERVATION`，
 * 「建屏 + 自动观察首屏」这条路每次都要多花一轮重新观察。
 */
internal object ObservationGenerationPolicy {
    /**
     * @param snapshotGeneration 快照登记时的代号；null 表示它没绑定任何锁（跨屏发布的首屏）。
     * @param currentGeneration 本次动作所在锁的代号。
     * @param snapshotDisplayId 快照来自哪块屏；null/0 表示主屏。
     * @param targetDisplayId 本次动作要动哪块屏；null/0 表示主屏。
     * @return true 表示这枚快照不能用于本次动作。
     */
    fun invalidate(
        snapshotGeneration: Long?,
        currentGeneration: Long?,
        snapshotDisplayId: Int?,
        targetDisplayId: Int?,
    ): Boolean {
        // 屏不一致：快照描述的不是这次要操作的界面，与本条规则无关，一律不可用。
        if (!sameDisplay(snapshotDisplayId, targetDisplayId)) return true
        // 没绑代号（跨屏发布的首屏）：屏对得上就能用，不看代号。
        if (snapshotGeneration == null) return false
        if (currentGeneration == null) return false
        // 同一块屏、代号相同：快照有效。
        if (snapshotGeneration == currentGeneration) return false
        // 同一块屏、代号变了：用户暂停/恢复或所有权换手，快照真的过期了。
        return true
    }

    /** 主屏用 null 与 0 两种写法表示，必须等价。 */
    private fun sameDisplay(left: Int?, right: Int?): Boolean {
        val leftMain = left == null || left == 0
        val rightMain = right == null || right == 0
        return if (leftMain || rightMain) leftMain == rightMain else left == right
    }
}
