package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「参数名写错」不能被静静吞掉。
 *
 * 真机踩过（157 轮）：`scroll_element` 的 `display_id` 整条被忽略 —— 模型写「在主屏滚动」，
 * 实际滚的是虚拟屏，返回 `ok=true`。这处已在 fix167 补上校验；
 * 但**参数名本身**写错（`displayId`）时 `optInt("display_id")` 仍只会返回缺省值。
 */
class ToolArgumentHintPolicyTest {

    private val declared = setOf("index", "observation_id", "direction", "display_id")

    @Test
    fun `camel case name points at the declared snake case name`() {
        val hints = ToolArgumentHintPolicy.review(declared, listOf("index", "observation_id", "displayId"))

        assertEquals(1, hints.size)
        assertEquals("displayId", hints.single().provided)
        assertEquals("display_id", hints.single().suggestion)
    }

    @Test
    fun `declared names never produce hints`() {
        assertTrue(ToolArgumentHintPolicy.review(declared, declared.toList()).isEmpty())
    }

    @Test
    fun `unknown name has no suggestion but is still reported`() {
        val hints = ToolArgumentHintPolicy.review(declared, listOf("foo"))

        assertEquals(1, hints.size)
        assertEquals(null, hints.single().suggestion)
    }

    @Test
    fun `empty declared set stays silent`() {
        assertTrue(ToolArgumentHintPolicy.review(emptySet(), listOf("displayId")).isEmpty())
    }

    @Test
    fun `normalize ignores case and separators`() {
        assertEquals("displayid", ToolArgumentHintPolicy.normalize("displayId"))
        assertEquals("displayid", ToolArgumentHintPolicy.normalize("Display_Id"))
        assertEquals("displayid", ToolArgumentHintPolicy.normalize("display-id"))
    }

    @Test
    fun `suggestion message names the right parameter`() {
        val hint = ToolArgumentHintPolicy.Hint(provided = "displayId", suggestion = "display_id")
        val message = ToolArgumentHintPolicy.message(hint, declared)

        assertTrue(message, message.contains("displayId"))
        assertTrue(message, message.contains("display_id"))
        // 必须说清「本次照常执行」，否则模型会以为整次调用失败了。
        assertTrue(message, message.contains("缺省值"))
    }

    @Test
    fun `unknown message lists the declared parameters`() {
        val message = ToolArgumentHintPolicy.message(
            ToolArgumentHintPolicy.Hint(provided = "foo", suggestion = null),
            declared,
        )

        assertTrue(message, message.contains("foo"))
        assertTrue(message, message.contains("display_id"))
        assertTrue(message, message.contains("direction"))
    }
}
