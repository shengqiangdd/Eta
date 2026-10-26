package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.data.model.McpServerSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 MCP 服务器的差异计算。
 *
 * 这里的用例守的是三个真机上很难复现、但一定会踩到的场景：
 * 1. 插件更新换了 MCP 地址 —— 只改名/改启用状态会把**旧地址继续连着**；
 * 2. 新版本不再声明某个服务器 —— 旧记录会变成指向已删插件的幽灵；
 * 3. 停用插件 —— 必须把它注册过的全部撤掉。
 *
 * 三条都属于「界面说一套、实际另一套」，所以放在纯函数层逐条钉住。
 */
class PluginMcpSyncPlanTest {

    private fun plugin(
        id: String = "demo",
        name: String = "演示插件",
        servers: List<Pair<String, String>> = listOf("demo" to "http://127.0.0.1:8099/mcp"),
    ) = PluginManifest.Plugin(
        id = id,
        name = name,
        version = "1.0.0",
        description = "",
        author = "",
        prompt = "",
        tools = emptyList(),
        mcp = servers.map { (serverName, url) ->
            PluginManifest.McpServer(
                name = serverName,
                transport = "http",
                command = "",
                args = emptyList(),
                url = url,
                env = emptyMap(),
            )
        },
        acp = emptyList(),
    )

    private fun registered(
        id: String,
        name: String,
        url: String,
        enabled: Boolean = true,
    ) = McpServerSetting(id = id, name = name, url = url, enabled = enabled)

    @Test
    fun `declared servers are added when nothing is registered`() {
        val actions = PluginMcpSyncPlan.plan(plugin(), existing = emptyList(), enabled = true)
        assertEquals(1, actions.add.size)
        assertEquals("plugin:demo:demo", actions.add.single().id)
        assertEquals("演示插件 · demo", actions.add.single().name)
        assertEquals("http://127.0.0.1:8099/mcp", actions.add.single().url)
        assertTrue(actions.update.isEmpty())
        assertTrue(actions.delete.isEmpty())
    }

    @Test
    fun `changed url is updated instead of silently kept`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(servers = listOf("demo" to "http://127.0.0.1:9000/mcp")),
            existing = listOf(registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp")),
            enabled = true,
        )
        assertTrue(actions.add.isEmpty())
        assertEquals(1, actions.update.size)
        assertEquals("http://127.0.0.1:9000/mcp", actions.update.single().url)
        assertTrue(actions.delete.isEmpty())
    }

    @Test
    fun `already in sync produces no actions`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(),
            existing = listOf(registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp")),
            enabled = true,
        )
        assertTrue(actions.isEmpty)
    }

    @Test
    fun `disabled registered server is re-enabled`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(),
            existing = listOf(
                registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp", enabled = false),
            ),
            enabled = true,
        )
        assertEquals(1, actions.update.size)
        assertTrue(actions.update.single().enabled)
    }

    @Test
    fun `servers dropped from the manifest are deleted`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(servers = listOf("main" to "http://127.0.0.1:8099/mcp")),
            existing = listOf(
                registered("plugin:demo:main", "演示插件 · main", "http://127.0.0.1:8099/mcp"),
                registered("plugin:demo:legacy", "演示插件 · legacy", "http://127.0.0.1:8098/mcp"),
            ),
            enabled = true,
        )
        assertEquals(listOf("plugin:demo:legacy"), actions.delete)
        assertTrue(actions.add.isEmpty())
        assertTrue(actions.update.isEmpty())
    }

    @Test
    fun `disabling deletes every registered server of the plugin`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(servers = emptyList()),
            existing = listOf(
                registered("plugin:demo:main", "演示插件 · main", "http://127.0.0.1:8099/mcp"),
                registered("plugin:demo:extra", "演示插件 · extra", "http://127.0.0.1:8097/mcp"),
            ),
            enabled = false,
        )
        assertEquals(
            listOf("plugin:demo:extra", "plugin:demo:main"),
            actions.delete,
        )
        assertTrue(actions.add.isEmpty())
        assertTrue(actions.update.isEmpty())
    }

    @Test
    fun `other plugins servers are left alone`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(),
            existing = listOf(
                registered("plugin:other:demo", "别的插件 · demo", "http://example.com/mcp"),
                registered("manual-server", "手填的服务器", "http://example.com/other"),
            ),
            enabled = false,
        )
        assertTrue(actions.delete.isEmpty())
        assertTrue(actions.isEmpty)
    }

    @Test
    fun `declaration without url is skipped`() {
        val actions = PluginMcpSyncPlan.plan(
            plugin(servers = listOf("broken" to "")),
            existing = emptyList(),
            enabled = true,
        )
        assertTrue(actions.add.isEmpty())
        assertTrue(actions.isEmpty)
    }

    @Test
    fun `empty manifest with nothing registered is a no-op`() {
        val actions = PluginMcpSyncPlan.plan(plugin(servers = emptyList()), emptyList(), enabled = true)
        assertTrue(actions.isEmpty)
        assertFalse(actions.delete.isNotEmpty())
    }

    @Test
    fun `orphans are only the plugin-namespaced servers of absent plugins`() {
        val existing = listOf(
            registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp"),
            // 本机实测残留：插件目录早已删掉，记录还在，会一直重连。
            registered("plugin:eta_link_demo:link_demo", "链接安装演示插件 · link_demo", "http://127.0.0.1:8099/mcp"),
            // 用户自己加的服务器：一律不碰。
            registered("manual-one", "我的服务器", "https://example.com/mcp"),
            // 形似但不是插件命名空间（少一段）。
            registered("plugin:demo", "奇怪的 id", "https://example.com/mcp"),
        )

        val orphans = PluginMcpSyncPlan.orphanedServerIds(existing, setOf("demo"))

        assertEquals(listOf("plugin:eta_link_demo:link_demo"), orphans)
    }

    @Test
    fun `no orphans when every plugin is still installed`() {
        val existing = listOf(
            registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp"),
        )
        assertTrue(PluginMcpSyncPlan.orphanedServerIds(existing, setOf("demo")).isEmpty())
    }

    @Test
    fun `disabling a plugin makes its servers orphans`() {
        // 传进来的「活跃插件」= 装着且启用；停用后它就该被撤掉。
        val existing = listOf(registered("plugin:demo:demo", "演示插件 · demo", "http://127.0.0.1:8099/mcp"))
        assertEquals(
            listOf("plugin:demo:demo"),
            PluginMcpSyncPlan.orphanedServerIds(existing, emptySet()),
        )
    }
}
