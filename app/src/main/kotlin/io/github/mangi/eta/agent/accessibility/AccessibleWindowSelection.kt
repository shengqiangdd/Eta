package io.github.mangi.eta.agent.accessibility

/**
 * 主屏节点采集该用哪个窗口（fix165）。
 *
 * 背景：主屏分支原先直接用 `rootInActiveWindow`，而它是**跨屏的活跃窗口**。
 * 虚拟屏上有应用时（尤其刚在上面点过东西），活跃窗口会变成虚拟屏那个，
 * 于是「观察主屏」拿到的是虚拟屏的界面 —— `after` 反馈因此指向另一块屏。
 *
 * 真机取证（本机 PKX110，主包，修复前）：
 *
 * ```text
 * observe_screen()                      → 主屏桌面（window 2059）
 * tap(display_id=148, x=360, y=422)     → 动作在虚拟屏 148
 * after.package=com.oplus.account, after.window_id=w2848   ← 虚拟屏 148 的窗口
 * ```
 *
 * 这里刻意不引用 android 类型：纯 JVM 单测直接覆盖。
 */
internal object AccessibleWindowSelection {

    /**
     * @param windowId 应该用来取根节点的窗口
     * @param replacedActive 是否替换掉了活跃窗口（true 表示活跃窗口属于别的屏）
     */
    data class Choice(val windowId: Int, val replacedActive: Boolean)

    /**
     * @param activeWindowId `rootInActiveWindow` 的窗口 id（拿不到时为负数）
     * @param mainDisplayWindowIds 主屏上**有根节点**的窗口 id（按系统顺序，含系统/输入法窗口）
     * @param mainDisplayApplicationWindowIds 其中类型为应用窗口的 id（保持同一顺序）
     */
    fun chooseMain(
        activeWindowId: Int,
        mainDisplayWindowIds: List<Int>,
        mainDisplayApplicationWindowIds: List<Int>,
    ): Choice = when {
        // 没有任何主屏窗口信息（旧系统或查询失败）：保持原有行为，不要凭猜测换窗口。
        mainDisplayWindowIds.isEmpty() -> Choice(activeWindowId, false)
        // 活跃窗口就在主屏上：这就是用户看到的那块，不需要干预。
        activeWindowId in mainDisplayWindowIds -> Choice(activeWindowId, false)
        // 活跃窗口属于别的屏：改取主屏的应用窗口（桌面/前台应用都算）。
        mainDisplayApplicationWindowIds.isNotEmpty() -> Choice(mainDisplayApplicationWindowIds.first(), true)
        // 主屏只剩系统窗口：用它也好过拿别的屏来冒充主屏。
        else -> Choice(mainDisplayWindowIds.first(), true)
    }
}
