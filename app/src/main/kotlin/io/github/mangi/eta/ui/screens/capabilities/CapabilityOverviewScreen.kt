package io.github.mangi.eta.ui.screens.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.CapabilityEntryUi
import io.github.mangi.eta.ui.model.CapabilityGroupUi
import io.github.mangi.eta.ui.model.filterCapabilityGroups
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private object CapabilityOverviewMetrics {
    val GridGap = 12.dp
    val GridHorizontalPadding = 16.dp
    val CardInsidePadding = 14.dp
    /** 分组标题的左边距；说明文字与它对齐，避免出现两套缩进。 */
    val SectionTitleStart = 32.dp
}

/**
 * 能力总览：把散在顶栏菜单、会话抽屉与设置里的入口收拢成一张按能力分组的地图。
 *
 * 设计取舍：
 * - 组内用双列卡片而不是长列表 —— 一眼能看到「有哪些能力」，而不是逐行读；
 * - 状态就地显示在卡片右上角（可用工具数、技能启用数、待处理权限），不需要先进页面才知道；
 * - 搜索是纯本地过滤，不额外请求任何数据，输入即可见。
 */
@Composable
internal fun CapabilityOverviewScreen(
    missingPermissionCount: Int,
    toolCount: Int,
    skillTotal: Int,
    skillEnabled: Int,
    skillLoading: Boolean,
    skillUnavailable: Boolean,
    providerCount: Int,
    currentModel: String?,
    onOpen: (AppRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) focusRequester.requestFocus()
    }
    val groups = capabilityGroups(
        missingPermissionCount = missingPermissionCount,
        toolCount = toolCount,
        skillTotal = skillTotal,
        skillEnabled = skillEnabled,
        skillLoading = skillLoading,
        skillUnavailable = skillUnavailable,
        providerCount = providerCount,
        currentModel = currentModel,
    )
    val visibleGroups = filterCapabilityGroups(groups, query)
    val entryCount = groups.sumOf { it.entries.size }
    MiuixScaffoldPage(
        title = stringResource(R.string.capability_overview_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(
                onClick = {
                    if (searching) {
                        query = ""
                        searching = false
                    } else {
                        searching = true
                    }
                },
            ) {
                Icon(
                    imageVector = if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(
                        if (searching) R.string.action_close else R.string.capability_overview_search,
                    ),
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) {
        if (searching) {
            item(key = "search") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.capability_overview_search_hint),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(bottom = 8.dp)
                        .focusRequester(focusRequester),
                )
            }
        }
        item(key = "intro") {
            Text(
                text = stringResource(R.string.capability_overview_intro, entryCount),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(
                    start = CapabilityOverviewMetrics.SectionTitleStart,
                    end = EtaPreferenceDefaults.SidePadding,
                    top = 4.dp,
                    bottom = 12.dp,
                ),
            )
        }
        // 权限缺失是唯一「现在就该处理」的状态，放在最前，避免用户翻完五个分组才发现。
        // 搜索时隐藏：正在按关键词找某个入口，提醒卡会把结果挤下去，也与查询无关。
        if (missingPermissionCount > 0 && query.isBlank()) {
            item(key = "attention") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
                ) {
                    EtaArrowPreference(
                        title = stringResource(
                            R.string.capability_overview_attention,
                            missingPermissionCount,
                        ),
                        summary = stringResource(R.string.capability_overview_attention_summary),
                        startAction = {
                            EtaPreferenceIcon(Icons.Rounded.Lock, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = { onOpen(AppRoute.Permissions) },
                    )
                }
            }
        }
        visibleGroups.forEach { group ->
            item(key = "${group.id}-title") {
                EtaPreferenceGroupTitle(group.title)
            }
            items(
                items = group.entries.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                CapabilityGridRow(entries = row, onOpen = onOpen)
            }
        }
        if (visibleGroups.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_overview_no_results),
                    summary = stringResource(R.string.capability_overview_no_results_summary),
                    icon = Icons.Rounded.Search,
                )
            }
        }
    }
}

@Composable
private fun CapabilityGridRow(
    entries: List<CapabilityEntryUi>,
    onOpen: (AppRoute) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CapabilityOverviewMetrics.GridHorizontalPadding)
            .padding(bottom = CapabilityOverviewMetrics.GridGap),
    ) {
        // 窄屏或放大字体时退回单列：双列会把标题挤成两三个字一行，可读性比「一屏看更多」更重要。
        // 本机（1216px / 560dpi）内容区约 315dp：320 会让双列永远不生效，296 才是真正的「窄屏」线。
        val useSingleColumn = maxWidth < 296.dp ||
            LocalDensity.current.fontScale >= 1.3f
        if (useSingleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(CapabilityOverviewMetrics.GridGap)) {
                entries.forEach { entry ->
                    CapabilityCard(entry, onOpen, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(CapabilityOverviewMetrics.GridGap),
            ) {
                entries.forEach { entry ->
                    CapabilityCard(
                        entry = entry,
                        onOpen = onOpen,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (entries.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    entry: CapabilityEntryUi,
    onOpen: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 总览卡片比工具卡片信息少，用自适应高度的紧凑卡：图标 + 状态一行，标题与摘要各最多两行。
    EtaCard(
        modifier = modifier,
        insideMargin = PaddingValues(CapabilityOverviewMetrics.CardInsidePadding),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = { onOpen(entry.route) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EtaPreferenceIcon(
                icon = iconForCapability(entry.id),
                tint = tintForCapability(entry.id),
            )
            Spacer(modifier = Modifier.weight(1f))
            entry.status?.let { status ->
                Text(
                    text = status,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (entry.statusNeedsAttention) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    },
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = entry.title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.summary,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun capabilityGroups(
    missingPermissionCount: Int,
    toolCount: Int,
    skillTotal: Int,
    skillEnabled: Int,
    skillLoading: Boolean,
    skillUnavailable: Boolean,
    providerCount: Int,
    currentModel: String?,
): List<CapabilityGroupUi> {
    val permissionsStatus = if (missingPermissionCount > 0) {
        stringResource(R.string.capability_overview_permission_missing, missingPermissionCount)
    } else {
        stringResource(R.string.capability_overview_permission_ready)
    }
    val toolsStatus = stringResource(R.string.capability_overview_tool_count, toolCount)
    // 技能列表是懒加载的：没读过时显示「读取中」，读取失败时明说不可用，
    // 而不是把「还没读」伪装成「一个都没有」。
    val skillsStatus = when {
        skillLoading -> stringResource(R.string.capability_overview_skill_loading)
        skillUnavailable -> stringResource(R.string.capability_overview_skill_unavailable)
        else -> stringResource(R.string.capability_overview_skill_count, skillEnabled, skillTotal)
    }
    val providersStatus = currentModel?.takeIf { it.isNotBlank() }
        ?: if (providerCount > 0) {
            stringResource(R.string.capability_overview_provider_count, providerCount)
        } else {
            null
        }
    return listOf(
        CapabilityGroupUi(
            id = "conversation",
            title = stringResource(R.string.capability_overview_group_conversation),
            entries = listOf(
                CapabilityEntryUi(
                    id = "characters",
                    title = stringResource(R.string.capability_overview_characters),
                    summary = stringResource(R.string.capability_overview_characters_summary),
                    route = AppRoute.Characters,
                    keywords = listOf("character", "roleplay", "角色"),
                ),
                CapabilityEntryUi(
                    id = "memory",
                    title = stringResource(R.string.route_memory),
                    summary = stringResource(R.string.capability_overview_memory_summary),
                    route = AppRoute.Memory,
                    keywords = listOf("memory", "记忆"),
                ),
                CapabilityEntryUi(
                    id = "backup",
                    title = stringResource(R.string.data_backup_title),
                    summary = stringResource(R.string.capability_overview_backup_summary),
                    route = AppRoute.DataBackup,
                    keywords = listOf("backup", "export", "备份"),
                ),
            ),
        ),
        CapabilityGroupUi(
            id = "agent",
            title = stringResource(R.string.capability_overview_group_agent),
            entries = listOf(
                CapabilityEntryUi(
                    id = "tools",
                    title = stringResource(R.string.route_tools),
                    summary = stringResource(R.string.capability_overview_tools_summary),
                    route = AppRoute.Tools,
                    status = toolsStatus,
                    keywords = listOf("tool", "工具"),
                ),
                CapabilityEntryUi(
                    id = "skills",
                    title = stringResource(R.string.capability_overview_skills),
                    summary = stringResource(R.string.capability_overview_skills_summary),
                    route = AppRoute.Skills,
                    status = skillsStatus,
                    keywords = listOf("skill", "技能"),
                ),
                CapabilityEntryUi(
                    id = "mcp",
                    title = stringResource(R.string.route_mcp_servers),
                    summary = stringResource(R.string.capability_overview_mcp_summary),
                    route = AppRoute.McpServers,
                    keywords = listOf("mcp", "server", "服务"),
                ),
                CapabilityEntryUi(
                    id = "browser",
                    title = stringResource(R.string.route_browser),
                    summary = stringResource(R.string.capability_overview_browser_summary),
                    route = AppRoute.Browser(),
                    keywords = listOf("browser", "web", "浏览器", "网页"),
                ),
            ),
        ),
        CapabilityGroupUi(
            id = "device",
            title = stringResource(R.string.capability_overview_group_device),
            entries = listOf(
                CapabilityEntryUi(
                    id = "permissions",
                    title = stringResource(R.string.route_permissions),
                    summary = stringResource(R.string.capability_overview_permissions_summary),
                    route = AppRoute.Permissions,
                    status = permissionsStatus,
                    statusNeedsAttention = missingPermissionCount > 0,
                    keywords = listOf("permission", "权限", "授权"),
                ),
                CapabilityEntryUi(
                    id = "enhance",
                    title = stringResource(R.string.route_system_enhancements),
                    summary = stringResource(R.string.capability_overview_enhance_summary),
                    route = AppRoute.SystemEnhance,
                    keywords = listOf("root", "xposed", "增强"),
                ),
                CapabilityEntryUi(
                    id = "terminal",
                    title = stringResource(R.string.route_terminal),
                    summary = stringResource(R.string.capability_overview_terminal_summary),
                    route = AppRoute.Terminal,
                    keywords = listOf("terminal", "shell", "终端"),
                ),
                CapabilityEntryUi(
                    id = "linux",
                    title = stringResource(R.string.route_linux_environment),
                    summary = stringResource(R.string.capability_overview_linux_summary),
                    route = AppRoute.LinuxEnvironment,
                    keywords = listOf("linux", "distro", "发行版"),
                ),
                CapabilityEntryUi(
                    id = "shared_folders",
                    title = stringResource(R.string.route_shared_folders),
                    summary = stringResource(R.string.capability_overview_shared_folders_summary),
                    route = AppRoute.SharedFolders,
                    keywords = listOf("mount", "共享", "挂载"),
                ),
                CapabilityEntryUi(
                    id = "workspace",
                    title = stringResource(R.string.capability_workspace),
                    summary = stringResource(R.string.capability_overview_workspace_summary),
                    route = AppRoute.Workspace,
                    keywords = listOf("file", "workspace", "文件"),
                ),
            ),
        ),
        CapabilityGroupUi(
            id = "model",
            title = stringResource(R.string.capability_overview_group_model),
            entries = listOf(
                CapabilityEntryUi(
                    id = "providers",
                    title = stringResource(R.string.route_model_providers),
                    summary = stringResource(R.string.capability_overview_providers_summary),
                    route = AppRoute.ModelProviders,
                    status = providersStatus,
                    keywords = listOf("model", "provider", "api", "模型"),
                ),
                CapabilityEntryUi(
                    id = "catalog",
                    title = stringResource(R.string.capability_overview_catalog),
                    summary = stringResource(R.string.capability_overview_catalog_summary),
                    route = AppRoute.CommunityCatalog,
                    keywords = listOf("catalog", "目录", "社区"),
                ),
                CapabilityEntryUi(
                    id = "speech",
                    title = stringResource(R.string.speech_settings_title),
                    summary = stringResource(R.string.capability_overview_speech_summary),
                    route = AppRoute.SpeechSettings,
                    keywords = listOf("speech", "voice", "语音", "朗读"),
                ),
            ),
        ),
        CapabilityGroupUi(
            id = "app",
            title = stringResource(R.string.capability_overview_group_app),
            entries = listOf(
                CapabilityEntryUi(
                    id = "appearance",
                    title = stringResource(R.string.appearance_title),
                    summary = stringResource(R.string.capability_overview_appearance_summary),
                    route = AppRoute.AppearanceSettings,
                    keywords = listOf("theme", "appearance", "外观", "主题"),
                ),
                CapabilityEntryUi(
                    id = "settings",
                    title = stringResource(R.string.route_settings),
                    summary = stringResource(R.string.capability_overview_settings_summary),
                    route = AppRoute.Settings,
                    keywords = listOf("settings", "设置"),
                ),
            ),
        ),
    )
}

private fun iconForCapability(id: String): ImageVector = when (id) {
    "characters" -> Icons.Rounded.SportsBar
    "memory" -> Icons.Rounded.Psychology
    "backup" -> Icons.Rounded.Inventory
    "tools" -> Icons.Rounded.Dashboard
    "skills" -> Icons.Rounded.ImportContacts
    "mcp" -> Icons.Rounded.AccountTree
    "browser" -> Icons.Rounded.Language
    "permissions" -> Icons.Rounded.Lock
    "enhance" -> Icons.Rounded.Security
    "terminal" -> Icons.Rounded.Terminal
    "linux" -> Icons.Rounded.Code
    "shared_folders" -> Icons.Rounded.FolderOpen
    "workspace" -> Icons.Rounded.Folder
    "providers" -> Icons.Rounded.Cloud
    "catalog" -> Icons.Rounded.Layers
    "speech" -> Icons.Rounded.SettingsVoice
    "appearance" -> Icons.Rounded.Palette
    else -> Icons.Rounded.Settings
}

private fun tintForCapability(id: String): Color = when (id) {
    "characters", "tools", "providers" -> EtaPreferenceColors.Green
    "permissions", "backup" -> EtaPreferenceColors.Orange
    "mcp", "linux", "speech" -> EtaPreferenceColors.Blue
    else -> EtaPreferenceColors.Green
}
