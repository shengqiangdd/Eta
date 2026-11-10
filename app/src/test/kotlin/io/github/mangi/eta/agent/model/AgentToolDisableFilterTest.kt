package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐项禁用工具：解析容错与裁剪语义。 */
class AgentToolDisableFilterTest {

    private fun tools(vararg names: String): JSONArray {
        val array = JSONArray()
        names.forEach { name -> array.put(JSONObject().put("name", name)) }
        return array
    }

    private fun namesOf(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getJSONObject(it).optString("name") }

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
    fun `解析会去掉空白与重复项`() {
        assertEquals(
            setOf("terminal", "read_file"),
            AgentToolDisableFilter.parse(" terminal , read_file ,, terminal ,"),
        )
    }
}
