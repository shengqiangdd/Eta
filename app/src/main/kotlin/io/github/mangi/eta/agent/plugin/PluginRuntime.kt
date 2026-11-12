package io.github.mangi.eta.agent.plugin

import android.content.Context
import androidx.core.content.edit
import io.github.mangi.eta.agent.acp.AcpClient
import io.github.mangi.eta.core.AgentLogger
import java.io.File

/**
 * 插件运行时：持有已安装插件、生成追加的工具 schema 与提示词。
 *
 * 与 Skill 系统的分工：
 * - Skill 给模型"做事的方法"（SKILL.md 正文按需读取）；
 * - Plugin 给系统"新增能力"（MCP 服务器、ACP 代理、提示词扩展）。
 * 两者安装目录、manifest、生命周期各自独立，互不影响。
 */
internal class PluginRuntime(
    filesDir: File,
    private val enabledState: PluginEnabledState,
    private val logger: AgentLogger,
) {
    /**
     * 生产入口：插件目录在 App 私有 files 下，启用状态落在 SharedPreferences。
     *
     * 另有一个不依赖 Android Context 的构造（上面那个）：Robolectric 在
     * Linux/aarch64 上起不来，而「回滚后来源记录是否跟着改」这类逻辑必须能被单测守住。
     */
    constructor(context: Context, logger: AgentLogger) : this(
        context.filesDir,
        SharedPreferencesPluginState(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        logger,
    )

    private val pluginsRoot = File(filesDir, "plugins")

    /** 历史留底（覆盖安装前写入，保留多版），回滚与详情里的「可回滚」都读它。 */
    private val historyRoot = File(filesDir, "plugin-history")
    val history = PluginHistoryStore(historyRoot)
    val installer = PluginPackageInstaller(pluginsRoot, historyRoot = historyRoot)

    /** 插件「从哪来」的记录（链接 / 本地路径），用于界面展示与「从来源更新」。 */
    val sources = PluginSourceStore(pluginsRoot)

    fun list(): List<PluginManifest.Plugin> =
        installer.installedPlugins().filter { plugin -> isEnabled(plugin.id) }

    fun allInstalled(): List<PluginManifest.Plugin> = installer.installedPlugins()

    fun setEnabled(pluginId: String, enabled: Boolean): Boolean {
        val exists = allInstalled().any { it.id == pluginId }
        if (!exists) return false
        enabledState.setEnabled(pluginId, enabled)
        return true
    }

    fun isEnabled(pluginId: String): Boolean = enabledState.isEnabled(pluginId)

    /**
     * 追加进系统提示的插件说明。
     *
     * 这里**只写说明，不把插件声明的 `tools` 注册成可调用工具**。
     * 原先有一个 `appendToolSchemas`，把 `tools` 塞进模型工具目录，但那些名字没有任何执行器：
     * 模型一旦真的调用，只会落到 `UNKNOWN_TOOL`（「未知设备工具」）。
     * 也就是说它给模型的是一个**假的可调用承诺**，而真正能执行的是同一插件里的
     * `mcp`（HTTP MCP 服务器）与 `acp`（编码代理）。所以改为在提示里如实列出
     * 「这个插件声明了哪些能力、要通过什么去用」，并删掉那段没有调用点的代码。
     */
    fun promptSection(): String {
        val plugins = list()
        if (plugins.isEmpty()) return ""
        return buildString {
            append("\n\n## 已安装插件\n")
            plugins.forEach { plugin ->
                append("- ").append(PluginManifest.summarize(plugin)).append('\n')
                if (plugin.acp.isNotEmpty()) {
                    plugin.acp.forEach { agent ->
                        append("  - ACP 代理 `").append(agent.id)
                            .append("`：").append(agent.title)
                            .append("（").append(agent.executable).append("）\n")
                    }
                }
                if (plugin.tools.isNotEmpty()) {
                    append("  - 能力声明（**不是可直接调用的工具**；请通过该插件的 MCP 工具或 ACP 代理完成）：")
                        .append(plugin.tools.joinToString("、") { it.name })
                        .append('\n')
                }
                if (plugin.mcp.isNotEmpty()) {
                    plugin.mcp.forEach { server ->
                        append("  - MCP 服务器 `").append(server.name)
                            .append("`：").append(server.transport).append('\n')
                    }
                }
                if (plugin.prompt.isNotBlank()) {
                    append(plugin.prompt.trim()).append('\n')
                }
            }
        }.trim()
    }

    fun installZip(
        openStream: () -> java.io.InputStream,
        replaceExisting: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
        source: PluginSourceStore.Source? = null,
    ): PluginPackageInstaller.Result = record(
        installer.installLocalZip(
            openStream = openStream,
            replaceExisting = replaceExisting,
            expectedReplacementId = expectedReplacementId,
            expectedArchiveSha256 = expectedArchiveSha256,
        ),
        source,
    )

    /**
     * 按路径安装：目录走目录安装，文件走 zip。
     *
     * 目录安装是给创造模式用的——Android 上没有 zip 命令，Agent 只能写文件；
     * 若强制先打包，「造插件」就卡在最后一步。两条路径共用同一套事务与日志。
     */
    fun installPath(
        path: java.io.File,
        replaceExisting: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ): PluginPackageInstaller.Result {
        val isDir = path.isDirectory
        return record(
            if (isDir) {
                installer.installLocalDir(
                    sourceDir = path,
                    replaceExisting = replaceExisting,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            } else {
                installer.installLocalZip(
                    openStream = { path.inputStream() },
                    replaceExisting = replaceExisting,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            },
            // 本地路径安装也记来源：否则界面只能显示「已安装」，事后无法回答「谁给的」。
            PluginSourceStore.Source(
                kind = PluginSourceStore.KIND_PATH,
                location = path.absolutePath,
                host = "",
                sha256 = "",
                version = "",
                installedAtMillis = 0L,
            ),
            // 目录安装算出来的是「目录内容摘要」，不是用户手里那份包的 SHA-256。
            // 写进「包摘要」等于给用户一个对不上的校验值，所以只对 zip 文件记录。
            archiveDigest = !isDir,
        )
    }

    /**
     * @param archiveDigest 本次安装是否有真实的「包摘要」。
     *   目录安装与回滚用的都是解包后的目录，没有原始 zip 可算，传 false 让来源记录留空，
     *   界面才会如实显示「未记录」，而不是拿目录摘要冒充包摘要。
     */
    private fun record(
        result: PluginPackageInstaller.Result,
        source: PluginSourceStore.Source? = null,
        archiveDigest: Boolean = true,
    ): PluginPackageInstaller.Result {
        if (result is PluginPackageInstaller.Result.Installed) {
            // 新装与覆盖安装都回到启用状态：覆盖安装通常就是为了让新版本跑起来。
            enabledState.setEnabled(result.plugin.id, true)
            // 来源要在安装成功后写：安装失败时留下来源记录会让界面以为这个插件还在。
            if (source != null) {
                sources.record(
                    result.plugin.id,
                    source.copy(
                        sha256 = if (archiveDigest) result.sha256 else "",
                        version = result.plugin.version,
                        installedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            logger.info(
                "Agent plugin action=install outcome=completed id=${result.plugin.id} " +
                    "version=${result.plugin.version} already=${result.alreadyInstalled} " +
                    "tools=${result.plugin.tools.size} mcp=${result.plugin.mcp.size} acp=${result.plugin.acp.size}"
            )
        }
        return result
    }

    /**
     * 回滚到指定留底版本：用留底目录重新走一次目录安装。
     *
     * 复用安装器而不是直接换目录，是为了继承同一套校验与事务；
     * 副作用是当前版本也会被留底 —— 于是可以来回切，不会"回滚一次就锁死"。
     *
     * 来源记录要跟着改：地址不变（还是同一个来源），但版本与安装时间必须变成回滚后的，
     * 包摘要留空 —— 留底目录里没有原始 zip，拿目录摘要冒充会让详情显示一个对不上的校验值。
     * （真实缺陷：回滚到 v1.0.0 后，详情仍显示 v1.1.0 的包摘要与安装时间。）
     */
    fun rollback(pluginId: String, version: String? = null): PluginPackageInstaller.Result {
        val wanted = version?.trim().orEmpty()
        val entry = if (wanted.isEmpty()) {
            history.get(pluginId)
        } else {
            history.getVersion(pluginId, wanted)
        } ?: return PluginPackageInstaller.Result.Failed(
            "PLUGIN_NO_BACKUP",
            if (wanted.isEmpty()) "没有可用的上一版备份" else "没有 v$wanted 的备份",
        )
        return record(
            installer.installLocalDir(
                sourceDir = entry.payloadDir,
                replaceExisting = true,
                expectedReplacementId = pluginId,
                expectedArchiveSha256 = null,
            ),
            // 没有来源记录的旧插件保持没有：不凭空造一条。
            source = sources.get(pluginId),
            archiveDigest = false,
        )
    }

    fun uninstall(pluginId: String): Boolean {
        val removed = installer.uninstall(pluginId)
        if (removed) {
            enabledState.forget(pluginId)
            sources.forget(pluginId)
        }
        logger.info("Agent plugin action=uninstall id=$pluginId outcome=$removed")
        return removed
    }

    /**
     * 汇总已启用插件声明的 ACP 代理，供 AcpClient 注册。
     *
     * 命名空间：`<pluginId>:<agentId>`。这样两个插件可以各自声明 `codex`
     * 而互不冲突，也让"这个代理来自哪个插件"在日志与 `acp_agent_list` 里一眼可查。
     */
    fun externalAcpAgents(): List<AcpClient.ExternalAgent> =
        list().flatMap { plugin ->
            plugin.acp.map { agent ->
                AcpClient.ExternalAgent(
                    id = PluginManifest.acpAgentId(plugin.id, agent.id),
                    title = "[${plugin.name}] ${agent.title}",
                    executable = agent.executable,
                    credentialEnvKeys = agent.credentialEnvKeys,
                )
            }
        }

    private companion object {
        const val PREFS_NAME = "eta_plugin_state"
    }
}

/** 插件启用状态的存放位置。 */
internal interface PluginEnabledState {
    fun isEnabled(pluginId: String): Boolean
    fun setEnabled(pluginId: String, enabled: Boolean)
    fun forget(pluginId: String)
}

/**
 * 启用状态必须落盘。
 *
 * 原先只存在内存里的 `ConcurrentHashMap`：用户停用一个插件，App 一重启它就又启用了 ——
 * 界面上看起来「停用成功了」，实际只对本次运行有效。停用往往是因为插件出了问题，
 * 「重启就复活」会让人以为是自己没点对。
 *
 * 抽成接口还有一个原因：Robolectric 在 Linux/aarch64 上起不来，
 * 而「回滚后来源记录要不要改」这类逻辑必须能被纯 JVM 单测守住。
 */
internal class SharedPreferencesPluginState(
    private val prefs: android.content.SharedPreferences,
) : PluginEnabledState {
    override fun isEnabled(pluginId: String): Boolean = prefs.getBoolean(key(pluginId), true)

    override fun setEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit { putBoolean(key(pluginId), enabled) }
    }

    override fun forget(pluginId: String) {
        prefs.edit { remove(key(pluginId)) }
    }

    private fun key(pluginId: String): String = ENABLED_PREFIX + pluginId

    private companion object {
        const val ENABLED_PREFIX = "enabled_"
    }
}
