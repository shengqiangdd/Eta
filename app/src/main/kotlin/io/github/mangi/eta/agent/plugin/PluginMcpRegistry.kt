package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

/**
 * 插件声明的 MCP 服务器 → 内置 MCP 管理器的注册与撤销。
 *
 * 为什么抽成独立对象：原先这段逻辑是 `AgentLocalTools` 的私有方法，只有 Agent 侧的
 * `plugin_install` / `plugin_enable` / `plugin_uninstall` 能调到。而**界面上的导入、链接安装、
 * 开关与卸载**走的是另一条路径（`PluginRuntime` / `AgentPluginsStore`），于是：
 * - 从界面装完插件，它声明的 MCP 服务器**根本没注册**（真机实测：`mcp_servers` 表是空的），
 *   界面显示「1 个 MCP 服务器」，实际一个都连不上；
 * - 停用或卸载插件后，它注册过的 MCP 服务器仍留在列表里继续连接。
 *
 * 现在两条路径共用同一份同步逻辑，差异计算在 [PluginMcpSyncPlan]（纯函数、可单测）。
 */
internal object PluginMcpRegistry {

    /**
     * 按启用状态同步该插件声明的全部 MCP 服务器。
     *
     * 失败不回滚（调用方只记录）：MCP 注册不成功不应让插件安装/切换整体失败。
     * 返回失败原因列表，供工具结果与日志展示。
     */
    suspend fun sync(plugin: PluginManifest.Plugin, enabled: Boolean): List<String> {
        val existing = runCatching { McpServerRepository.servers() }.getOrElse { error ->
            return listOf("读取已注册服务器失败：${error.javaClass.simpleName}")
        }
        val actions = PluginMcpSyncPlan.plan(plugin, existing, enabled)
        if (actions.isEmpty) return emptyList()

        val problems = mutableListOf<String>()
        actions.add.forEach { want ->
            runCatching {
                McpServerRepository.add(
                    McpServerSetting(id = want.id, name = want.name, url = want.url),
                    bearerToken = "",
                )
            }.onFailure { error -> problems += "${serverName(want.id)}: ${error.javaClass.simpleName}" }
        }
        actions.update.forEach { server ->
            runCatching { McpServerRepository.update(server) }
                .onFailure { error -> problems += "${serverName(server.id)}: ${error.javaClass.simpleName}" }
        }
        actions.delete.forEach { serverId ->
            runCatching { McpServerRepository.delete(serverId) }
                .onFailure { error -> problems += "${serverName(serverId)}: ${error.javaClass.simpleName}" }
        }
        return problems
    }

    /** 从 `plugin:<插件>:<服务器>` 里取出服务器名，用于失败提示。 */
    private fun serverName(serverId: String): String = serverId.substringAfterLast(':')

    /**
     * 清掉指向「已经不存在的插件」的服务器记录。
     *
     * 触发点：插件页刷新。它读的就是"盘上现在装着什么"，顺手把对不上的注册撤掉，
     * 和来源记录、留底目录的处理方式一致 —— 界面看到的不该是一个已经不存在的插件。
     *
     * @param activePluginIds 仍然装着且启用的插件 id。
     * @return 失败原因（成功则为空）。
     */
    suspend fun pruneOrphans(activePluginIds: Set<String>): List<String> {
        val existing = runCatching { McpServerRepository.servers() }.getOrElse { error ->
            return listOf("读取已注册服务器失败：${error.javaClass.simpleName}")
        }
        val orphans = PluginMcpSyncPlan.orphanedServerIds(existing, activePluginIds)
        if (orphans.isEmpty()) return emptyList()
        val problems = mutableListOf<String>()
        orphans.forEach { serverId ->
            runCatching { McpServerRepository.delete(serverId) }
                .onFailure { problems += "${serverName(serverId)}: ${it.javaClass.simpleName}" }
        }
        return problems
    }
}
