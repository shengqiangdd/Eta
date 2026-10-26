package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentContextAppToolCatalog
import io.github.mangi.eta.agent.model.AgentGestureToolCatalog
import io.github.mangi.eta.agent.model.AgentTextSystemToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具 schema 里的 display 作用域必须和真实执行路径一致，防止再次出现「文档说支持/实际落主屏」。 */
class AgentToolDisplayScopeTest {

    private fun functions(catalog: JSONArray): Map<String, JSONObject> =
        (0 until catalog.length()).associate { index ->
            val function = catalog.getJSONObject(index).getJSONObject("function")
            function.getString("name") to function
        }

    private fun properties(function: JSONObject): JSONObject =
        function.getJSONObject("parameters").getJSONObject("properties")

    @Test
    fun `launch app and wait for package expose display id`() {
        val appTools = JSONArray().also(AgentContextAppToolCatalog::appendTo)
        val textTools = JSONArray().also(AgentTextSystemToolCatalog::appendTo)

        assertTrue(properties(functions(appTools).getValue("launch_app")).has("display_id"))
        assertTrue(properties(functions(textTools).getValue("wait_for_package")).has("display_id"))
    }

    @Test
    fun `main only tools say so instead of silently acting on default display`() {
        val appTools = functions(JSONArray().also(AgentContextAppToolCatalog::appendTo))
        val textTools = functions(JSONArray().also(AgentTextSystemToolCatalog::appendTo))

        assertTrue(appTools.getValue("open_uri").getString("description").contains("只作用于主屏"))
        assertTrue(textTools.getValue("open_system_panel").getString("description").contains("仅支持主屏"))
    }

    @Test
    fun `gesture display id wording no longer claims node tools are unsupported`() {
        val gesture = functions(JSONArray().also(AgentGestureToolCatalog::appendTo))
        for (name in listOf("tap", "tap_area", "long_press", "swipe", "scroll")) {
            val description = properties(gesture.getValue(name)).getJSONObject("display_id")
                .getString("description")
            assertFalse("$name 的 display_id 文案仍在说虚拟屏不支持节点工具", description.contains("不支持节点类工具"))
        }
    }
}
