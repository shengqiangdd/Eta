package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐项禁用工具：解析容错与裁剪语义。
 *
 * 输入**按真实目录的结构**构造（名字在 `function.name`，不是顶层 `name`）。
 * 这条不是形式主义：早先这里用顶层 `name` 造数据，而真实目录是 OpenAI 风格，
 * 过滤器读到的永远是空串 —— 功能从未生效，而本测试全绿。
 * 端到端那层由 [AgentToolDisableEndToEndTest] 兜住。
 */
class AgentToolDisableFilterTest {

    private fun tool(name: String): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", "d"))

    private fun tools(vararg names: String): JSONArray {
        val array = JSONArray()
        names.forEach { array.put(tool(it)) }
        return array
    }

    private fun namesOf(array: JSONArray): List<String> = AgentToolDisableFilter.namesOf(array)

    @Test
    fun `namesOf 读的是 function_name 而不是顶层 name`() {
        // 顶层也放一个 name，确保取的是嵌套那个，不会因为巧合而通过。
        val array = JSONArray().put(
            JSONObject().put("name", "顶层陷阱").put("function", JSONObject().put("name", "read_file")),
        )
        assertEquals(listOf("read_file"), namesOf(array))
    }

    @Test
    fun `空集合与空串都不裁剪，且返回原对象`() {
        val original = tools("terminal", "read_file")
        assertSame(original, AgentToolDisableFilter.apply(original, emptySet()))
        assertTrue(AgentToolDisableFilter.parse(null).isEmpty())
        assertTrue(AgentToolDisableFilter.parse("").isEmpty())
        assertTrue(AgentToolDisableFilter.parse("   ").isEmpty())
    }

    @Test
    fun `只裁掉被禁用的名字，其余保持原顺序`() {
        val filtered = AgentToolDisableFilter.apply(
            tools("terminal", "read_file", "browser_click", "write_file"),
            setOf("terminal", "browser_click"),
        )
        assertEquals(listOf("read_file", "write_file"), namesOf(filtered))
    }

    @Test
    fun `禁用全部工具时得到空数组而不是 null`() {
        val filtered = AgentToolDisableFilter.apply(
            tools("terminal", "read_file"),
            setOf("terminal", "read_file"),
        )
        assertEquals(0, filtered.length())
    }

    @Test
    fun `一个都没命中时返回原对象，避免无谓复制`() {
        val original = tools("terminal", "read_file")
        assertSame(original, AgentToolDisableFilter.apply(original, setOf("browser_click")))
    }

    @Test
    fun `名字区分大小写，不会误伤同名不同大小写`() {
        val filtered = AgentToolDisableFilter.apply(tools("Terminal", "terminal"), setOf("terminal"))
        assertEquals(listOf("Terminal"), namesOf(filtered))
    }

    @Test
    fun `结构异常的工具项不会导致崩溃或误删`() {
        // 缺 function、缺 name、类型不对 —— 一律保留，宁可多给也不误删。
        val array = JSONArray()
            .put(JSONObject().put("type", "function"))
            .put(JSONObject().put("function", JSONObject()))
            .put(JSONObject().put("function", "不是对象"))
            .put("裸字符串")
            .put(tool("terminal"))
        val filtered = AgentToolDisableFilter.apply(array, setOf("terminal"))
        assertEquals(4, filtered.length())
    }

    @Test
    fun `解析会去掉空白与重复项`() {
        assertEquals(
            setOf("terminal", "read_file"),
            AgentToolDisableFilter.parse(" terminal , read_file ,, terminal ,"),
        )
    }
}
