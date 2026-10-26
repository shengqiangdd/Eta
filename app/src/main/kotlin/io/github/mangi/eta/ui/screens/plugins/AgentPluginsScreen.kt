package io.github.mangi.eta.ui.screens.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentPluginsAction
import io.github.mangi.eta.ui.model.AgentPluginsUiState
import io.github.mangi.eta.ui.model.PluginDetails
import io.github.mangi.eta.ui.model.PluginItemUi
import io.github.mangi.eta.ui.model.PluginUpdateStatusUi
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

@Composable
fun AgentPluginsScreen(
    state: AgentPluginsUiState,
    onAction: (AgentPluginsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var uninstallTarget by remember { mutableStateOf<PluginItemUi?>(null) }
    // 只记 id 而不是对象：弹窗开着时用户仍可能拨开关、插件列表刷新，
    // 存对象会让详情停在打开那一刻的旧值（版本 / 启用状态对不上）。
    var detailsTargetId by remember { mutableStateOf<String?>(null) }
    var linkDialogOpen by remember { mutableStateOf(false) }
    var linkInput by remember { mutableStateOf("") }
    val operationPending = state.isBusy
    // 预览一出现就收起输入框：否则装完/取消后会退回「输入链接」这一层，
    // 用户看到刚才的链接还填着，会以为要点第二次「下载并预览」（真机实测踩到）。
    LaunchedEffect(state.linkPreview) {
        if (state.linkPreview != null) linkDialogOpen = false
    }
    // 系统 OpenDocument 在虚拟屏上会把任务搬到主屏（实测），ZIP 导入因此走应用内选择器。
    val context = LocalContext.current
    var pickingZip by remember { mutableStateOf(false) }
    val openZipPicker = { if (!operationPending) pickingZip = true }
    if (pickingZip) {
        WorkspacePathPicker(
            initialPath = WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.IMPORT)
                ?: pickerStartPath(context),
            pickFiles = true,
            title = stringResource(R.string.capability_workspace_pick_file),
            onBack = { pickingZip = false },
            onPick = { path ->
                pickingZip = false
                WorkspacePickerHistory.remember(
                    context,
                    WorkspacePickerHistory.Kind.IMPORT,
                    importHistoryDirectory(path),
                )
                onAction(AgentPluginsAction.ImportZipPath(path))
            },
        )
        return
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_plugins),
        onBack = { onAction(AgentPluginsAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "plugin-import-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.ui_install_087db6))
        }
        item(key = "plugin-import-card") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                EtaPreference(
                    title = if (state.isImporting) {
                        stringResource(R.string.plugins_installing)
                    } else {
                        stringResource(R.string.plugins_import_zip)
                    },
                    summary = stringResource(R.string.plugins_import_summary),
                    startAction = {
                        if (state.isImporting) {
                            Box(
                                modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        } else {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.FolderZip,
                                enabled = !operationPending,
                                tint = EtaPreferenceColors.Orange,
                            )
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                    onClickLabel = stringResource(R.string.plugins_import_zip),
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = if (state.isFetchingLink) {
                        stringResource(R.string.plugins_link_fetching)
                    } else {
                        stringResource(R.string.plugins_link_install)
                    },
                    summary = stringResource(R.string.plugins_link_summary),
                    startAction = {
                        if (state.isFetchingLink) {
                            Box(
                                modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        } else {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Link,
                                enabled = !operationPending,
                                tint = EtaPreferenceColors.Blue,
                            )
                        }
                    },
                    enabled = !operationPending,
                    onClick = { linkDialogOpen = true },
                    onClickLabel = stringResource(R.string.plugins_link_install),
                )
            }
        }

        if (state.plugins.isNotEmpty()) {
            item(key = "plugin-installed-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.plugins_installed_group))
            }
            item(key = "plugin-installed-card") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    state.plugins.forEachIndexed { index, plugin ->
                        if (index > 0) EtaPreferenceDivider()
                        PluginSwitchRow(
                            plugin = plugin,
                            enabled = !operationPending,
                            updateAvailableVersion = state.updateStatuses[plugin.id]
                                ?.takeIf { it.state == PluginUpdateStatusUi.State.UPDATE_AVAILABLE }
                                ?.remoteVersion,
                            onToggle = { checked ->
                                onAction(AgentPluginsAction.TogglePlugin(plugin.id, checked))
                            },
                            onUninstall = { uninstallTarget = plugin },
                            onShowDetails = { detailsTargetId = plugin.id },
                            onUpdateFromSource = {
                                onAction(AgentPluginsAction.UpdateFromSource(plugin.id))
                            },
                            onRollback = { version ->
                                onAction(AgentPluginsAction.RollbackPlugin(plugin.id, version))
                            },
                        )
                    }
                    EtaPreferenceDivider()
                    // 检查更新放在已安装列表里而不是顶栏：它作用的对象就是这些插件，
                    // 且结果是逐插件标在行上的，用户看完就知道该点谁。
                    EtaPreference(
                        title = if (state.isCheckingUpdates) {
                            stringResource(R.string.plugins_checking_updates)
                        } else {
                            stringResource(R.string.plugins_check_updates)
                        },
                        summary = stringResource(R.string.plugins_check_updates_summary),
                        startAction = {
                            if (state.isCheckingUpdates) {
                                Box(
                                    modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            } else {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Refresh,
                                    enabled = !operationPending,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            }
                        },
                        enabled = !operationPending,
                        onClick = { onAction(AgentPluginsAction.CheckUpdates) },
                        onClickLabel = stringResource(R.string.plugins_check_updates),
                    )
                }
            }
        }

        if (state.plugins.isEmpty() && state.isLoading) {
            item(key = "plugin-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
        }
        if (state.plugins.isEmpty() && !state.isLoading) {
            item(key = "plugin-empty") {
                ListEmptyState(
                    title = stringResource(R.string.plugins_empty_title),
                    summary = stringResource(R.string.plugins_empty_summary),
                    icon = Icons.Rounded.Extension,
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EtaTextButton(
                                text = stringResource(R.string.plugins_import_zip),
                                enabled = !operationPending,
                                onClick = openZipPicker,
                            )
                            EtaTextButton(
                                text = stringResource(R.string.plugins_link_install),
                                enabled = !operationPending,
                                onClick = { linkDialogOpen = true },
                            )
                        }
                    },
                )
            }
        }
    }

    if (linkDialogOpen) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.plugins_link_title),
            summary = stringResource(R.string.plugins_link_dialog_summary),
            onDismissRequest = { if (!state.isFetchingLink) linkDialogOpen = false },
        ) {
            Column {
                TextField(
                    value = linkInput,
                    onValueChange = { linkInput = it },
                    label = stringResource(R.string.plugins_link_hint),
                    singleLine = true,
                    enabled = !state.isFetchingLink,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isFetchingLink) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        InfiniteProgressIndicator(size = 18.dp)
                        Text(
                            text = stringResource(R.string.plugins_link_fetching),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                MiuixDialogActions(
                    confirmText = stringResource(R.string.plugins_link_fetch),
                    confirmEnabled = linkInput.isNotBlank() && !state.isFetchingLink,
                    cancelEnabled = !state.isFetchingLink,
                    onCancel = { linkDialogOpen = false },
                    onConfirm = { onAction(AgentPluginsAction.PrepareLinkInstall(linkInput)) },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    state.linkPreview?.let { preview ->
        val channels = buildList {
            if (preview.toolCount > 0) add(stringResource(R.string.plugins_tool_count, preview.toolCount))
            if (preview.mcpCount > 0) add(stringResource(R.string.plugins_mcp_count, preview.mcpCount))
            if (preview.acpCount > 0) add(stringResource(R.string.plugins_acp_count, preview.acpCount))
            if (preview.hasPrompt) add(stringResource(R.string.plugins_prompt_extension))
        }
        EtaWindowDialog(
            show = true,
            title = stringResource(
                if (preview.isUpdate) {
                    R.string.plugins_update_preview_title
                } else {
                    R.string.plugins_link_preview_title
                },
            ),
            onDismissRequest = { onAction(AgentPluginsAction.CancelLinkInstall) },
        ) {
            Column {
                Text(
                    text = "${preview.name} v${preview.version}",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                // 更新模式要把「从哪个版本到哪个版本」写清楚，否则用户看不出这次下载有没有意义。
                if (preview.isUpdate && preview.currentVersion.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.plugins_update_version_change,
                            preview.currentVersion,
                            preview.version,
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = EtaPreferenceColors.Blue,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (preview.description.isNotBlank()) {
                    Text(
                        text = preview.description,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.plugins_link_source,
                        "http${if (preview.secure) "s" else ""}://${preview.host}",
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!preview.secure) {
                    Text(
                        text = stringResource(R.string.plugins_link_insecure),
                        style = MiuixTheme.textStyles.footnote1,
                        color = EtaPreferenceColors.Orange,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = if (channels.isEmpty()) {
                        stringResource(R.string.plugins_channels_none)
                    } else {
                        channels.joinToString(" · ")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.plugins_link_size, formatSize(preview.bytes)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.plugins_link_digest, preview.sha256.take(16)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                MiuixDialogActions(
                    confirmText = if (state.isImporting) {
                        stringResource(R.string.plugins_installing)
                    } else {
                        stringResource(
                            if (preview.isUpdate) {
                                R.string.plugins_link_update_confirm
                            } else {
                                R.string.plugins_link_confirm
                            },
                        )
                    },
                    confirmEnabled = !state.isImporting,
                    cancelEnabled = !state.isImporting,
                    onCancel = { onAction(AgentPluginsAction.CancelLinkInstall) },
                    onConfirm = { onAction(AgentPluginsAction.ConfirmLinkInstall) },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    // 详情按 id 从最新状态里取：插件被卸载后弹窗自动消失，不需要额外收尾。
    state.plugins.firstOrNull { it.id == detailsTargetId }?.let { plugin ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.plugins_details),
            summary = "${plugin.name} · v${plugin.version}",
            onDismissRequest = { detailsTargetId = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_id),
                    value = plugin.id,
                    monospace = true,
                )
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_source),
                    value = PluginDetails.sourceForDetails(plugin.sourceHost, plugin.sourceLabel)
                        ?: stringResource(R.string.plugins_details_not_recorded),
                )
                // 主机名不足以核实来源：同一主机下可能挂着很多包，完整地址才是凭据。
                if (plugin.sourceLocation.isNotBlank()) {
                    PluginDetailRow(
                        label = stringResource(R.string.plugins_details_source_location),
                        value = plugin.sourceLocation,
                        monospace = true,
                    )
                }
                // 「什么时候装的、装的是哪份包」原本只躺在 .sources.json 里，
                // 用户没法核实自己装的到底是不是刚下载的那一份。
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_installed_at),
                    value = PluginDetails.formatInstalledAt(plugin.installedAtMillis)
                        ?: stringResource(R.string.plugins_details_not_recorded),
                )
                // 覆盖安装会删掉旧版本；有没有留底、留的是哪一版，用户必须看得出来，
                // 否则「要不要升级」这个决定缺少最关键的一条信息。
                // 多版本时全部列出：只显示「上一版」时，用户不知道再往前还能退。
                if (plugin.rollbackVersions.isNotEmpty()) {
                    val versions = plugin.rollbackVersions.map { entry ->
                        val backedUpAt = PluginDetails.formatInstalledAt(entry.backedUpAtMillis)
                        if (backedUpAt != null) {
                            stringResource(
                                R.string.plugins_details_rollback_value,
                                entry.version,
                                backedUpAt,
                            )
                        } else {
                            "v${entry.version}"
                        }
                    }
                    PluginDetailRow(
                        label = stringResource(R.string.plugins_details_rollback),
                        value = versions.joinToString("\n"),
                    )
                }
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_digest),
                    value = PluginDetails.shortDigest(plugin.sha256)
                        ?: stringResource(R.string.plugins_details_not_recorded),
                    monospace = true,
                )
                Text(
                    text = stringResource(R.string.plugins_details_digest_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 行摘要只给「1 项能力声明 · 1 个 MCP 服务器」，具体是哪几个只有点开这里才知道。
                Text(
                    text = stringResource(R.string.plugins_details_capabilities),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 14.dp),
                )
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_tools),
                    value = capabilityValue(plugin.toolNames),
                )
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_mcp),
                    value = capabilityValue(plugin.mcpServers),
                )
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_acp),
                    value = capabilityValue(plugin.acpAgents),
                )
                // ACP 与 MCP 的生效时机不同：MCP 在插件页刷新时就写进注册表，
                // ACP 由**会话级** AcpClient 持有，只能等下一次会话重建工具集。
                // 真机验收时「插件装好了、代理列表里却查不到」极容易被误判成安装失败，
                // 所以这里如实写明，而不是让用户自己去猜。
                if (plugin.acpAgents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugins_details_acp_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                PluginDetailRow(
                    label = stringResource(R.string.plugins_details_prompt),
                    value = stringResource(
                        if (plugin.hasPrompt) R.string.plugins_details_yes else R.string.plugins_details_no,
                    ),
                )
                if (plugin.toolNames.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugins_details_tools_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // 回滚原本只挂在行尾菜单里：那是个小图标按钮，用户很难想到
                // 「版本还能退回去」藏在里面（验收时我先点了行内徽标，没反应）。
                // 每个留底版本一个按钮：只给「上一版」时，想退两版得先退一次再退一次，
                // 而单版本留底会在第一步就把目标版本覆盖掉（真实缺陷）。
                plugin.rollbackVersions.forEach { entry ->
                    EtaTextButton(
                        text = stringResource(R.string.plugins_rollback, entry.version),
                        onClick = {
                            detailsTargetId = null
                            onAction(AgentPluginsAction.RollbackPlugin(plugin.id, entry.version))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = ButtonDefaults.textButtonColors(),
                    )
                }
                EtaTextButton(
                    text = stringResource(R.string.ui_knew_cb63c6),
                    onClick = { detailsTargetId = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    uninstallTarget?.let { plugin ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.plugins_uninstall_title),
            summary = stringResource(R.string.plugins_uninstall_summary, plugin.name),
            onDismissRequest = { uninstallTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.plugins_uninstall),
                destructive = true,
                confirmEnabled = !operationPending,
                onCancel = { uninstallTarget = null },
                onConfirm = {
                    uninstallTarget = null
                    onAction(AgentPluginsAction.UninstallPlugin(plugin.id))
                },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentPluginsAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentPluginsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun PluginSwitchRow(
    plugin: PluginItemUi,
    enabled: Boolean,
    /** 非空表示检查更新发现远端有这个更新的版本；行内直接标出来。 */
    updateAvailableVersion: String?,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUpdateFromSource: () -> Unit,
    onShowDetails: () -> Unit,
    onRollback: (String) -> Unit,
) {
    val description = plugin.description.ifBlank { stringResource(R.string.plugins_no_description) }
    val channels = buildList {
        if (plugin.toolCount > 0) {
            add(stringResource(R.string.plugins_tool_count, plugin.toolCount))
        }
        if (plugin.mcpServers.isNotEmpty()) {
            add(stringResource(R.string.plugins_mcp_count, plugin.mcpServers.size))
        }
        if (plugin.acpAgents.isNotEmpty()) {
            add(stringResource(R.string.plugins_acp_count, plugin.acpAgents.size))
        }
        if (plugin.hasPrompt) {
            add(stringResource(R.string.plugins_prompt_extension))
        }
    }
    // 「声明了能力但一个都跑不了」是最容易误判成"装成功"的状态：说明性工具没有执行器，
    // 真正能跑的只有 MCP 与 ACP，这里如实标出来。
    val summary = if (plugin.executableChannelCount == 0) {
        stringResource(R.string.plugins_channels_none)
    } else {
        channels.joinToString(" · ")
    }
    EtaPreference(
        startAction = {
            EtaPreferenceIcon(
                icon = Icons.Rounded.Extension,
                enabled = enabled,
                tint = if (plugin.enabled) EtaPreferenceColors.Green else EtaPreferenceColors.Blue,
            )
        },
        endActions = {
            OverlayIconDropdownMenu(
                modifier = Modifier.align(Alignment.CenterVertically),
                entry = DropdownEntry(
                    items = buildList {
                        // 详情放第一项：装完之后「是谁给的、什么时候装的、装的是哪份包」
                        // 这三件事本该一眼能看到，而不是藏在文件里。
                        add(
                            DropdownItem(
                                text = stringResource(R.string.plugins_details),
                                enabled = enabled,
                                onClick = { if (enabled) onShowDetails() },
                            ),
                        )
                        // 只有「链接安装」的插件才有来源链接可重放；本地导入没有可靠的重读地址。
                        if (plugin.canUpdateFromSource) {
                            add(
                                DropdownItem(
                                    text = stringResource(R.string.plugins_update_from_source),
                                    enabled = enabled,
                                    onClick = { if (enabled) onUpdateFromSource() },
                                ),
                            )
                        }
                        // 每个留底版本一项：只给「上一版」时，多版本留底等于看不见；
                        // 没有备份就整项不显示，否则等于给个死按钮。
                        plugin.rollbackVersions.forEach { entry ->
                            add(
                                DropdownItem(
                                    text = stringResource(R.string.plugins_rollback, entry.version),
                                    enabled = enabled,
                                    onClick = { if (enabled) onRollback(entry.version) },
                                ),
                            )
                        }
                        add(
                            DropdownItem(
                                text = stringResource(R.string.plugins_uninstall),
                                enabled = enabled,
                                onClick = { if (enabled) onUninstall() },
                            ),
                        )
                    },
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = plugin.name,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            EtaSwitch(
                checked = plugin.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = plugin.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "v${plugin.version}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            text = description,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        // 「检查更新」的结论落在这一行：否则用户必须逐个点「从来源更新」才知道有没有新版本。
        // 只标一行绿字等于「看得见但点不动」——验收时连我自己都点了三次没反应，
        // 所以这里直接做成可点击入口，点它就是「从来源更新」。
        updateAvailableVersion?.let { remote ->
            Text(
                text = stringResource(R.string.plugins_update_available_action, remote),
                style = MiuixTheme.textStyles.footnote1,
                color = if (enabled) EtaPreferenceColors.Green else MiuixTheme.colorScheme.disabledOnSurface,
                textDecoration = if (enabled) TextDecoration.Underline else null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(enabled = enabled) { onUpdateFromSource() }
                    .padding(vertical = 4.dp),
            )
        }
        // 「从哪来」：装完之后界面上原本完全看不出这个插件是谁给的，
        // 用户没法核实来源，也没法判断能不能从同一个链接更新。
        plugin.sourceLabel?.let { label ->
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = summary,
            style = MiuixTheme.textStyles.footnote1,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 详情弹窗里的一行「字段 / 值」。
 *
 * 值用 weight 而不是固定宽度：中文标签四个字、英文标签一个词，宽度差得远，
 * 写死宽度总有一边会换行或留一大片空白。
 */
@Composable
private fun PluginDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.36f),
        )
        Text(
            text = value,
            style = if (monospace) {
                MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace)
            } else {
                MiuixTheme.textStyles.footnote1
            },
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.64f),
        )
    }
}

/** 能力清单的展示值：空列表显示「无」，超出部分只报数量。 */
@Composable
private fun capabilityValue(items: List<String>): String {
    if (items.isEmpty()) return stringResource(R.string.plugins_details_no)
    val slice = PluginDetails.sliceForDetails(items)
    val joined = slice.shown.joinToString(", ")
    return if (slice.hidden > 0) {
        joined + " " + stringResource(R.string.plugins_details_more, slice.hidden)
    } else {
        joined
    }
}
