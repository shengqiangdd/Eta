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
        // 2026-10-27（fix184 自查）：这里原来不分 mode 一律走 REPLACE 的长度比较。
        // 追加时正确结果的长度是「原长度 + 新增长度」，与 target 长度必然不同 ——
        // 于是一次正常的密码框追加会被判成 UNKNOWN（说「不确定」，实际写进去了）。
        // 不区分 mode 也就等于把「追加」当成「替换」在判：语义错了，结论只会更不可靠。
        if (before.password) {
            return when (mode) {
                Mode.APPEND -> judgePasswordAppend(before, after, typed)
                else -> judgePasswordReplace(before, after, typed)
            }
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
        return when {
            after.text == typed -> Verdict.VERIFIED
            after.text == before.text -> Verdict.NOT_APPLIED
            // 2026-10-27（fix184）：这里原来是「变了就算成功」（`else -> VERIFIED`）。
            // 输入框做了截断或改写时（长度上限、自动大写、加分隔符），文本确实变了，
            // 但和要写的内容对不上 —— 旧判据照样报 verified，模型以为原样写进去了。
            // 这不是「没写进去」（那是 NOT_APPLIED），而是「写的和要的不一样」，只能说不确定。
            else -> Verdict.UNKNOWN
        }
    }

    /**
     * 密码框的 REPLACE：读不到内容，只能比长度。
     *
     * 2026-10-27（fix184）：旧判据是「长度变了就算输进去」，于是写入被截断（要 30 个、
     * 只进去 10 个）也报 verified。现在必须**等于要写入的长度**才算数；
     * 要写入的长度与原有长度相同（幂等重写）时，读出来一样，只能给 UNKNOWN。
     * 掩码长度固定（例如始终 6 个 ●）的输入框会因此长期停在 UNKNOWN —— 这是安全方向：
     * 宁可说「不确定」，也不能把截断说成成功。
     */
    private fun judgePasswordReplace(before: Field, after: Field, typed: String): Verdict {
        return when {
            typed.length == before.text.length -> Verdict.UNKNOWN
            after.text.length == typed.length -> Verdict.VERIFIED
            else -> Verdict.UNKNOWN
        }
    }

    /**
     * 密码框的 APPEND：同样只能比长度，但正确结果的长度是「原长度 + 新内容长度」。
     *
     * 这条分支是 fix184 自查补上的：改判据时把密码框统一塞进了 REPLACE 的长度比较，
     * 于是**成功的追加**（长度变长、但不是目标长度）会被报成 UNKNOWN ——
     * 方向是安全的（没说假话），但结论也不对：明明写进去了却说「不确定」。
     * 用 `after.text == typed` 作替换判据同样不成立：密码框读出来是掩码，不是原文。
     */
    private fun judgePasswordAppend(before: Field, after: Field, typed: String): Verdict {
        return when {
            // 追加的正确结果：长度 = 原长度 + 新内容长度。
            after.text.length == before.text.length + typed.length -> Verdict.VERIFIED
            // 长度正好等于要追加的内容：旧内容被整体替换掉了。
            after.text.length == typed.length && typed.length != before.text.length ->
                Verdict.NOT_APPLIED
            // 长度没变（追加被吞）或变了但对不上（截断、固定掩码）：只能说不确定。
            else -> Verdict.UNKNOWN
        }
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
            // 2026-10-27（fix184）：before 读出来本来就是空（内容读不到）时，after 空不能
            // 证明「清空了」—— 那种情况从前会直接报 verified，等于对读不到的框从不验证。
            return if (before.text.isNotEmpty() && after.text.isEmpty()) {
                Verdict.VERIFIED
            } else {
                Verdict.UNKNOWN
            }
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
        after: Field? = null,
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
            // 密码框读不到内容：追加也只能比长度，不能回显「输入框里现在是「…」」（那是掩码或空串）。
            // 2026-10-27（fix184 自查）：judge 已按 mode 区分，这里同步说清结论，否则模型会去读一段不存在的文本。
            mode == Mode.APPEND && after?.password == true && verdict != Verdict.VERIFIED ->
                if (verdict == Verdict.NOT_APPLIED) {
                    "你要的是「追加」，但输入框是密码框（读不到内容），读回的长度正好等于「${echo(typed)}」本身：" +
                        "原内容被整体替换了。确实要保留原内容时，只能整段重写（先把新旧内容拼好再整体写入）。"
                } else {
                    "输入框是密码框（读不到内容），只能比长度：追加后读回 ${after.text.length} 个字符，" +
                        "与「原长度 + ${typed.length}」对不上（截断、固定掩码，或没追加上）。" +
                        "无法确认是否按追加语义写进去，请让用户自行确认。"
                }
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
            // 写后的内容既不是原文，也不是要写的内容：输入框做了改写或截断（长度上限、自动大写、
            // 加分隔符），或者只写进去一部分。这与「读不出文本」是两件事，必须分开说（fix184）。
            // 只报长度、不回显内容：输入框里可能是用户自己的数据。
            mode != Mode.APPEND && verdict == Verdict.UNKNOWN && after != null &&
                !after.password && beforeText != null &&
                after.text != typed && after.text != beforeText ->
                "写入命令执行了（退出码 0），但输入框里现在是 ${after.text.length} 个字符，" +
                    "与你要写入的 ${typed.length} 个字符对不上：可能被输入框截断（长度上限）、" +
                    "自动改写（大写或加分隔符），或者只写进去一部分。请 observe_screen 核对；" +
                    "不要当成已经原样写入，需要确证时把内容改短到输入框允许的范围再写一次。"
            // 密码框读不到内容，只能比长度：长度对不上同样不能说「写进去了」。
            mode != Mode.APPEND && verdict == Verdict.UNKNOWN && after != null &&
                after.password && beforeText != null &&
                after.text.length != beforeText.length && after.text.length != typed.length ->
                "输入框是密码框（读不到内容），只能比长度：写入后长度是 ${after.text.length}，" +
                    "与你要写入的 ${typed.length} 个字符对不上，无法确认是否原样写入。请让用户自行确认。"
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
