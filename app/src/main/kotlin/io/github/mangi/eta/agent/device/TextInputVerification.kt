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
        /** 文本确实变了（密码框则是长度变了；清空则是真的空了）。 */
        VERIFIED,

        /** 文本没达到预期：一字未变、追加被整体替换、或清空后还有内容。 */
        NOT_APPLIED,

        /** 没有前后对照可比较（找不到聚焦输入框、读不到树、或本来就没输入文本）。 */
        UNKNOWN,
    }

    /**
     * 这次输入到底在做什么。
     *
     * 为什么必须分开：同一个「文本变了」在三种语义下含义完全不同 ——
     * 追加要求变成「原有内容 + 新增内容」，清空要求变成空串。
     * 只看「变没变」（fix159 第一版）会把「append 被整体替换」「清空没生效」都判成成功。
     */
    enum class Mode {
        /** 整体替换：文本应该变成 typed。 */
        REPLACE,

        /** 光标处插入：文本应该变成 before + typed。 */
        APPEND,

        /** 清空：文本应该变空。 */
        CLEAR,
    }

    fun judge(
        before: Field?,
        after: Field?,
        typed: String,
        mode: Mode = Mode.REPLACE,
    ): Verdict {
        if (mode == Mode.CLEAR) return judgeCleared(before, after)
        if (typed.isEmpty()) return Verdict.UNKNOWN
        if (before == null || after == null) return Verdict.UNKNOWN
        if (before.password) {
            // 遮蔽文本（●●●●）读出来可能是等长占位，只能看长度有没有变化。
            return if (after.text.length != before.text.length) Verdict.VERIFIED else Verdict.UNKNOWN
        }
        if (mode == Mode.APPEND) {
            return when {
                // 追加的正确结果只有一个：原文 + 新内容。
                after.text == before.text + typed -> Verdict.VERIFIED
                // 旧内容被整体换成了新内容 —— 这正是「以为是追加、实际是替换」的样子。
                after.text == typed && typed != before.text -> Verdict.NOT_APPLIED
                after.text == before.text -> Verdict.NOT_APPLIED
                // 变了，但不是「原文 + 新增」：塞进去了点别的东西，说不准，别报成功。
                else -> Verdict.UNKNOWN
            }
        }
        // 目标内容与现有完全相同：写与不写，读出来一模一样 —— 不能断言「没写进去」。
        // 2026-10-27：旧写法直接给 NOT_APPLIED，配套文案是「一字未变，多半被页面吞掉了」，
        // 于是「幂等重写同一段文本」被报成失败，模型会再写一遍（甚至去改别的字段）。
        if (typed == before.text) return Verdict.UNKNOWN
        return if (after.text == before.text) Verdict.NOT_APPLIED else Verdict.VERIFIED
    }

    /**
     * 清空是否真的生效。
     *
     * 背景：清空走 `input keycombination 113 29` + `keyevent 67`（CTRL+A + DEL），
     * 这两条命令同样「退出码 0 但什么都没做」—— 自绘输入框根本不实现全选。
     * fix159 第一版对清空直接 `typed.isEmpty() -> UNKNOWN`，等于**从不验证清空**。
     */
    private fun judgeCleared(before: Field?, after: Field?): Verdict {
        if (before == null || after == null) return Verdict.UNKNOWN
        if (before.password) {
            // 密码框读不出内容，只能看长度是否归零。
            return if (after.text.isEmpty()) Verdict.VERIFIED else Verdict.UNKNOWN
        }
        return if (after.text.isEmpty()) Verdict.VERIFIED else Verdict.NOT_APPLIED
    }

    /** 给模型看的说明；没有需要额外解释的情况时返回 null。 */
    fun noteFor(
        verdict: Verdict,
        typed: String,
        hadFocusedField: Boolean,
        mode: Mode = Mode.REPLACE,
        beforeText: String? = null,
    ): String? {
        if (mode == Mode.CLEAR) return when {
            verdict == Verdict.VERIFIED -> null
            !hadFocusedField ->
                "没有读到聚焦的输入框：CTRL+A + DEL 在这种情况下同样会静默失败（命令仍退出 0），" +
                    "无法确认文本是否已清空。请先 tap_element 点开目标输入框再重试，不要因为 ok=true 就以为已经清空。"
            verdict == Verdict.NOT_APPLIED ->
                "清空命令执行了（退出码 0），但输入框里还有文本：CTRL+A 全选 + DEL 在自绘输入框上不生效。" +
                    "请 observe_screen 核对内容，必要时点界面上的「清除」按钮，或改用长按删除。"
            else ->
                "看到了输入框，但读不出它的文本（密码框，或无障碍拿不到内容），无法确认是否已清空。" +
                    "请 observe_screen 核对一次再决定下一步。"
        }
        return when {
            typed.isEmpty() -> null
            verdict == Verdict.VERIFIED -> null
            // 没看到聚焦输入框：不管判成 UNKNOWN 还是 NOT_APPLIED，都要说「文本可能被直接丢弃」。
            // 注意 UNKNOWN 才是常态：before 为 null 时 judge 必然给 UNKNOWN。
            // 以前这句话只写在 NOT_APPLIED 分支里 —— 那条组合在真实流程中永远不会出现，
            // 等于把最需要说的那种情况永远说不出来（fix159 自查发现）。
            !hadFocusedField ->
                "没有看到聚焦的输入框：`input text` 在这种情况下会静默丢弃文本（命令仍退出 0）。" +
                    "请先 tap_element / tap_area 点开目标输入框再重新输入；不要因为 ok=true 就以为已经输入。"
            // 追加语义下的两种「不对」必须分开说：整体替换 vs 说不清。
            mode == Mode.APPEND && verdict == Verdict.NOT_APPLIED ->
                "你要的是「追加」，但输入框里现在是「${echo(typed)}」而不是「原有内容 + ${echo(typed)}」：" +
                    "原内容被整体替换了（或根本没加进去）。请 observe_screen 确认，需要保留原内容时先读出再重新整体写入。"
            mode == Mode.APPEND && verdict == Verdict.UNKNOWN ->
                "文本变了，但不是「原有内容 + ${echo(typed)}」，无法确认是按追加语义写进去的。" +
                    "请 observe_screen 核对一次，不要当成已经追加成功。"
            // 目标与原文相同时 judge 只能给 UNKNOWN：这时「没写」与「写进去了」读起来一样，
            // 不能沿用下面「读不出文本」的说法（那是密码框或树读不到的情形）。
            mode != Mode.APPEND && verdict == Verdict.UNKNOWN &&
                beforeText != null && typed == beforeText ->
                "输入框里本来就是「${echo(typed)}」，写入后读到的仍是同一段文本：" +
                    "无法区分是这次写进去的，还是原来就在。按「未确认」上报 —— " +
                    "内容对得上就继续；需要确证时先把内容改成别的值再写回来。"
            verdict == Verdict.UNKNOWN ->
                "看到了输入框，但读不出它的文本（可能是密码框，或无障碍拿不到文本），无法确认是否写进去。" +
                    "请 observe_screen 核对一次再决定下一步。"
            else ->
                "输入命令执行了（退出码 0），但输入「${echo(typed)}」之后，聚焦输入框里的文本一字未变，" +
                    "多半是被页面吞掉了（例如输入框只读、或该界面把键盘事件转走了）。" +
                    "请 observe_screen 确认，不要原样重试第三次；需要时改用 tap_element 点开输入框。"
        }
    }

    /**
     * 无障碍通路的回报里 `input_verification` 该填什么（`null` = 不填这个字段）。
     *
     * 为什么需要它（fix182）：同一个「文本写成了」在这条通路里曾有两种回报形状 ——
     * `method` 含 `SET_TEXT` 时补 `input_verification`，而 `ACTION_PASTE`（目标拒绝 SET_TEXT、
     * 改走 [PASTE_METHOD] 兜底）**只有 `verified`**。按 `input_verification` 判断的消费方
     * 在兜底成功时反而读不到结论：2026-10-27 真机探针就这样把「粘贴成功」报成「未验证」。
     *
     * 为什么按 method 而不是「有 verified 就补」：`verified` 也出现在别的动作上，
     * 那些不是文本写入，补一个 `input_verification` 只会把语义弄乱。
     */
    fun wireVerificationField(method: String, verified: Boolean?): String? {
        if (verified == null) return null
        val isTextWrite = method.contains("SET_TEXT") || method == PASTE_METHOD
        if (!isTextWrite) return null
        return if (verified) "verified" else "unknown"
    }

    /** 粘贴兜底分支的动作名（与 `AgentAccessibilityService.pasteText` 保持一致）。 */
    const val PASTE_METHOD = "ACTION_PASTE"

    /** 把实际（截断后的）输入内容带进提示里，便于模型对账自己到底点了什么。 */
    private fun echo(typed: String): String {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return "（空）"
        return if (trimmed.length <= 24) trimmed else trimmed.take(24) + "…"
    }
}
