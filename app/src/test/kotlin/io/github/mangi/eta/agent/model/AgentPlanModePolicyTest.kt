package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanModePolicyTest {

    private fun tool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name))

    private fun toolsOf(vararg names: String): JSONArray = JSONArray().also { array ->
        names.forEach { array.put(tool(it)) }
    }

    private fun namesOf(tools: JSONArray): List<String> = (0 until tools.length()).map { index ->
        tools.getJSONObject(index).getJSONObject("function").getString("name")
    }

    @Test
    fun readOnlyToolsSurviveAndWriteToolsDoNot() {
        for (name in listOf(
            "read_file", "get_current_context", "search_apps", "list_directory", "observe_screen",
            "device_status", "network_info", "fetch_url", "web_search", "memory_get", "todo_write", "goal",
            "ask_user", "terminal", "stat_file", "grep_files",
        )) {
            assertTrue("$name 应属于只读", AgentPlanModePolicy.isReadOnly(name))
        }
        for (name in listOf(
            "write_file", "edit_file", "set_alarm", "launch_app", "set_device_state", "memory_write",
            "terminal_job", "terminal_daemon", "plugin_install", "skills_install_from_github",
            "media_control", "create_note", "some_mcp_tool_0123",
        )) {
            assertFalse("$name 不应属于只读", AgentPlanModePolicy.isReadOnly(name))
        }
    }

    @Test
    fun filteringKeepsOrderAndDropsEverythingElse() {
        val filtered = AgentPlanModePolicy.retainReadOnly(
            toolsOf("get_current_context", "write_file", "observe_screen", "launch_app", "read_file"),
        )
        assertEquals(listOf("get_current_context", "observe_screen", "read_file"), namesOf(filtered))
    }

    @Test
    fun everyWhitelistedNameIsARealTool() {
        // 白名单靠手写，拼错一个字母就等于悄悄少了一个能力；用真实目录反查。
        val everything = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            localWebSearch = true,
        )
        val real = namesOf(everything)
        AgentPlanModePolicy.readOnlyNames.forEach { name ->
            assertTrue("白名单里的 $name 在工具目录里不存在", name in real)
        }
    }

    @Test
    fun planCatalogHidesWriteToolsButKeepsReadOnlyOnes() {
        val everything = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val planned = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            mode = AgentMode.PLAN,
        )
        val all = namesOf(everything)
        val kept = namesOf(planned)
        assertTrue(all.size > kept.size)
        assertTrue(kept.containsAll(listOf("observe_screen", "get_current_context", "read_file", "terminal")))
        for (name in listOf("write_file", "edit_file", "launch_app", "memory_write", "set_alarm")) {
            assertTrue("$name 在计划模式下不该出现", name in all && name !in kept)
        }
        // 计划模式不能凭空多出工具。
        assertTrue(all.containsAll(kept))
    }
}
