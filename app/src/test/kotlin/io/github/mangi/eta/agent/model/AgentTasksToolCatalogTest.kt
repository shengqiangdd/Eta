package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTasksToolCatalogTest {

    private fun schema(): JSONObject {
        val tools = JSONArray()
        AgentTasksToolCatalog.appendTo(tools)
        assertEquals(1, tools.length())
        return tools.getJSONObject(0).getJSONObject("function")
    }

    @Test
    fun schemaDeclaresStatusFilterAndBoundedLimit() {
        val function = schema()
        assertEquals("tasks", function.getString("name"))
        assertTrue(function.getString("description").contains("resume"))

        val properties = function.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(
            setOf("running", "completed", "failed", "cancelled", "stalled", "all"),
            properties.getJSONObject("status").getJSONArray("enum").toStringList().toSet(),
        )
        assertEquals(1, properties.getJSONObject("limit").getInt("minimum"))
        assertEquals(
            AgentTaskLedger.MAX_ENTRIES,
            properties.getJSONObject("limit").getInt("maximum"),
        )
        // 两个参数都可省略：最常见的用法是"看看现在都有谁"。
        assertFalse(function.getJSONObject("parameters").has("required"))
    }

    @Test
    fun defaultsToEveryStatusAndADefaultLimit() {
        val query = AgentTasksToolCatalog.parse(JSONObject())
        assertNull(query.status)
        assertEquals(AgentTasksToolCatalog.DEFAULT_LIMIT, query.limit)
    }

    @Test
    fun acceptsStatusAndClampsLimit() {
        val running = AgentTasksToolCatalog.parse(
            JSONObject().put("status", "running").put("limit", 3),
        )
        assertEquals(AgentTaskStatus.RUNNING, running.status)
        assertEquals(3, running.limit)

        val all = AgentTasksToolCatalog.parse(JSONObject().put("status", "all"))
        assertNull(all.status)

        // 自动收尾要能单独筛：模型靠这个区分「用户叫停」与「卡住该重派」。
        val stalled = AgentTasksToolCatalog.parse(JSONObject().put("status", "stalled"))
        assertEquals(AgentTaskStatus.STALLED, stalled.status)

        val huge = AgentTasksToolCatalog.parse(JSONObject().put("limit", 9_999))
        assertEquals(AgentTaskLedger.MAX_ENTRIES, huge.limit)
    }

    @Test
    fun rejectsUnknownFieldsAndStatuses() {
        assertThrows { AgentTasksToolCatalog.parse(JSONObject().put("stat", "running")) }
        assertThrows { AgentTasksToolCatalog.parse(JSONObject().put("status", "done")) }
        assertThrows { AgentTasksToolCatalog.parse(JSONObject().put("limit", 0)) }
        assertThrows { AgentTasksToolCatalog.parse(JSONObject().put("limit", -1)) }
    }

    /** 计划模式只留"看"的工具；台账是只读的，必须留着，否则调研时看不见已派出的子代理。 */
    @Test
    fun planModeKeepsTasks() {
        val tools = JSONArray()
        AgentTasksToolCatalog.appendTo(tools)

        val kept = AgentPlanModePolicy.retainReadOnly(tools)
        assertEquals(1, kept.length())
        assertEquals(
            AgentTasksToolCatalog.NAME,
            kept.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun catalogExposesExactlyOneTasksTool() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertEquals(1, names.count { it == AgentTasksToolCatalog.NAME })
        // 未登记的工具会被能力合同静默剔除；这里直接对着合同点名。
        assertTrue(AgentToolRequirements.toolNames.contains(AgentTasksToolCatalog.NAME))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { index -> getString(index) }
}
