package io.github.mangi.eta.agent.device

/**
 * 虚拟屏上「文本到底有没有输进去」的判断。
 *
 * 背景（fix159）：虚拟屏的 ASCII 输入走 `input -d N text ...`。这条命令在**当前屏没有
 * 聚焦输入框时会静默丢弃文本，但退出码仍是 0** —— 于是工具层返回 `ok=true`，
 * 模型以为字已经打进去了，接着去点「搜索」却发现页面上什么都没有。
 * 这与 fix156 / fix157 / fix158 是同一类问题：**结果说成功，但没确认是不是要的那件事**。
 *
 * 做法：输入前后各读一次「当前聚焦的可编辑节点」，比较它的文本。
 * 判不准时必须说「不确定」，不能默认成功：
 * - 密码框的文本会被遮蔽（或读成等长占位），只能比长度；
 * - 读不到树（无障碍断开）时同样是「不确定」。
 *
 * 刻意不引用 android 类型：本对象被纯 JVM 单测直接覆盖。
 */
internal object TextInputVerification {
    /** 一次观察里「聚焦的可编辑节点」的文本状态。 */
    data class Field(val text: String, val password: Boolean)

    enum class Verdict {
        /** 文本确实变了（密码框则是长度变了）。 */
        VERIFIED,

        /** 文本一字未变：几乎可以断定没有输进去。 */
        NOT_APPLIED,

        /** 没有前后对照可比较（找不到聚焦输入框、读不到树、或本来就没输入文本）。 */
        UNKNOWN,
    }

    fun judge(before: Field?, after: Field?, typed: String): Verdict {
        if (typed.isEmpty()) return Verdict.UNKNOWN
        if (before == null || after == null) return Verdict.UNKNOWN
        if (before.password) {
            // 遮蔽文本（●●●●）读出来可能是等长占位，只能看长度有没有变化。
            return if (after.text.length != before.text.length) Verdict.VERIFIED else Verdict.UNKNOWN
        }
        return if (after.text == before.text) Verdict.NOT_APPLIED else Verdict.VERIFIED
    }

    /** 给模型看的说明；没有需要额外解释的情况时返回 null。 */
    fun noteFor(verdict: Verdict, typed: String, hadFocusedField: Boolean): String? = when {
        typed.isEmpty() -> null
        verdict == Verdict.VERIFIED -> null
        // 没看到聚焦输入框：不管判成 UNKNOWN 还是 NOT_APPLIED，都要说「文本可能被直接丢弃」。
        // 注意 UNKNOWN 才是常态：before 为 null 时 judge 必然给 UNKNOWN。
        // 以前这句话只写在 NOT_APPLIED 分支里 —— 那条组合在真实流程中永远不会出现，
        // 等于把最需要说的那种情况永远说不出来（fix159 自查发现）。
        !hadFocusedField ->
            "没有看到聚焦的输入框：`input text` 在这种情况下会静默丢弃文本（命令仍退出 0）。" +
                "请先 tap_element / tap_area 点开目标输入框再重新输入；不要因为 ok=true 就以为已经输入。"
        verdict == Verdict.UNKNOWN ->
            "看到了输入框，但读不出它的文本（可能是密码框，或无障碍拿不到文本），无法确认是否写进去。" +
                "请 observe_screen 核对一次再决定下一步。"
        else ->
            "输入命令执行了（退出码 0），但输入「${echo(typed)}」之后，聚焦输入框里的文本一字未变，" +
                "多半是被页面吞掉了（例如输入框只读、或该界面把键盘事件转走了）。" +
                "请 observe_screen 确认，不要原样重试第三次；需要时改用 tap_element 点开输入框。"
    }

    /** 把实际（截断后的）输入内容带进提示里，便于模型对账自己到底点了什么。 */
    private fun echo(typed: String): String {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return "（空）"
        return if (trimmed.length <= 24) trimmed else trimmed.take(24) + "…"
    }
}
