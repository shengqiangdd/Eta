package io.github.mangi.eta.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.MoreHoriz
<<<<<<< HEAD
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
=======
import androidx.compose.material.icons.rounded.Code
>>>>>>> origin/main
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold as CouiLayoutScaffold
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import com.itos.coui.topappbar.TopAppBarButtonGroup
import io.github.mangi.eta.R
<<<<<<< HEAD
import io.github.mangi.eta.data.model.ChatViewMode
=======
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
>>>>>>> origin/main
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.AgentTopBarChatHeader
import io.github.mangi.eta.ui.components.ChatActionIcons
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.VirtualDisplayPanel
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.coui.CouiBackButton
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
<<<<<<< HEAD
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
=======
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
>>>>>>> origin/main
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
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
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
<<<<<<< HEAD
    onConversationPin: (ConversationSummaryUi) -> Unit,
=======
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
>>>>>>> origin/main
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
    homeTitle: String? = null,
    homeCharacter: RoleplayChatUi? = null,
    homeModelPickerState: AgentModelPickerUiState? = null,
    isHomeStreaming: Boolean = false,
    onHomeModelSelected: (String) -> Unit = {},
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    val pageContent: @Composable () -> Unit = {
<<<<<<< HEAD
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
=======
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                val dark = CouiTheme.isDark
                val colors = CouiPageDefaults.themeColors(dark)
                val isHome = currentRoute is AppRoute.Home
                CouiLayoutScaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MiuixTheme.colorScheme.surface,
                    windowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        if (currentRoute !is AppRoute.Settings) {
                            CouiTopAppBar(
                                title = {
                                    if (isHome && homeModelPickerState != null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            AgentTopBarChatHeader(
                                                conversationTitle = homeTitle,
                                                character = homeCharacter,
                                                state = homeModelPickerState,
                                                isStreaming = isHomeStreaming,
                                                onModelSelected = onHomeModelSelected,
                                                onOpenCharacter = onOpenCharacter,
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = titleForRoute(currentRoute),
                                            style = LocalCouiTextStyle.current,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                navigationIcon = {
                                    if (isHome) {
                                        CouiTopAppBarIconButton(
                                            icon = Icons.AutoMirrored.Outlined.ShortText,
                                            contentDescription = stringResource(R.string.action_conversation_history),
                                            onClick = onOpenConversationPane,
                                            dark = dark,
                                            colors = colors,
                                        )
                                    } else {
                                        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
                                    }
                                },
                                actions = {
                                    if (isHome) {
                                        TopAppBarButtonGroup {
                                            CouiTopBarOverflowMenu(
                                                dark = dark,
                                                colors = colors,
                                                onNewConversation = onNewConversation,
                                                onOpenTerminal = onOpenTerminal,
                                                onLaunchKimiWeb = onLaunchKimiWeb,
                                                kimiWebLabel = kimiWebLabel,
                                                canStopKimiWeb = canStopKimiWeb,
                                                onStopKimiWeb = onStopKimiWeb,
                                                onRefreshKimiWeb = onRefreshKimiWeb,
                                                onOpenBrowser = onOpenBrowser,
                                            )
                                        }
                                    }
                                },
                                dark = dark,
                                colors = CouiTopAppBarDefaults.colors(
                                    backgroundColor = MiuixTheme.colorScheme.surface,
                                    titleColor = colors.title,
                                    dark = dark,
                                ),
                            )
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(padding)
                    }
                }
            }
            AppearanceUiStyle.MIUIX -> {
                // 首页顶栏高度固定，不参与折叠；未绑定顶栏的滚动行为会吞掉聊天列表的单向滑动。
                val scrollBehavior = if (currentRoute is AppRoute.Home) null else MiuixScrollBehavior()
                val backdrop = rememberTopBarBackdrop()
                val topBarColor = topBarContainerColor(backdrop)
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
                                    homeTitle = homeTitle,
                                    homeCharacter = homeCharacter,
                                    homeModelPickerState = homeModelPickerState,
                                    isHomeStreaming = isHomeStreaming,
                                    onHomeModelSelected = onHomeModelSelected,
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
                            .then(
                                if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                else Modifier,
                            ),
                    ) {
                        content(padding)
                    }
                }
>>>>>>> origin/main
            }
        }
    }

    ChatImagePreviewHost(modifier = modifier) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
<<<<<<< HEAD
                onConversationPin = onConversationPin,
=======
                onConversationTogglePin = onConversationTogglePin,
>>>>>>> origin/main
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
private fun CouiTopBarOverflowMenu(
    dark: Boolean,
    colors: com.itos.coui.layout.CouiPageColors,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    val anchor = rememberCouiMenuAnchor()

    val newConversationText = stringResource(R.string.action_new_conversation)
    val openTerminalText = stringResource(R.string.action_open_terminal)
    val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val autoReadText = stringResource(
        if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
    )
    val menuItems = remember(
        newConversationText,
        openTerminalText,
        kimiWebLabel,
        openBrowserText,
        stopKimiWebText,
        canStopKimiWeb,
        autoReadText,
        hasSpeechPlayback,
        autoReadEnabled,
    ) {
        buildList {
            add(CouiMenuItem(value = 0, text = newConversationText, icon = ChatActionIcons.NewChat))
            add(CouiMenuItem(value = 1, text = openTerminalText, icon = Icons.Rounded.Terminal))
            add(CouiMenuItem(value = 2, text = kimiWebLabel, icon = Icons.Rounded.Code))
            add(CouiMenuItem(value = 3, text = openBrowserText, icon = Icons.Rounded.Language))
            if (canStopKimiWeb) {
                add(CouiMenuItem(value = 4, text = stopKimiWebText, icon = Icons.Rounded.Stop))
            }
            if (hasSpeechPlayback) {
                add(
                    CouiMenuItem(
                        value = 5,
                        text = autoReadText,
                        icon = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    ),
                )
            }
        }
    }

    Box {
        CouiTopAppBarIconButton(
            icon = CouiIcons.More,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.couiMenuAnchor(anchor),
            dark = dark,
            colors = colors,
            onClick = {
                onRefreshKimiWeb()
                anchor.open { showMenu = true }
            },
        )
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = { item ->
                showMenu = false
                when (item.value) {
                    0 -> onNewConversation()
                    1 -> onOpenTerminal()
                    2 -> onLaunchKimiWeb()
                    3 -> onOpenBrowser()
                    4 -> onStopKimiWeb()
                    5 -> speechSettings?.let { current ->
                        scope.launch {
                            SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                        }
                    }
                }
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior?,
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
<<<<<<< HEAD
    onOpenCapabilities: () -> Unit,
    onOpenWorkspace: () -> Unit,
    chatViewMode: ChatViewMode,
    onChatViewModeChange: (ChatViewMode) -> Unit,
    onSearchMessages: () -> Unit,
=======
    homeTitle: String?,
>>>>>>> origin/main
    homeCharacter: RoleplayChatUi?,
    homeModelPickerState: AgentModelPickerUiState?,
    isHomeStreaming: Boolean,
    onHomeModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
) {
    val isHome = route is AppRoute.Home
<<<<<<< HEAD
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
=======
    if (isHome && homeModelPickerState != null) {
        HomeMiuixTopBar(
            color = color,
            onOpenConversationPane = onOpenConversationPane,
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
            onLaunchKimiWeb = onLaunchKimiWeb,
            kimiWebLabel = kimiWebLabel,
            canStopKimiWeb = canStopKimiWeb,
            onStopKimiWeb = onStopKimiWeb,
            onRefreshKimiWeb = onRefreshKimiWeb,
            onOpenBrowser = onOpenBrowser,
        ) {
            AgentTopBarChatHeader(
                conversationTitle = homeTitle,
                character = homeCharacter,
                state = homeModelPickerState,
                isStreaming = isHomeStreaming,
                onModelSelected = onHomeModelSelected,
                onOpenCharacter = onOpenCharacter,
>>>>>>> origin/main
            )
        }
        return
    }

<<<<<<< HEAD
    // 首页聊天舞台与浏览器、终端这类整屏工具页没有可驱动折叠的滚动列表，大标题会一直展开、
    // 白占一屏顶部；它们使用紧凑标题，其余二级内容页统一使用可折叠大标题。
    val compactTitle = isHome || route is AppRoute.Browser || route is AppRoute.Terminal
    if (compactTitle) {
=======
    val navigationIcon: @Composable () -> Unit = {
        MiuixBackButton(onClick = onBack)
    }
    val actions: @Composable RowScope.() -> Unit = {}

    if (route is AppRoute.Terminal) {
>>>>>>> origin/main
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

<<<<<<< HEAD
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
=======
@Composable
private fun HomeMiuixTopBar(
    color: Color,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    centerContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .height(56.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .padding(
                start = TopAppBarDefaults.NavigationIconPadding,
                end = TopAppBarDefaults.ActionIconPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenConversationPane) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ShortText,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_conversation_history),
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            centerContent()
        }
        TopBarOverflowMenu(
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
            onLaunchKimiWeb = onLaunchKimiWeb,
            kimiWebLabel = kimiWebLabel,
            canStopKimiWeb = canStopKimiWeb,
            onStopKimiWeb = onStopKimiWeb,
            onRefreshKimiWeb = onRefreshKimiWeb,
            onOpenBrowser = onOpenBrowser,
        )
>>>>>>> origin/main
    }
}

private val TopBarMenuIconSize = 20.dp

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
                                imageVector = ChatActionIcons.NewChat,
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
<<<<<<< HEAD
                }
=======
                } else {
                    emptyList()
                }) + if (hasSpeechPlayback) {
                    listOf(
                        DropdownItem(
                            text = autoReadText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp
                                    else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else emptyList()
>>>>>>> origin/main
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
<<<<<<< HEAD
    is AppRoute.CommunityCatalog -> stringResource(R.string.route_community_catalog)
    is AppRoute.CommunityCatalogProvider -> stringResource(R.string.route_community_catalog_provider)
=======
    is AppRoute.CommunityCatalog -> "从 models.dev 添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
>>>>>>> origin/main
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}
