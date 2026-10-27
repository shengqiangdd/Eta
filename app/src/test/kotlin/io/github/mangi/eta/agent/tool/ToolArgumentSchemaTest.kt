package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * schema 里声明的参数名必须是执行层真正会读的那些。
 *
 * 反过来的漏法本轮就踩到了（fix167）：`scroll_element` 执行层读 `display_id`，
 * 但 schema 里没写 —— 模型既不知道能传，传错名字也没有任何反馈。
 */
class ToolArgumentSchemaTest {

    @Test
    fun `element gestures declare the display id they actually read`() {
        for (name in listOf("tap_element", "long_press_element", "scroll_element")) {
            val keys = ToolArgumentSchema.declaredKeys(name)
            assertTrue("$name 没有登记到参数索引里", keys != null)
            assertTrue("$name 执行层会读 display_id，schema 必须声明", "display_id" in checkNotNull(keys))
        }
    }

    @Test
    fun `scroll element keeps direction and amount`() {
        val keys = checkNotNull(ToolArgumentSchema.declaredKeys("scroll_element"))

        assertTrue("direction" in keys)
        assertTrue("amount" in keys)
        assertTrue("index" in keys)
        assertTrue("observation_id" in keys)
    }

    @Test
    fun `long press element keeps duration`() {
        val keys = checkNotNull(ToolArgumentSchema.declaredKeys("long_press_element"))

        assertTrue("duration_ms" in keys)
    }

    @Test
    fun `coordinate gestures keep their positional parameters`() {
        val keys = checkNotNull(ToolArgumentSchema.declaredKeys("tap"))

        assertTrue("x" in keys)
        assertTrue("y" in keys)
        assertTrue("coordinate_space" in keys)
        assertTrue("display_id" in keys)
    }

    @Test
    fun `unknown tool is not guessed`() {
        assertNull(ToolArgumentSchema.declaredKeys("no_such_tool_at_all"))
    }

    @Test
    fun `declared keys are never empty for a registered tool`() {
        val keys = checkNotNull(ToolArgumentSchema.declaredKeys("observe_screen"))

        assertTrue(keys.isNotEmpty())
        assertTrue("display_id" in keys)
    }

    @Test
    fun `a declared name that does not exist produces no hint`() {
        val declared = checkNotNull(ToolArgumentSchema.declaredKeys("scroll_element"))

        // 用真实 schema 兜一遍：合法的调用不该冒出任何提示。
        assertEquals(emptyList<ToolArgumentHintPolicy.Hint>(), ToolArgumentHintPolicy.review(declared, declared.toList()))
    }
}
