package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文本到底有没有输进去」的判断（fix159）。
 *
 * 守的是：`input -d N text ...` 在没聚焦输入框时**静默丢弃**且退出码 0，
 * 工具层不能因为命令成功就回 `ok=true` 而不加任何标注。
 */
class TextInputVerificationTest {
    private fun field(text: String, password: Boolean = false) =
        TextInputVerification.Field(text = text, password = password)

    @Test
    fun `text that actually changed is verified`() {
        assertEquals(
            TextInputVerification.Verdict.VERIFIED,
            TextInputVerification.judge(before = field(""), after = field("abc"), typed = "abc"),
        )
        assertEquals(
            TextInputVerification.Verdict.VERIFIED,
            TextInputVerification.judge(before = field("ab"), after = field("ababc"), typed = "abc"),
        )
    }

    @Test
    fun `unchanged text is not applied`() {
        // 真机现象：命令退出码 0，输入框里一个字都没变。
        assertEquals(
            TextInputVerification.Verdict.NOT_APPLIED,
            TextInputVerification.judge(before = field(""), after = field(""), typed = "abc"),
        )
    }

    @Test
    fun `without a before or after snapshot we say unknown, not success`() {
        assertEquals(
            TextInputVerification.Verdict.UNKNOWN,
            TextInputVerification.judge(before = null, after = field("abc"), typed = "abc"),
        )
        assertEquals(
            TextInputVerification.Verdict.UNKNOWN,
            TextInputVerification.judge(before = field(""), after = null, typed = "abc"),
        )
    }

    @Test
    fun `password fields are judged by length only`() {
        // 遮蔽文本可能读成等长占位；长度变了才算输进去。
        assertEquals(
            TextInputVerification.Verdict.VERIFIED,
            TextInputVerification.judge(before = field("", password = true), after = field("••", password = true), typed = "ab"),
        )
        assertEquals(
            TextInputVerification.Verdict.UNKNOWN,
            TextInputVerification.judge(before = field("••", password = true), after = field("••", password = true), typed = "ab"),
        )
    }

    @Test
    fun `an empty payload cannot be verified`() {
        assertEquals(
            TextInputVerification.Verdict.UNKNOWN,
            TextInputVerification.judge(before = field("x"), after = field("y"), typed = ""),
        )
    }

    @Test
    fun `the reachable unknown case still explains what to do`() {
        // 真实流程里最常见的组合：没有聚焦输入框 → judge 给 UNKNOWN（不是 NOT_APPLIED）。
        // 这条说明必须照样给出，否则模型只看到 ok=true，不知道文本可能已被丢弃。
        val verdict = TextInputVerification.judge(before = null, after = null, typed = "eta")
        assertEquals(TextInputVerification.Verdict.UNKNOWN, verdict)
        val note = TextInputVerification.noteFor(verdict, typed = "eta", hadFocusedField = false)
        assertNotNull(note)
        assertTrue(note!!.contains("静默丢弃"))
        // 看到了输入框但读不出文本：说法必须与「没看到输入框」不同。
        val unreadable = TextInputVerification.noteFor(
            TextInputVerification.Verdict.UNKNOWN,
            typed = "eta",
            hadFocusedField = true,
        )
        assertNotNull(unreadable)
        assertTrue(unreadable!!.contains("读不出"))
        assertTrue(unreadable != note)
    }

    @Test
    fun `notes point at the next step instead of repeating ok true`() {
        val noneDetected = TextInputVerification.noteFor(
            TextInputVerification.Verdict.NOT_APPLIED,
            typed = "abc",
            hadFocusedField = false,
        )
        assertNotNull(noneDetected)
        assertTrue(noneDetected!!.contains("tap_element"))
        // 没看到输入框 ≠ 看到了但没输进去：两种说法必须不同，否则模型会误判该修什么。
        val swallowed = TextInputVerification.noteFor(
            TextInputVerification.Verdict.NOT_APPLIED,
            typed = "abc",
            hadFocusedField = true,
        )
        assertNotNull(swallowed)
        assertTrue(swallowed!! != noneDetected)
        assertTrue(swallowed.contains("abc"))
        // 已经确认成功的路径不该再给一段解释，免得模型把成功当成「有异常」。
        assertNull(
            TextInputVerification.noteFor(
                TextInputVerification.Verdict.VERIFIED,
                typed = "abc",
                hadFocusedField = true,
            ),
        )
    }

    @Test
    fun `clearing is verified only when the field is actually empty`() {
        // 清空走 CTRL+A + DEL，同样会「退出 0 但什么都没做」；fix159 对清空直接给了 UNKNOWN，
        // 等于从不验证。现在清空必须自己有一个结论。
        assertEquals(
            TextInputVerification.Verdict.VERIFIED,
            TextInputVerification.judge(
                before = field("eta"),
                after = field(""),
                typed = "",
                mode = TextInputVerification.Mode.CLEAR,
            ),
        )
        assertNull(
            TextInputVerification.noteFor(
                TextInputVerification.Verdict.VERIFIED,
                typed = "",
                hadFocusedField = true,
                mode = TextInputVerification.Mode.CLEAR,
            ),
        )
    }

    @Test
    fun `a clear that left text behind is not applied`() {
        val verdict = TextInputVerification.judge(
            before = field("eta"),
            after = field("eta"),
            typed = "",
            mode = TextInputVerification.Mode.CLEAR,
        )
        assertEquals(TextInputVerification.Verdict.NOT_APPLIED, verdict)
        val note = TextInputVerification.noteFor(
            verdict,
            typed = "",
            hadFocusedField = true,
            mode = TextInputVerification.Mode.CLEAR,
        )
        assertNotNull(note)
        assertTrue(note!!.contains("不生效"))
        // 没有聚焦框时清空同样会静默失败，说法必须不同。
        val noField = TextInputVerification.noteFor(
            TextInputVerification.Verdict.UNKNOWN,
            typed = "",
            hadFocusedField = false,
            mode = TextInputVerification.Mode.CLEAR,
        )
        assertNotNull(noField)
        assertTrue(noField!!.contains("清空"))
        assertTrue(noField != note)
    }

    @Test
    fun `append must produce the original text plus the new part`() {
        // 正确：原文 + 新增。
        assertEquals(
            TextInputVerification.Verdict.VERIFIED,
            TextInputVerification.judge(
                before = field("et"),
                after = field("eta"),
                typed = "a",
                mode = TextInputVerification.Mode.APPEND,
            ),
        )
        // 「以为是追加、实际是整体替换」：文本确实变了，但不是追加 —— 旧判据会当成 VERIFIED。
        assertEquals(
            TextInputVerification.Verdict.NOT_APPLIED,
            TextInputVerification.judge(
                before = field("et"),
                after = field("a"),
                typed = "a",
                mode = TextInputVerification.Mode.APPEND,
            ),
        )
        // 根本没加进去。
        assertEquals(
            TextInputVerification.Verdict.NOT_APPLIED,
            TextInputVerification.judge(
                before = field("et"),
                after = field("et"),
                typed = "a",
                mode = TextInputVerification.Mode.APPEND,
            ),
        )
    }

    @Test
    fun `append that changed text in an unexpected way stays unknown`() {
        // 文本变了，但不是「原文 + 新增」：可能是输入法改写、光标不在末尾。说不准就别报成功。
        val verdict = TextInputVerification.judge(
            before = field("et"),
            after = field("etx"),
            typed = "a",
            mode = TextInputVerification.Mode.APPEND,
        )
        assertEquals(TextInputVerification.Verdict.UNKNOWN, verdict)
        val note = TextInputVerification.noteFor(
            verdict,
            typed = "a",
            hadFocusedField = true,
            mode = TextInputVerification.Mode.APPEND,
        )
        assertNotNull(note)
        assertTrue(note!!.contains("追加"))
        // 与「被整体替换」那种说法必须区别开：两者该修的东西不一样。
        val replaced = TextInputVerification.noteFor(
            TextInputVerification.Verdict.NOT_APPLIED,
            typed = "a",
            hadFocusedField = true,
            mode = TextInputVerification.Mode.APPEND,
        )
        assertNotNull(replaced)
        assertTrue(replaced!!.contains("整体替换"))
        assertTrue(replaced != note)
    }

    @Test
    fun `replace with identical content is unconfirmed not failed`() {
        val same = TextInputVerification.Field(text = "abc", password = false)
        // 写的内容和现有内容完全一样：读出来无差别，不能断言「没写进去」。
        assertEquals(
            TextInputVerification.Verdict.UNKNOWN,
            TextInputVerification.judge(before = same, after = same, typed = "abc"),
        )
        // 该变又真没变：「没生效」仍然是正确结论。
        assertEquals(
            TextInputVerification.Verdict.NOT_APPLIED,
            TextInputVerification.judge(before = same, after = same, typed = "xyz"),
        )
        val note = TextInputVerification.noteFor(
            TextInputVerification.Verdict.UNKNOWN,
            typed = "abc",
            hadFocusedField = true,
            beforeText = "abc",
        )
        assertNotNull(note)
        assertTrue(note!!.contains("本来就是"))
        // 与「读不出文本」必须区分：两者的处理建议不一样。
        assertTrue(
            note != TextInputVerification.noteFor(
                TextInputVerification.Verdict.UNKNOWN,
                typed = "abc",
                hadFocusedField = true,
            ),
        )
    }

    @Test
    fun `paste fallback also carries input_verification`() {
        // 守卫 fix182：ACTION_PASTE 成功时必须给出同样的 verified 结论。
        // 真机代价：探针只读 input_verification，于是一次完全成功的粘贴被报成「未验证」。
        assertEquals("verified", TextInputVerification.wireVerificationField("ACTION_PASTE", true))
        assertEquals("unknown", TextInputVerification.wireVerificationField("ACTION_PASTE", false))
        // 直写通路（SET_TEXT 被接受）与它的粘贴变体都得保留原有行为。
        assertEquals("verified", TextInputVerification.wireVerificationField("ACTION_SET_TEXT", true))
        assertEquals("verified", TextInputVerification.wireVerificationField("ACTION_SET_TEXT_PASTE", true))
        // 非文本写入的动作不补：verified 只说明它自己那件事做成了。
        assertNull(TextInputVerification.wireVerificationField("GESTURE_SCROLL", true))
        assertNull(TextInputVerification.wireVerificationField("ACTION_CLICK", true))
        // 没有结论就不编一个（outcomeUnknown 的 verified 是 null）。
        assertNull(TextInputVerification.wireVerificationField("ACTION_PASTE", null))
        assertNull(TextInputVerification.wireVerificationField("", true))
    }
}
