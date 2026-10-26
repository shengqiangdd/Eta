package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.plugin.AgentPluginTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录与能力表必须同源。
 *
 * 只发 schema 不登记能力表时，[AgentToolCapabilities.project] 里的
 * `requireNotNull(find(name))` 会抛 "Missing tool requirements: xxx"，
 * 整轮工具目录构建失败 —— 界面表现为「Runtime 运行失败」，而且**每个新会话都起不来**。
 * 155 轮的 `plugin_reload` 就是这么漏的（schema 加了、能力表没加）。
 */
class AgentToolCatalogRequirementTest {

    private fun publishedNames(rootAvailable: Boolean = true): List<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = AgentToolCapabilities(rootAvailable = rootAvailable),
            localWebSearch = true,
            mode = AgentMode.DEFAULT,
        )
        return (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
    }

    @Test
    fun everyPublishedToolIsRegistered() {
        val unregistered = publishedNames().filter { AgentToolRequirements.find(it) == null }.sorted()
        assertTrue("这些工具已发布但没在 AgentToolRequirements 登记：$unregistered", unregistered.isEmpty())
    }

    @Test
    fun pluginManagementToolsAreAllRegistered() {
        val unregistered = AgentPluginTools.names.filter { AgentToolRequirements.find(it) == null }.sorted()
        assertEquals(emptyList<String>(), unregistered)
    }

    /** 没有 root 时目录要能照常建出来（只是把 REQUIRED 的工具裁掉），不能因此抛异常。 */
    @Test
    fun unprivilegedCatalogStillBuilds() {
        val names = publishedNames(rootAvailable = false)
        assertTrue(names.isNotEmpty())
        assertTrue("没有 root 时不该出现需要 root 的工具", "plugin_install" !in names)
    }

    /**
     * 反方向的保险：万一又漏登记一处，运行时应当**剔除这一个工具**，
     * 而不是让整轮目录构建抛异常（那会让每个新会话都起不来）。
     * 把「不知道的工具」筛出来的责任在 AgentToolCatalogRequirementTest 与装前门禁。
     */
    @Test
    fun unregisteredToolIsDroppedInsteadOfFailingTheCatalog() {
        val tools = JSONArray()
            .put(tool("plugin_list"))
            .put(tool("not_a_registered_tool"))
        val projected = AgentToolRequirements.project(tools, rootAvailable = true)
        assertEquals(1, projected.length())
        assertEquals("plugin_list", projected.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    private fun tool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject().put("name", name).put("parameters", JSONObject().put("type", "object")),
        )
}
