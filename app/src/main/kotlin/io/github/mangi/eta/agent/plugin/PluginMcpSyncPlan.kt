package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.data.model.McpServerSetting

/**
 * 插件声明的 MCP 服务器 ↔ 已注册记录之间的**差异计算**。
 *
 * 为什么把这段抽成纯函数：原先它是「边查边写」，只能在装了 Room 的真机上验证，
 * 而它最容易出错的三件事都是纯逻辑 ——
 * 1. **换了地址要更新**：插件更新时 URL 变了，只改名/改启用状态会把旧地址继续连着；
 * 2. **删掉声明的要撤销**：新版本不再声明某个服务器，旧记录会变成指向不存在插件的幽灵；
 * 3. **停用要全撤**：停用后仍有服务器在连，「界面说停用、实际还在跑」。
 *
 * 差异算清楚了，写库只是照单执行。
 */
internal object PluginMcpSyncPlan {

    /** 需要注册成什么样。 */
    data class Desired(
        val id: String,
        val name: String,
        val url: String,
    )

    data class Actions(
        val add: List<Desired> = emptyList(),
        val update: List<McpServerSetting> = emptyList(),
        val delete: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = add.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    fun plan(
        plugin: PluginManifest.Plugin,
        existing: List<McpServerSetting>,
        enabled: Boolean,
    ): Actions {
        // 只碰「这个插件命名空间下」的记录，别的插件的服务器一律不参与。
        val prefix = "plugin:${plugin.id}:"
        val known = existing.filter { it.id.startsWith(prefix) }.associateBy { it.id }

        if (!enabled) {
            // 停用：把它注册过的全部撤掉（包括新版本已不再声明的那些）。
            return Actions(delete = known.keys.sorted())
        }

        val desired = plugin.mcp
            // 没地址的声明注册不了，跳过（与旧行为一致，不制造一个连不上的空条目）。
            .filter { it.url.isNotBlank() }
            .map { server ->
                Desired(
                    id = PluginManifest.mcpServerId(plugin.id, server.name),
                    name = "${plugin.name} · ${server.name}",
                    url = server.url,
                )
            }

        val add = mutableListOf<Desired>()
        val update = mutableListOf<McpServerSetting>()
        desired.forEach { want ->
            val current = known[want.id]
            when {
                current == null -> add += want
                !current.enabled || current.name != want.name || current.url != want.url ->
                    update += current.copy(name = want.name, url = want.url, enabled = true)
            }
        }

        val declaredIds = desired.map { it.id }.toSet()
        val delete = known.keys.filter { it !in declaredIds }.sorted()

        return Actions(add = add, update = update, delete = delete)
    }

    /**
     * 找出「已经不该存在」的插件服务器：命名空间 `plugin:<插件 id>:<服务器>` 里的插件
     * 既没装着、也没启用。
     *
     * 为什么要单独算：撤销只在**经过界面/工具的停用与卸载**时发生。插件目录被别的方式
     * 弄没了（手工删、覆盖安装中断、旧版本没有同步逻辑）时，记录会一直留着并持续重连 ——
     * 本机实测就有一条 `plugin:eta_link_demo:link_demo` 指向早已删掉的插件。
     *
     * 只认 `plugin:` 前缀：用户自己加的服务器一律不碰。
     */
    fun orphanedServerIds(existing: List<McpServerSetting>, activePluginIds: Set<String>): List<String> =
        existing.mapNotNull { server ->
            val parts = server.id.split(':')
            if (parts.size < 3 || parts[0] != PLUGIN_ID_PREFIX) return@mapNotNull null
            if (parts[1] in activePluginIds) null else server.id
        }.sorted()

    /** `plugin:<id>:<name>` 的前缀，与 [PluginManifest.mcpServerId] 保持一致。 */
    const val PLUGIN_ID_PREFIX = "plugin"
}
