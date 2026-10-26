package io.github.mangi.eta.agent.device

import android.view.Display

/**
 * 虚拟屏点击与主屏输入法的隔离策略。
 *
 * 虚拟屏没有自己的 IME 窗口；为了不让键盘弹到主屏，点击期间会临时切到无 UI
 * 输入法。这里把两个边界钉死：
 * - 主屏已经在显示键盘时，不再切 IME / 不再执行虚拟点击（用户正在输入，不能抢）；
 * - 点击成功后保留一小段静默窗口，晚到的 showSoftInput 也不会落到主屏。
 */
internal object VirtualDisplayImePolicy {
    /** 主屏 IME 可见时最多等待它收起的时间；超时返回 MAIN_INPUT_ACTIVE。 */
    const val MAIN_INPUT_WAIT_MS = 1_200L
    const val MAIN_INPUT_POLL_MS = 200L

    /** 点击后继续静默的时间，覆盖输入框动画/延迟 showSoftInput。 */
    const val SILENT_GRACE_MS = 800L

    fun focusedDisplayId(inputDump: String): Int? =
        Regex("FocusedDisplayId:\\s*(-?\\d+)")
            .find(inputDump)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    /**
     * 某个虚拟屏上是否真的有输入会话（输入框已连接 IME）。
     *
     * ClientState 的 header 之后到下一个 ClientState 之前都属于该客户端；
     * `curSession=SessionState` / `sessionRequested=true` 表示该屏正在等输入法。
     */
    fun virtualDisplayHasInputSession(inputMethodDump: String, displayId: Int): Boolean {
        if (inputMethodDump.isBlank()) return false
        val marker = "mSelfReportedDisplayId=$displayId"
        return inputMethodDump.split("ClientState{").drop(1).any { part ->
            val header = part.substringBefore('}')
            if (!header.contains(marker)) return@any false
            val body = part.substringAfter('}', "")
            body.contains("sessionRequested=true") || body.contains("curSession=SessionState")
        }
    }

    fun mainImeVisible(inputMethodDump: String): Boolean {
        if (inputMethodDump.isBlank()) return false
        val shown = Regex("mInputShown=(true|false)")
            .findAll(inputMethodDump)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toBooleanStrictOrNull()
        if (shown == true) {
            // 显示时还要看输入法客户端属于哪个 display。
            // 实测死锁：虚拟屏自己的输入框聚焦后 mInputShown=true（mSelfReportedDisplayId=80），
            // 旧逻辑把它当「主屏正在输入」，于是虚拟屏后续每一次点击都被 MAIN_INPUT_ACTIVE 挡掉。
            val displayId = Regex("mSelfReportedDisplayId=(\\d+)")
                .findAll(inputMethodDump)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            return displayId == null || displayId == Display.DEFAULT_DISPLAY
        }
        val visibility = Regex("mImeWindowVis=(0x[0-9a-fA-F]+)")
            .findAll(inputMethodDump)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.removePrefix("0x")
            ?.toLongOrNull(16)
        return visibility != null && visibility != 0L
    }
}
