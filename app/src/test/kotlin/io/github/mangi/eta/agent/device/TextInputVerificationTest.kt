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
}
