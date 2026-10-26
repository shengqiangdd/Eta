package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.plugin.PluginManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模式的行为契约 + 提示与代码校验的一致性守门。
 *
 * 第二部分是本文件的主要价值：创造模式的提示如果写了代码不接受的格式，
 * 模型会反复试错、用户只看到"装了没反应"。这类缺陷读代码看不出来，
 * 只能靠断言把两边钉在一起。
 */
class AgentModeTest {

    private fun tool(name: String): JSONObject = JSONObject().put(
        "type",
        "function",
    ).put("function", JSONObject().put("name", name))

    private fun namesOf(tools: JSONArray): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            add(tools.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }

    @Test
    fun unknownOrMissingIdFallsBackToDefault() {
        assertEquals(AgentMode.STANDARD, AgentMode.fromId(null))
        assertEquals(AgentMode.STANDARD, AgentMode.fromId(""))
        assertEquals(AgentMode.STANDARD, AgentMode.fromId("  "))
        assertEquals(AgentMode.STANDARD, AgentMode.fromId("creator-v2"))
        assertEquals(AgentMode.DEFAULT, AgentMode.fromId(null))
    }

    @Test
    fun knownIdsRoundTrip() {
        AgentMode.values().forEach { mode ->
            assertEquals(mode, AgentMode.fromId(mode.id))
            assertEquals(mode, AgentMode.fromId(" ${mode.id} "))
        }
    }

    @Test
    fun onlyPlanModeIsReadOnly() {
        assertTrue(AgentMode.PLAN.readOnlyTools)
        assertFalse(AgentMode.STANDARD.readOnlyTools)
        assertFalse(AgentMode.CREATOR.readOnlyTools)
    }

    @Test
    fun planModeKeepsOnlyReadOnlyTools() {
        val tools = JSONArray().also {
            it.put(tool("read_file"))
            it.put(tool("write_file"))
            it.put(tool("terminal"))
            it.put(tool("get_current_context"))
            it.put(tool("edit_file"))
        }
        assertEquals(
            setOf("read_file", "terminal", "get_current_context"),
            namesOf(AgentMode.PLAN.retainTools(tools)),
        )
    }

    @Test
    fun otherModesKeepEveryTool() {
        val tools = JSONArray().also {
            it.put(tool("read_file"))
            it.put(tool("write_file"))
            it.put(tool("terminal"))
        }
        assertEquals(setOf("read_file", "write_file", "terminal"), namesOf(AgentMode.STANDARD.retainTools(tools)))
        assertEquals(setOf("read_file", "write_file", "terminal"), namesOf(AgentMode.CREATOR.retainTools(tools)))
    }

    @Test
    fun creatorModeKeepsTheToolsItNeeds() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            memoryTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            mode = AgentMode.CREATOR,
        )
        val names = namesOf(tools)
        // 写文件、装插件、读技能格式：少任何一个，创造流程都走不完。
        listOf("write_file", "edit_file", "plugin_install", "plugin_list", "skills_list", "skills_read").forEach {
            assertTrue("创造模式缺少 $it", it in names)
        }
    }

    @Test
    fun standardModeHasNoModeSectionAndPlanKeepsTheOldOne() {
        assertEquals("", AgentModePrompt.section(AgentMode.STANDARD))
        val plan = AgentModePrompt.section(AgentMode.PLAN)
        assertTrue(plan.startsWith("<plan_mode>"))
        assertTrue(plan.endsWith("</plan_mode>"))
        assertTrue(plan.contains("不要自行开始执行"))
    }

    @Test
    fun creatorPromptMatchesTheRealManifestContract() {
        val prompt = AgentModePrompt.section(AgentMode.CREATOR)
        // 三条最容易写错、且写错就会被安装器拒绝的约束。
        assertTrue("缺少 id 正则", prompt.contains(PluginManifest.ID_PATTERN))
        assertTrue("缺少 manifest 文件名", prompt.contains(PluginManifest.FILE_NAME))
        assertTrue("未说明 MCP 只支持 http", prompt.contains("transport 只支持 http"))
        // 平台事实：Android 上没有 zip 命令，提示必须先说清楚，否则模型会去打包。
        assertTrue("未说明没有 zip 命令", prompt.contains("没有 zip 命令"))
        // 生效时机：新插件的工具要下一轮才出现，不能在本轮声称可用。
        assertTrue("未说明下一轮才生效", prompt.contains("下一轮"))
    }

    @Test
    fun creatorPromptOnlyReferencesToolsThatExist() {
        val prompt = AgentModePrompt.section(AgentMode.CREATOR)
        val catalog = namesOf(
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                memoryTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
            ),
        )
        val mentioned = listOf(
            "plugin_list",
            "plugin_install",
            "skills_list",
            "skills_read",
            "skills_read_resource",
        )
        mentioned.forEach { name ->
            assertTrue("提示提到 $name，但工具目录里没有", name in catalog)
            assertTrue("提示没有解释 $name 的用途", prompt.contains(name))
        }
    }

    @Test
    fun creatorPromptPointsAtTheDirectoryInstallPath() {
        val prompt = AgentModePrompt.section(AgentMode.CREATOR)
        // 目录安装是创造模式的关键能力：提示必须明确"传目录绝对路径"，而不是让模型自己猜。
        assertTrue(prompt.contains("目录绝对路径"))
        assertTrue(prompt.contains("plugin_uninstall"))
    }
}
