package io.github.mangi.eta.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.VirtualDisplayPanel
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import io.github.mangi.eta.ui.voice.SpeechAutoReadToggle

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单（新建对话、终端、浏览器），保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenWorkspace: () -> Unit,
    chatViewMode: ChatViewMode = ChatViewMode.ALL,
    onChatViewModeChange: (ChatViewMode) -> Unit = {},
    onSearchMessages: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationExportHistory: (ConversationSummaryUi) -> Unit,
    onConversationStop: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    homeCharacter: RoleplayChatUi? = null,
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        AgentTopBar(
                            route = currentRoute,
                            scrollBehavior = scrollBehavior,
                            color = topBarColor,
                            onBack = onBack,
                            onOpenConversationPane = onOpenConversationPane,
                            onNewConversation = onNewConversation,
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onRefreshKimiWeb = onRefreshKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                            onOpenCapabilities = onOpenCapabilities,
                            onOpenWorkspace = onOpenWorkspace,
                            chatViewMode = chatViewMode,
                            onChatViewModeChange = onChatViewModeChange,
                            onSearchMessages = onSearchMessages,
                            homeCharacter = homeCharacter,
                            onOpenCharacter = onOpenCharacter,
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                content(padding)
                // 只在聊天页显示预览面板：设置、运行详情等页面不该被它遮挡。
                if (currentRoute is AppRoute.Home) {
                    VirtualDisplayPanel(
                        // 面板跟着当前对话走：别的对话的屏不该出现在这里。
                        conversationId = conversationPaneState?.selectedConversationId,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = padding.calculateTopPadding() + 8.dp, end = 12.dp),
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationPin = onConversationPin,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationExportHistory = onConversationExportHistory,
                onConversationStop = onConversationStop,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenWorkspace: () -> Unit,
    chatViewMode: ChatViewMode,
    onChatViewModeChange: (ChatViewMode) -> Unit,
    onSearchMessages: () -> Unit,
    homeCharacter: RoleplayChatUi?,
    onOpenCharacter: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    // 宽屏下会话列表常驻左侧，这个「拉开列表」按钮点下去不会有任何变化，直接收起来。
    val wideScreen = rememberIsWideScreen()
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!wideScreen) {
                    IconButton(onClick = onOpenConversationPane) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ShortText,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.action_conversation_history),
                        )
                    }
                }
                // 角色对话的身份标识放在顶栏左侧，点按进入角色详情；普通对话保持空标题。
                homeCharacter?.let { character ->
                    CharacterTopBarIdentity(character = character, onClick = onOpenCharacter)
                }
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            ChatViewModeMenu(mode = chatViewMode, onSelect = onChatViewModeChange)
            SpeechAutoReadToggle()
            TopBarOverflowMenu(
                onSearchMessages = onSearchMessages,
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                onOpenCapabilities = onOpenCapabilities,
                onOpenWorkspace = onOpenWorkspace,
            )
        }
    }

    // 首页聊天舞台与浏览器、终端这类整屏工具页没有可驱动折叠的滚动列表，大标题会一直展开、
    // 白占一屏顶部；它们使用紧凑标题，其余二级内容页统一使用可折叠大标题。
    val compactTitle = isHome || route is AppRoute.Browser || route is AppRoute.Terminal
    if (compactTitle) {
        SmallTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

/**
 * 顶栏的聊天视图菜单：全部 / 对话 / 轨迹。
 *
 * 图标本身指示当前模式（没收窄是描边，收窄是实心强调色），点开才展开选项 ——
 * 它不是一个开关：直接循环切换会让用户看不出「点一下变了什么」，也不知道还有第三种；
 * 也不该藏进溢出菜单，否则用户会以为工具调用没发生过。图标只是状态指示，所以选中项在
 * 弹层里用勾选再确认一次。
 */
@Composable
private fun ChatViewModeMenu(
    mode: ChatViewMode,
    onSelect: (ChatViewMode) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = when (mode) {
                    ChatViewMode.ALL -> Icons.Outlined.FilterAlt
                    ChatViewMode.DIALOG -> Icons.Rounded.FilterAlt
                    ChatViewMode.TRACE -> Icons.Rounded.Timeline
                },
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.chat_view_mode_action),
                tint = if (mode == ChatViewMode.ALL) {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                } else {
                    MiuixTheme.colorScheme.primary
                },
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val entries = listOf(
                Triple(
                    ChatViewMode.ALL,
                    stringResource(R.string.chat_view_mode_all),
                    Icons.Rounded.Subject,
                ),
                Triple(
                    ChatViewMode.DIALOG,
                    stringResource(R.string.chat_view_mode_dialog),
                    Icons.Rounded.FilterAlt,
                ),
                Triple(
                    ChatViewMode.TRACE,
                    stringResource(R.string.chat_view_mode_trace),
                    Icons.Rounded.Timeline,
                ),
            )
            ListPopupColumn {
                entries.forEachIndexed { index, (item, label, icon) ->
                    DropdownImpl(
                        item = DropdownItem(
                            text = label,
                            icon = { iconModifier ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = iconModifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                        optionSize = entries.size,
                        isSelected = item == mode,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            if (item != mode) onSelect(item)
                        },
                    )
                }
            }
        }
    }
}

private val TopBarMenuIconSize = 20.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onSearchMessages: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val capabilityText = stringResource(R.string.capability_overview_title)
            val searchText = stringResource(R.string.chat_search_action)
            val newConversationText = stringResource(R.string.action_new_conversation)
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val launchKimiWebText = kimiWebLabel
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            val workspaceText = stringResource(R.string.capability_workspace)
            // 菜单项与动作成对定义：顺序天然一致。早前是 menuItems + when(index) 两处维护，
            // 新增一项要同时改两个地方，漏改就会点错菜单。
            val entries: List<Pair<DropdownItem, () -> Unit>> = buildList {
                add(
                    DropdownItem(
                        text = capabilityText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenCapabilities,
                )
                // 会话工作区是「当前这段对话」的设置，入口就跟在会话相关动作后面，
                // 不再要求用户去设置或能力概览里找。
                add(
                    DropdownItem(
                        text = workspaceText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenWorkspace,
                )
                add(
                    DropdownItem(
                        text = searchText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onSearchMessages,
                )
                add(
                    DropdownItem(
                        text = newConversationText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.AddComment,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onNewConversation,
                )
                add(
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenTerminal,
                )
                add(
                    DropdownItem(
                        text = launchKimiWebText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(R.drawable.ic_kimi_code),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onLaunchKimiWeb,
                )
                add(
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenBrowser,
                )
                if (canStopKimiWeb) {
                    add(
                        DropdownItem(
                            text = stopKimiWebText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ) to onStopKimiWeb,
                    )
                }
            }
            ListPopupColumn {
                entries.forEachIndexed { index, (item, action) ->
                    DropdownImpl(
                        item = item,
                        optionSize = entries.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    // 上游 3.3.5 的标题表：Subagent 页没有对应字符串资源，沿用其硬编码标题。
    is AppRoute.Subagents -> "Subagent"
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Plugins -> stringResource(R.string.route_plugins)
    is AppRoute.Usage -> stringResource(R.string.route_usage)
    is AppRoute.RunInspector -> if (route.runId == null) {
        stringResource(R.string.route_runs)
    } else {
        stringResource(R.string.route_run_detail)
    }
    is AppRoute.Characters -> stringResource(R.string.route_characters)
    is AppRoute.CharacterDetail -> stringResource(R.string.route_character_detail)
    is AppRoute.CharacterEditor -> stringResource(R.string.route_character_editor)
    is AppRoute.CharacterPersona -> stringResource(R.string.route_character_persona)
    is AppRoute.CharacterMemory -> stringResource(R.string.route_character_memory)
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.EnvVars -> stringResource(R.string.env_vars_title)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.Capabilities -> stringResource(R.string.capability_overview_title)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.CommunityCatalog -> stringResource(R.string.route_community_catalog)
    is AppRoute.CommunityCatalogProvider -> stringResource(R.string.route_community_catalog_provider)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}

@Composable
private fun CharacterTopBarIdentity(character: RoleplayChatUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 32.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
