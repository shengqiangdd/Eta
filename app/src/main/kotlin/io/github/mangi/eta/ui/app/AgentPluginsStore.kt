package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.plugin.PluginLinkDownloader
import io.github.mangi.eta.agent.plugin.PluginManifest
import io.github.mangi.eta.agent.plugin.PluginMcpRegistry
import io.github.mangi.eta.agent.plugin.PluginPackageInstaller
import io.github.mangi.eta.agent.plugin.PluginRuntime
import io.github.mangi.eta.agent.plugin.PluginSourceStore
import io.github.mangi.eta.agent.plugin.PluginVersion
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.model.AgentPluginsUiState
import io.github.mangi.eta.ui.model.PluginDetails
import io.github.mangi.eta.ui.model.PluginItemUi
import io.github.mangi.eta.ui.model.PluginLinkPreviewUi
import io.github.mangi.eta.ui.model.PluginNoticeUi
import io.github.mangi.eta.ui.model.PluginRollbackUi
import io.github.mangi.eta.ui.model.PluginUpdateStatusUi
import io.github.mangi.eta.ui.model.PluginUpdateSummary
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理页状态。
 *
 * 直接持有 [PluginRuntime]：插件状态本来就在文件系统（`files/plugins`）与 SharedPreferences
 * 里，Agent 会话侧用的是另一个实例，两边读的是同一份真实状态，不需要额外的同步层。
 */
internal class AgentPluginsStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val runtime = PluginRuntime(appContext, AndroidAgentLogger)
    private val linkDownloader = PluginLinkDownloader(File(appContext.cacheDir, "plugin-links"))
    private var noticeSequence = 0L

    /** 链接安装第一步下载到的暂存文件；用户确认前一直留着，取消就删。 */
    private var pendingLinkFile: File? = null

    var pluginsState by mutableStateOf(AgentPluginsUiState(isLoading = true))
        private set

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val items = runCatching {
                val installed = runtime.allInstalled()
                // 先清掉「插件目录里已经不存在」的来源记录：界面不该给一个已删插件显示来源，
                // 更不该允许「从来源更新」一个不存在的目标。
                //
                // 顺手做一次自愈：记录里的摘要/安装时间属于**某一版**，版本对不上时
                // （典型：回滚到旧版）它们对当前装着的这一版并不成立，改写成「未记录」，
                // 否则详情会拿上一版的校验值冒充当前包。
                val sources = runCatching {
                    runtime.sources.reconcile(installed.associate { it.id to it.version })
                }.getOrDefault(emptyMap())
                // 插件已被删掉时，它的留底也应一并清掉，否则白占空间。
                runCatching { runtime.history.prune(installed.map { it.id }.toSet()) }
                // 指向「已经不存在的插件」的 MCP 服务器会一直重连，也占着工具目录。
                // 这一页读的就是盘上装着什么，顺手把对不上的注册撤掉（与来源、留底同一套思路）。
                runCatching {
                    PluginMcpRegistry.pruneOrphans(
                        installed.filter { plugin -> runtime.isEnabled(plugin.id) }
                            .map { plugin -> plugin.id }
                            .toSet(),
                    )
                }.onSuccess { problems ->
                    if (problems.isNotEmpty()) {
                        AndroidAgentLogger.warn(
                            "Agent plugin mcp prune failed: ${problems.joinToString("；").take(200)}",
                        )
                    }
                }
                installed.map { plugin ->
                    val source = sources[plugin.id]
                    val backups = runCatching { runtime.history.list(plugin.id) }
                        .getOrDefault(emptyList())
                    val digestApplies = source == null ||
                        PluginDetails.recordAppliesToVersion(source.version, plugin.version)
                    PluginItemUi(
                        id = plugin.id,
                        name = plugin.name,
                        version = plugin.version,
                        description = plugin.description,
                        author = plugin.author,
                        enabled = runtime.isEnabled(plugin.id),
                        toolCount = plugin.tools.size,
                        mcpServers = plugin.mcp.map { it.name },
                        acpAgents = plugin.acp.map { it.title },
                        hasPrompt = plugin.prompt.isNotBlank(),
                        sourceLabel = source?.let(::describeSource),
                        canUpdateFromSource = source?.isLink == true && source.location.isNotBlank(),
                        // 详情页要回答「什么时候装的、装的是哪份包」，这两个值只在来源记录里。
                        // 记录属于另一版时（回滚过的插件），摘要与时间都不再对应当前这一版，
                        // 按「未记录」显示 —— reconcile 会把记录改对，这里是不依赖写盘的兜底。
                        installedAtMillis = if (digestApplies) source?.installedAtMillis ?: 0L else 0L,
                        sha256 = if (digestApplies) source?.sha256.orEmpty() else "",
                        // 主机名不足以核实来源（同一主机下可能有很多包），详情里给完整地址。
                        sourceLocation = source?.location.orEmpty(),
                        sourceHost = source?.host.orEmpty(),
                        // 全部留底一次读出：界面要能列出多个可回滚版本，而不是只给「上一版」。
                        rollbackVersions = backups
                            .filter { PluginDetails.canRollback(plugin.version, it.version) }
                            .map { PluginRollbackUi(it.version, it.backedUpAtMillis) },
                        toolNames = plugin.tools.map { it.name },
                    )
                }
            }.getOrElse { error ->
                withContext(Dispatchers.Main) {
                    pluginsState = pluginsState.copy(
                        isLoading = false,
                        notice = pluginsState.notice ?: notice(
                            title = appContext.getString(R.string.plugins_read_failed_title),
                            message = appContext.getString(R.string.plugins_read_failed_message),
                            isError = true,
                        ),
                    )
                }
                AndroidAgentLogger.warn(
                    "Agent plugin list unavailable: type=${error.javaClass.simpleName}",
                )
                return@launch
            }
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(plugins = items, isLoading = false)
            }
        }
    }

    fun toggle(pluginId: String, enabled: Boolean) {
        if (pluginsState.isBusy) return
        pluginsState = pluginsState.copy(busyPluginId = pluginId, notice = null)
        scope.launch(Dispatchers.IO) {
            val plugin = runtime.allInstalled().firstOrNull { it.id == pluginId }
            val succeeded = runCatching { runtime.setEnabled(pluginId, enabled) }.getOrDefault(false)
            // 停用要连带撤销它注册过的 MCP 服务器：只改开关的话，服务器条目还在、连接还在，
            // 界面显示"已停用"而实际仍占着工具目录（与 Agent 侧 plugin_enable 必须一致）。
            val mcpProblems = if (succeeded && plugin != null) {
                runCatching { PluginMcpRegistry.sync(plugin, enabled) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    busyPluginId = null,
                    notice = when {
                        !succeeded -> notice(
                            title = appContext.getString(R.string.plugins_toggle_failed_title),
                            message = appContext.getString(R.string.plugins_toggle_failed_message),
                            isError = true,
                        )
                        mcpProblems.isNotEmpty() -> notice(
                            title = appContext.getString(R.string.plugins_mcp_sync_failed_title),
                            message = mcpProblems.joinToString("；").take(300),
                            isError = true,
                        )
                        else -> notice(
                            title = appContext.getString(
                                if (enabled) R.string.plugins_enabled_title else R.string.plugins_disabled_title,
                            ),
                            message = appContext.getString(R.string.plugins_restart_hint),
                            isError = false,
                        )
                    },
                )
            }
            refresh()
        }
    }

    fun uninstall(pluginId: String) {
        if (pluginsState.isBusy) return
        val plugin = pluginsState.plugins.firstOrNull { it.id == pluginId } ?: return
        pluginsState = pluginsState.copy(busyPluginId = pluginId, notice = null)
        scope.launch(Dispatchers.IO) {
            val declared = runtime.allInstalled().firstOrNull { it.id == pluginId }
            val succeeded = runCatching { runtime.uninstall(pluginId) }.getOrDefault(false)
            // 插件没了，上一版留底也不该继续躺在磁盘上。
            if (succeeded) runCatching { runtime.history.forget(pluginId) }
            // 卸载后必须撤销它注册过的 MCP 服务器，否则列表里会留下指向已删插件的"幽灵服务器"。
            val mcpProblems = if (succeeded && declared != null) {
                runCatching { PluginMcpRegistry.sync(declared, false) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    busyPluginId = null,
                    // 插件已删：它的检查结果没有意义了。
                    updateStatuses = pluginsState.updateStatuses - pluginId,
                    notice = when {
                        !succeeded -> notice(
                            title = appContext.getString(R.string.plugins_uninstall_failed_title),
                            message = appContext.getString(R.string.plugins_uninstall_failed_message),
                            isError = true,
                        )
                        mcpProblems.isNotEmpty() -> notice(
                            title = appContext.getString(R.string.plugins_mcp_sync_failed_title),
                            message = mcpProblems.joinToString("；").take(300),
                            isError = true,
                        )
                        else -> notice(
                            title = appContext.getString(R.string.plugins_uninstalled_title),
                            message = appContext.getString(
                                R.string.plugins_uninstalled_message,
                                plugin.name,
                            ),
                            isError = false,
                        )
                    },
                )
            }
            refresh()
        }
    }

    /** 应用内选择器给出的路径：虚拟屏上系统文件选择器不可用，所以导入得自己实现。 */
    fun importZipFile(path: String) {
        importZipFrom(openStream = { File(path).inputStream() }, location = path)
    }

    private fun importZipFrom(openStream: () -> InputStream, location: String) {
        if (pluginsState.isImporting) return
        pluginsState = pluginsState.copy(isImporting = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                runtime.installZip(
                    openStream = openStream,
                    replaceExisting = false,
                    expectedReplacementId = null,
                    expectedArchiveSha256 = null,
                    // 本地导入也记来源：事后能回答「这个插件从哪来的」。
                    source = PluginSourceStore.Source(
                        kind = PluginSourceStore.KIND_PATH,
                        location = location,
                        host = "",
                        sha256 = "",
                        version = "",
                        installedAtMillis = 0L,
                    ),
                )
            }.getOrElse { error ->
                AndroidAgentLogger.warn("Agent plugin install failed: type=${error.javaClass.simpleName}")
                null
            }
            val mcpDetail = syncMcpAfterInstall(result)
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    isImporting = false,
                    // 装完版本就可能变了：上一轮检查更新的结论立即过期。
                    updateStatuses = emptyMap(),
                    notice = installNotice(result, updating = false, mcpDetail = mcpDetail),
                )
            }
            refresh()
        }
    }

    fun dismissNotice() {
        pluginsState = pluginsState.copy(notice = null)
    }

    /**
     * 链接安装第一步：下载 + 解析清单，**只产生预览**，不碰插件目录。
     *
     * 参考 DSHA 的两段式安装（inspect → 预览 → commit）：链接是外部内容，
     * 装进去的插件会往 Agent 里注入 MCP 服务器与提示词，值得让用户先看一眼再确认。
     */
    fun prepareLinkInstall(rawUrl: String, updateTargetId: String? = null) {
        if (pluginsState.isFetchingLink || pluginsState.isImporting) return
        discardPendingLink()
        pluginsState = pluginsState.copy(isFetchingLink = true, linkPreview = null, notice = null)
        scope.launch(Dispatchers.IO) {
            // 更新模式要先知道「当前装的是哪个版本」，并且确认目标插件还在：
            // 目标已被删掉时覆盖安装会失败在 ID 不匹配上，用户看不懂原因。
            val currentVersion = if (updateTargetId != null) {
                runCatching {
                    runtime.allInstalled().firstOrNull { it.id == updateTargetId }?.version
                }.getOrNull()
            } else {
                null
            }
            if (updateTargetId != null && currentVersion == null) {
                withContext(Dispatchers.Main) {
                    pluginsState = pluginsState.copy(
                        isFetchingLink = false,
                        notice = notice(
                            title = appContext.getString(R.string.plugins_update_failed_title),
                            message = appContext.getString(R.string.plugins_update_target_missing),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val outcome = linkDownloader.download(rawUrl)
            if (outcome is PluginLinkDownloader.Outcome.Failed) {
                withContext(Dispatchers.Main) {
                    pluginsState = pluginsState.copy(
                        isFetchingLink = false,
                        notice = notice(
                            title = appContext.getString(R.string.plugins_link_failed_title),
                            message = "${outcome.code}：${outcome.message}".take(300),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val downloaded = outcome as PluginLinkDownloader.Outcome.Downloaded
            val inspection = runCatching {
                runtime.installer.inspectZip { downloaded.file.inputStream() }
            }.getOrNull()
            val preview = when (inspection) {
                is PluginPackageInstaller.Inspection.Ready -> {
                    val upToDate = updateTargetId != null &&
                        inspection.plugin.version == currentVersion
                    val conflict = when {
                        // 同一个链接现在给的是另一个插件：不能当成「更新」覆盖过去。
                        updateTargetId != null && inspection.plugin.id != updateTargetId ->
                            appContext.getString(
                                R.string.plugins_update_id_mismatch,
                                updateTargetId,
                                inspection.plugin.id,
                            )
                        upToDate -> appContext.getString(
                            R.string.plugins_up_to_date_message,
                            inspection.plugin.name,
                            currentVersion,
                        )
                        else -> null
                    }
                    if (conflict != null) {
                        linkDownloader.cleanup(downloaded.file)
                        withContext(Dispatchers.Main) {
                            pluginsState = pluginsState.copy(
                                isFetchingLink = false,
                                notice = notice(
                                    title = appContext.getString(
                                        if (upToDate) {
                                            R.string.plugins_up_to_date_title
                                        } else {
                                            R.string.plugins_update_failed_title
                                        },
                                    ),
                                    message = conflict,
                                    isError = !upToDate,
                                ),
                            )
                        }
                        return@launch
                    }
                    pendingLinkFile = downloaded.file
                    PluginLinkPreviewUi(
                        token = UUID.randomUUID().toString(),
                        url = downloaded.url,
                        host = downloaded.host,
                        secure = downloaded.secure,
                        name = inspection.plugin.name,
                        version = inspection.plugin.version,
                        author = inspection.plugin.author,
                        description = inspection.plugin.description,
                        toolCount = inspection.plugin.tools.size,
                        mcpCount = inspection.plugin.mcp.size,
                        acpCount = inspection.plugin.acp.size,
                        hasPrompt = inspection.plugin.prompt.isNotBlank(),
                        bytes = downloaded.bytes,
                        sha256 = downloaded.sha256,
                        updateTargetId = updateTargetId,
                        currentVersion = currentVersion ?: "",
                    )
                }
                else -> {
                    linkDownloader.cleanup(downloaded.file)
                    val rejected = inspection as? PluginPackageInstaller.Inspection.Rejected
                    withContext(Dispatchers.Main) {
                        pluginsState = pluginsState.copy(
                            isFetchingLink = false,
                            notice = notice(
                                title = appContext.getString(R.string.plugins_link_not_plugin_title),
                                message = if (rejected != null) {
                                    "${rejected.code}：${rejected.message}".take(300)
                                } else {
                                    appContext.getString(R.string.plugins_link_not_plugin_message)
                                },
                                isError = true,
                            ),
                        )
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(isFetchingLink = false, linkPreview = preview)
            }
        }
    }

    /** 链接安装第二步：装预览里那一份暂存文件（同一份字节，不重新下载）。 */
    fun confirmLinkInstall() {
        val preview = pluginsState.linkPreview ?: return
        if (pluginsState.isImporting) return
        val file = pendingLinkFile ?: return
        // 更新模式必须带 expectedReplacementId：装错目标会把别的插件覆盖掉。
        val updatingId = preview.updateTargetId
        pluginsState = pluginsState.copy(isImporting = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                runtime.installZip(
                    openStream = { file.inputStream() },
                    replaceExisting = updatingId != null,
                    expectedReplacementId = updatingId,
                    expectedArchiveSha256 = if (updatingId != null) preview.sha256 else null,
                    // 记下来源：界面要能显示「从哪来」，也才有「从来源更新」这个入口。
                    source = PluginSourceStore.Source(
                        kind = PluginSourceStore.KIND_LINK,
                        location = preview.url,
                        host = preview.host,
                        sha256 = preview.sha256,
                        version = preview.version,
                        installedAtMillis = 0L,
                    ),
                )
            }.getOrNull()
            discardPendingLink()
            val mcpDetail = syncMcpAfterInstall(result)
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    isImporting = false,
                    linkPreview = null,
                    // 更新成功后不能继续显示「可更新到 vX」：那个版本已经装上了。
                    updateStatuses = emptyMap(),
                    notice = installNotice(
                        result = result,
                        updating = updatingId != null,
                        mcpDetail = mcpDetail,
                    ),
                )
            }
            refresh()
        }
    }

    /**
     * 「从来源更新」：把记录的链接重新走一遍下载 + 预览。
     *
     * 只对「链接安装」的插件可用。本地路径导入的来源（content:// 或绝对路径）大多已经被系统清掉，
     * 重读同一路径要么失败、要么读到别的文件 —— 那比「不能更新」更危险。
     */
    fun rollback(pluginId: String, version: String? = null) {
        if (pluginsState.isBusy) return
        val plugin = pluginsState.plugins.firstOrNull { it.id == pluginId } ?: return
        pluginsState = pluginsState.copy(busyPluginId = pluginId, notice = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { runtime.rollback(pluginId, version) }.getOrNull()
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    busyPluginId = null,
                    updateStatuses = pluginsState.updateStatuses - pluginId,
                    notice = when (result) {
                        is PluginPackageInstaller.Result.Installed -> notice(
                            title = appContext.getString(R.string.plugins_rollback_done_title),
                            message = appContext.getString(
                                R.string.plugins_rollback_done_message,
                                plugin.name,
                                result.plugin.version,
                            ),
                            isError = false,
                        )
                        else -> notice(
                            title = appContext.getString(R.string.plugins_rollback_failed_title),
                            message = (result as? PluginPackageInstaller.Result.Failed)?.message
                                ?: appContext.getString(R.string.plugins_rollback_failed_message),
                            isError = true,
                        )
                    },
                )
            }
            refresh()
        }
    }

    fun updateFromSource(pluginId: String) {
        if (pluginsState.isBusy) return
        pluginsState = pluginsState.copy(busyPluginId = pluginId, notice = null)
        scope.launch(Dispatchers.IO) {
            val source = runCatching { runtime.sources.get(pluginId) }.getOrNull()
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(busyPluginId = null)
                if (source == null || !source.isLink || source.location.isBlank()) {
                    pluginsState = pluginsState.copy(
                        notice = notice(
                            title = appContext.getString(R.string.plugins_update_failed_title),
                            message = appContext.getString(R.string.plugins_update_no_source),
                            isError = true,
                        ),
                    )
                } else {
                    prepareLinkInstall(source.location, pluginId)
                }
            }
        }
    }

    /**
     * 检查更新：对所有「链接安装」的插件重新下载来源包，**只解析清单、不安装**。
     *
     * 为什么要单独一个动作：「从来源更新」要逐个插件点开才知道有没有新版本，
     * 这里一次点完，把结论标在列表上（行内「可更新 v1.3.0」）。
     *
     * 代价如实说明：链接只有整包才能读到版本，所以每个插件都会真下载一次（≤32MB）。
     * 因此**不自动执行**、串行执行，并在界面上显示「正在检查更新」。
     */
    fun checkUpdates() {
        if (pluginsState.isBusy) return
        pluginsState = pluginsState.copy(isCheckingUpdates = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val targets = runCatching {
                val sources = runtime.sources.load()
                runtime.allInstalled().mapNotNull { plugin ->
                    val source = sources[plugin.id]
                    if (source != null && source.isLink && source.location.isNotBlank()) {
                        plugin to source.location
                    } else {
                        null
                    }
                }
            }.getOrElse { error ->
                AndroidAgentLogger.warn(
                    "Agent plugin update check list failed: type=${error.javaClass.simpleName}",
                )
                withContext(Dispatchers.Main) {
                    pluginsState = pluginsState.copy(
                        isCheckingUpdates = false,
                        notice = notice(
                            title = appContext.getString(R.string.plugins_check_failed_title),
                            message = appContext.getString(R.string.plugins_check_failed_message),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            if (targets.isEmpty()) {
                withContext(Dispatchers.Main) {
                    pluginsState = pluginsState.copy(
                        isCheckingUpdates = false,
                        updateStatuses = emptyMap(),
                        notice = notice(
                            title = appContext.getString(R.string.plugins_check_no_targets_title),
                            message = appContext.getString(R.string.plugins_check_no_targets_message),
                            isError = false,
                        ),
                    )
                }
                return@launch
            }
            val statuses = LinkedHashMap<String, PluginUpdateStatusUi>()
            targets.forEach { (plugin, location) ->
                val status = checkOneUpdate(plugin, location)
                statuses[plugin.id] = status
                // 没有这行日志时，界面不出结果只能靠猜（第一次真机验收就遇到了）。
                AndroidAgentLogger.info(
                    "Agent plugin update check id=${plugin.id} state=${status.state} " +
                        "current=${status.currentVersion} remote=${status.remoteVersion}",
                )
            }
            withContext(Dispatchers.Main) {
                pluginsState = pluginsState.copy(
                    isCheckingUpdates = false,
                    updateStatuses = statuses,
                    notice = summarizeUpdateCheck(statuses),
                )
            }
        }
    }

    /** 单个插件：下载 → 只读清单 → 比版本 → 删掉临时文件（成败都删）。 */
    private suspend fun checkOneUpdate(
        plugin: PluginManifest.Plugin,
        location: String,
    ): PluginUpdateStatusUi {
        val outcome = linkDownloader.download(location)
        val downloaded = when (outcome) {
            is PluginLinkDownloader.Outcome.Failed ->
                return failedUpdateStatus(plugin, "${outcome.code}：${outcome.message}")
            is PluginLinkDownloader.Outcome.Downloaded -> outcome
        }
        return try {
            val inspection = runCatching {
                runtime.installer.inspectZip { downloaded.file.inputStream() }
            }.getOrNull()
            when (inspection) {
                is PluginPackageInstaller.Inspection.Ready -> {
                    val remote = inspection.plugin
                    // 同一个链接现在给的是别的插件：不能报「可更新」，那会覆盖掉一个无关插件。
                    if (remote.id != plugin.id) {
                        failedUpdateStatus(
                            plugin,
                            appContext.getString(R.string.plugins_check_id_mismatch, remote.id),
                        )
                    } else {
                        when (val relation = PluginVersion.relate(remote.version, plugin.version)) {
                            PluginVersion.Relation.NEWER -> PluginUpdateStatusUi(
                                pluginId = plugin.id,
                                name = plugin.name,
                                currentVersion = plugin.version,
                                remoteVersion = remote.version,
                                state = PluginUpdateStatusUi.State.UPDATE_AVAILABLE,
                            )
                            PluginVersion.Relation.SAME -> PluginUpdateStatusUi(
                                pluginId = plugin.id,
                                name = plugin.name,
                                currentVersion = plugin.version,
                                remoteVersion = remote.version,
                                state = PluginUpdateStatusUi.State.UP_TO_DATE,
                            )
                            PluginVersion.Relation.OLDER -> PluginUpdateStatusUi(
                                pluginId = plugin.id,
                                name = plugin.name,
                                currentVersion = plugin.version,
                                remoteVersion = remote.version,
                                state = PluginUpdateStatusUi.State.OLDER,
                                detail = appContext.getString(
                                    R.string.plugins_check_older_detail,
                                    remote.version,
                                    plugin.version,
                                ),
                            )
                            // 格式读不出来但确实不同：交给用户判断，不替他下结论。
                            PluginVersion.Relation.DIFFERENT -> PluginUpdateStatusUi(
                                pluginId = plugin.id,
                                name = plugin.name,
                                currentVersion = plugin.version,
                                remoteVersion = remote.version,
                                state = PluginUpdateStatusUi.State.UPDATE_AVAILABLE,
                                detail = appContext.getString(
                                    R.string.plugins_check_version_unknown,
                                    remote.version,
                                    plugin.version,
                                ),
                            )
                        }
                    }
                }
                is PluginPackageInstaller.Inspection.Rejected ->
                    failedUpdateStatus(plugin, "${inspection.code}：${inspection.message}")
                null -> failedUpdateStatus(
                    plugin,
                    appContext.getString(R.string.plugins_check_failed_message),
                )
            }
        } finally {
            linkDownloader.cleanup(downloaded.file)
        }
    }

    private fun failedUpdateStatus(plugin: PluginManifest.Plugin, detail: String) =
        PluginUpdateStatusUi(
            pluginId = plugin.id,
            name = plugin.name,
            currentVersion = plugin.version,
            remoteVersion = "",
            state = PluginUpdateStatusUi.State.FAILED,
            detail = detail.take(160),
        )

    /** 把逐插件结论压成一条提示：有更新的列出来、失败与「来源更旧」附在后面，不混成一句「检查完成」。 */
    /**
     * 把逐插件结论压成一条提示。
     *
     * 分类交给 [PluginUpdateSummary]（纯函数、有单测），这里只负责挑文案：
     * 之前把「来源更旧」错归进"已是最新"，真机上出现标题说最新、正文说更旧的自相矛盾。
     */
    private fun summarizeUpdateCheck(
        statuses: Map<String, PluginUpdateStatusUi>,
    ): PluginNoticeUi {
        val summary = PluginUpdateSummary.of(statuses.values)
        val lines = buildList {
            summary.updates.take(MAX_LISTED_UPDATE_ITEMS).forEach { status ->
                add(
                    appContext.getString(
                        R.string.plugins_check_update_line,
                        status.name,
                        status.currentVersion,
                        status.remoteVersion,
                    ),
                )
            }
            if (summary.updates.size > MAX_LISTED_UPDATE_ITEMS) {
                add(
                    appContext.getString(
                        R.string.plugins_check_more_updates,
                        summary.updates.size - MAX_LISTED_UPDATE_ITEMS,
                    ),
                )
            }
            summary.failures.take(MAX_LISTED_UPDATE_ITEMS).forEach { status ->
                add(
                    appContext.getString(
                        R.string.plugins_check_failed_line,
                        status.name,
                        status.detail.orEmpty(),
                    ),
                )
            }
            if (summary.failures.size > MAX_LISTED_UPDATE_ITEMS) {
                add(
                    appContext.getString(
                        R.string.plugins_check_more_failures,
                        summary.failures.size - MAX_LISTED_UPDATE_ITEMS,
                    ),
                )
            }
        }
        val title = when (summary.kind) {
            PluginUpdateSummary.Kind.UPDATES ->
                appContext.getString(R.string.plugins_check_found_title, summary.updates.size)
            PluginUpdateSummary.Kind.FAILURES ->
                appContext.getString(R.string.plugins_check_all_failed_title)
            PluginUpdateSummary.Kind.OLDER ->
                appContext.getString(R.string.plugins_check_older_title)
            PluginUpdateSummary.Kind.UP_TO_DATE ->
                appContext.getString(R.string.plugins_check_none_title)
            PluginUpdateSummary.Kind.NOTHING ->
                appContext.getString(R.string.plugins_check_no_targets_title)
        }
        val message = when (summary.kind) {
            PluginUpdateSummary.Kind.UPDATES, PluginUpdateSummary.Kind.FAILURES ->
                lines.joinToString("\n")
            PluginUpdateSummary.Kind.OLDER ->
                appContext.getString(R.string.plugins_check_older_summary, summary.older.size)
            PluginUpdateSummary.Kind.UP_TO_DATE ->
                appContext.getString(R.string.plugins_check_none_message, summary.checked)
            PluginUpdateSummary.Kind.NOTHING ->
                appContext.getString(R.string.plugins_check_no_targets_message)
        }
        return notice(
            title = title,
            message = message,
            isError = summary.kind == PluginUpdateSummary.Kind.FAILURES,
        )
    }

    fun cancelLinkInstall() {
        discardPendingLink()
        pluginsState = pluginsState.copy(linkPreview = null)
    }

    private fun discardPendingLink() {
        pendingLinkFile?.let(linkDownloader::cleanup)
        pendingLinkFile = null
    }

    /**
     * 失败原因原样展示 code + message。
     *
     * 插件安装的失败码本身就有信息量（例如 `PLUGIN_MCP_HTTP_ONLY` 说明这是能力限制而不是坏包），
     * 统一翻译成"安装失败"会让用户以为是文件坏了，反复重试。
     */
    /**
     * 装完/更新完立刻把插件声明的 MCP 服务器注册进去。
     *
     * 不做这一步的话：界面显示「1 个 MCP 服务器」，MCP 服务器列表里却是空的
     * （真机实测：从界面装完，`mcp_servers` 表一行都没有），用户只能去调 agent 工具或重启。
     * 失败不抛出：注册失败不该让「插件已经装好了」这个事实消失，但必须写进提示里。
     */
    private suspend fun syncMcpAfterInstall(result: PluginPackageInstaller.Result?): String? {
        val installed = result as? PluginPackageInstaller.Result.Installed ?: return null
        val problems = runCatching {
            PluginMcpRegistry.sync(installed.plugin, enabled = true)
        }.getOrElse { error ->
            listOf("${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(200))
        }
        if (problems.isEmpty()) return null
        AndroidAgentLogger.warn(
            "Agent plugin mcp sync failed: ${problems.joinToString("；").take(200)}",
        )
        return appContext.getString(
            R.string.plugins_mcp_sync_failed_detail,
            problems.joinToString("；").take(300),
        )
    }

    /** 安装/更新统一的提示：成功文案与「顺带没注册上的 MCP」一起说清楚。 */
    private fun installNotice(
        result: PluginPackageInstaller.Result?,
        updating: Boolean,
        mcpDetail: String?,
    ): PluginNoticeUi {
        fun withMcp(base: String): String = if (mcpDetail.isNullOrBlank()) base else "$base\n$mcpDetail"
        return when (result) {
            is PluginPackageInstaller.Result.Installed -> notice(
                title = appContext.getString(
                    if (updating) R.string.plugins_updated_title else R.string.plugins_installed_title,
                ),
                message = withMcp(
                    when {
                        result.alreadyInstalled -> appContext.getString(
                            R.string.plugins_already_installed,
                            result.plugin.name,
                            result.plugin.version,
                        )
                        updating -> appContext.getString(
                            R.string.plugins_updated_message,
                            result.plugin.name,
                            result.plugin.version,
                        )
                        else -> appContext.getString(
                            R.string.plugins_installed_message,
                            result.plugin.name,
                            result.plugin.version,
                        )
                    },
                ),
                isError = false,
            )
            is PluginPackageInstaller.Result.Failed -> notice(
                title = appContext.getString(R.string.plugins_install_failed_title),
                message = describeFailure(result),
                isError = true,
            )
            null -> notice(
                title = appContext.getString(R.string.plugins_install_failed_title),
                message = appContext.getString(R.string.plugins_install_failed_message),
                isError = true,
            )
        }
    }

    private fun describeFailure(failed: PluginPackageInstaller.Result.Failed): String =
        if (failed.message.isBlank()) failed.code else "${failed.code}：${failed.message}"

    private fun describeSource(source: PluginSourceStore.Source): String =
        if (source.isLink && source.host.isNotBlank()) {
            appContext.getString(R.string.plugins_source_host, source.host)
        } else {
            appContext.getString(R.string.plugins_source_local)
        }

    private fun notice(title: String, message: String, isError: Boolean): PluginNoticeUi =
        PluginNoticeUi(
            id = ++noticeSequence,
            title = title,
            message = message,
            isError = isError,
        )
}

/** 提示里最多逐条列几个插件：再多就变成一堵墙，用户反而看不到重点。 */
private const val MAX_LISTED_UPDATE_ITEMS = 5
