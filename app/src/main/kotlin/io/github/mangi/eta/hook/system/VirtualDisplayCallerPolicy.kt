package io.github.mangi.eta.hook.system

/**
 * 是否允许把 activity 放到某个虚拟屏上。
 *
 * 从 [VirtualDisplaySystemHooks] 里抽出来，是因为这段判定最容易写出**永不生效的死代码**：
 * 上游做法是「当前线程正处于 createVirtualDisplay 调用中才放行」，而
 * `canPlaceEntityOnDisplay()` 发生在 startActivity 时，与创建调用不在同一时段，
 * 那个标记早已复位 —— 逻辑看着严密，实际一行都不会命中。
 *
 * 所以判定只依赖**归属**：这个 displayId 是不是 Eta 自己建的。归属由创建成功后登记，
 * 与调用时刻无关。这条约束用 [VirtualDisplayCallerPolicyTest] 钉住。
 */
internal object VirtualDisplayCallerPolicy {

    /**
     * @param displayId 目标虚拟屏 id
     * @param etaDisplayIds Eta 创建成功过的虚拟屏 id 集合
     * @param callerIsEta 调用方 UID 是否属于 Eta；判不出来时应传 false
     */
    fun allows(
        displayId: Int?,
        etaDisplayIds: Set<Int>,
        callerIsEta: Boolean,
    ): Boolean {
        if (displayId == null) return false
        // displayId 0 是默认屏（主屏），负数/0 是创建失败时的返回值，
        // 都不该被当成「Eta 的虚拟屏」放行。
        if (displayId <= 0) return false
        if (displayId !in etaDisplayIds) return false
        return callerIsEta
    }
}
