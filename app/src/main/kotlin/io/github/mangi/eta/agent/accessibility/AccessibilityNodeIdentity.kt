package io.github.mangi.eta.agent.accessibility

/** 可在 JVM 中验证的节点身份指纹；viewId 不是列表项唯一标识。 */
internal data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false
        // uniqueId 只证明还是同一个虚拟节点，不证明它仍表达模型观察时的动作语义。
        if (text != refreshed.text) return false
        if (description != refreshed.description) return false
        return true
    }
}

/**
 * 窗口内容变化后，只有真正稳定且在观察范围内可证明唯一的身份才能继续使用。
 * 截断快照无法证明 text/desc 指纹在窗口其余部分不存在重复项。
 */
internal object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
    ): Boolean =
        identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated)

    /**
     * 输入框在内容变化的窗口里的兜底判定。
     *
     * 为什么需要它：空的输入框没有 text / desc / uniqueId，文本指纹根本用不上；
     * 有内容的输入框在**截断快照**里也无法证明自己的文本全局唯一
     * （`canBypassContentChange` 要求 `!snapshotTruncated`）—— 而输入框恰恰是最常被写入的控件，
     * 宿主窗口又常常一直在变（打字动画、光标闪烁、来消息）。
     * 真机证据：Eta 自己的会话输入框，写入首次与自动重试两次都是 STALE_CONTENT，一个字没写进去。
     *
     * 换成几何指纹：同一窗口内「类名 + viewId + 提示语 + 屏幕矩形」唯一，
     * 且刷新后矩形/类名/viewId 未变。矩形是几何事实，与其他语义指纹不同，
     * 不受快照截断影响（被截掉的节点不可能和它同矩形同时可见）；
     * 写完要改什么语义，仍由动作前的 `identityMatches` 再校一遍。
     */
    fun canBypassContentChangeForInputField(
        structuralMatchCount: Int,
        refreshedStructureUnchanged: Boolean,
    ): Boolean = structuralMatchCount == 1 && refreshedStructureUnchanged
}
