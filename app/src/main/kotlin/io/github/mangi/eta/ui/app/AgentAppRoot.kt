package io.github.mangi.eta.ui.app

import android.Manifest
import androidx.core.net.toUri
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ChatViewMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.ConversationExportSink
import io.github.mangi.eta.data.repository.ConversationHistoryExporter
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentPluginsAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogProviderScreen
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.capabilities.CapabilityOverviewScreen
import io.github.mangi.eta.ui.screens.characters.CharacterDetailScreen
import io.github.mangi.eta.ui.screens.characters.CharacterEditorScreen
import io.github.mangi.eta.ui.screens.characters.CharacterLibraryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterMemoryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterPersonaScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.plugins.AgentPluginsScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.subagent.SubagentSettingsScreen
import io.github.mangi.eta.ui.screens.terminal.EnvVarsScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.usage.UsageScreen
import io.github.mangi.eta.ui.screens.runs.RunInspectorScreen
import io.github.mangi.eta.ui.voice.SpeechOssScreen
import io.github.mangi.eta.ui.voice.SpeechRecognitionScreen
import io.github.mangi.eta.ui.voice.SpeechSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechSynthesisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    openRoute: AppRoute? = null,
    openRouteRequestId: Int = 0,
    assistantConversationKey: String? = null,
    requestedConversationId: String? = null,
    onRequestedConversationOpened: () -> Unit = {},
    requestedArchivedConversation: ArchivedConversationRequest? = null,
    onArchivedConversationOpened: () -> Unit = {},
    openSpeechSettings: Boolean = false,
    openTerminal: Boolean = false,
    onTerminalOpened: () -> Unit = {},
    onSpeechSettingsOpened: () -> Unit = {},
    onAssistantConversationOpened: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uiScope = rememberCoroutineScope()
    // 聊天页视图模式跟着外观偏好通道走，改动后 LocalAppearanceSettings 会自动重组聊天页。
    val chatViewMode = LocalAppearanceSettings.current.chatViewMode
    val setChatViewMode: (ChatViewMode) -> Unit = { mode ->
        uiScope.launch {
            AppearanceSettingsRepository.update { it.copy(chatViewMode = mode) }
        }
    }
    // 「在对话中搜索」是一个一次性的打开意图，用递增计数表达：聊天页只在它变化时激活搜索栏，
    // 不会因为别的重组把已经关掉的搜索栏重新弹出来。
    var chatSearchRequest by remember { mutableIntStateOf(0) }
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    // 调试直达（debuggable 包 + intent extra）：验证深层页面时不用一层层点抽屉。
    // key 里带上请求计数：进程已在跑时再发一次同一路由只走 onNewIntent，openRoute 值不变，
    // 只靠它做 key 的话这次请求会被吞掉（真机上就是这么"点了没反应"的）。
    LaunchedEffect(openRoute, openRouteRequestId) {
        if (openRoute != null && backStack.lastOrNull() != openRoute) backStack.add(openRoute)
    }
    LaunchedEffect(openSpeechSettings) {
        if (openSpeechSettings) {
            if (backStack.lastOrNull() != AppRoute.SpeechSettings) backStack.add(AppRoute.SpeechSettings)
            onSpeechSettingsOpened()
        }
    }
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            if (backStack.lastOrNull() != AppRoute.Terminal) backStack.add(AppRoute.Terminal)
            onTerminalOpened()
        }
    }
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    var navigationResetKey by rememberSaveable { mutableIntStateOf(0) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    // 会话运行中保持屏幕唤醒：窗口 flag 负责前台，屏幕唤醒锁负责切到其他应用后的整机保屏。
    val rootView = LocalView.current
    val keepScreenPrefs = remember(context.applicationContext) {
        Prefs.localAgentPreferences(context.applicationContext)
    }
    var keepScreenEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON))
    }
    DisposableEffect(keepScreenPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_KEEP_SCREEN_ON) {
                rootView.post {
                    val enabled = Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON)
                    keepScreenEnabled = enabled
                    agentState.updateKeepScreenEnabled(enabled)
                }
            }
        }
        keepScreenPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { keepScreenPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 保屏用「有会话在跑」而不是 homeState：homeState 只反映当前选中的会话，
    // 任务运行中切到别的会话时它会变成不运行的状态，屏幕就会照常息屏。
    // 暂停同样按全局算：只有「运行且未暂停」的会话才需要保屏，否则暂停任务后一直常亮。
    val keepScreenOn = AgentKeepScreenOnPolicy.shouldKeep(
        enabled = keepScreenEnabled,
        streaming = agentState.anyRunningUnpausedConversation,
        paused = false,
    )
    LaunchedEffect(keepScreenOn) { rootView.keepScreenOn = keepScreenOn }
    DisposableEffect(rootView) { onDispose { rootView.keepScreenOn = false } }
    val skills = appViewModel.skills
    val plugins = appViewModel.plugins
    val memory = appViewModel.memory
    val permissionHealth = appViewModel.permissionHealth
    val characterStore = viewModel<CharacterLibraryViewModel>().store
    val communityCatalogStore = viewModel<CommunityCatalogViewModel>().store
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest(permissionHealth::refreshPermissionHealth)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHealth.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    RootAccess.refresh(context)
                    appViewModel.refreshKimiWeb()
                    permissionHealth.refreshPermissionHealth()
                    agentState.refreshRuntimeResults()
                }
                // 退到后台立刻落盘：进程随后可能被系统回收，
                // 不会再有下一次「用户操作触发保存」的机会。
                Lifecycle.Event.ON_STOP -> agentState.flushPersistence()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var conversationPaneOpen by remember { mutableStateOf(false) }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    // 自动备份：启动后立即检查一次，之后每 30 分钟看一次是否到期。
    // 用固定节拍而不是精确闹钟：Eta 是常驻应用，用户回到前台时大概率已经在节拍上；
    // 真正的「该不该备」由 lastBackupAt + 间隔决定，这里的节拍不会多写文件。
    LaunchedEffect(Unit) {
        while (true) {
            try {
                agentState.runAutoBackupIfDue()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // 自动备份失败不该影响启动或使用；原因已写进设置的 lastError。
            }
            delay(30 * 60 * 1000L)
        }
    }

    LaunchedEffect(requestedConversationId, agentState.conversationsLoaded) {
        val conversationId = requestedConversationId ?: return@LaunchedEffect
        // 会话列表还没载入时不能把请求消费掉：冷启动下会话必然找不到，
        // 一旦清空 requestedConversationId，载入完成后就没人再打开它了。
        // 载入完成会改变 conversationsLoaded，自动重跑这一段。
        if (!agentState.selectConversation(conversationId)) {
            // 已经载入过却还是找不到，说明会话真的不在了；消费掉请求，不让它永久悬挂。
            if (agentState.conversationsLoaded) onRequestedConversationOpened()
            return@LaunchedEffect
        }
        conversationPaneOpen = false
        navigator.popToHome()
        onRequestedConversationOpened()
    }

    // 系统分享 / 选中文本：Activity 把内容投进 AgentShareIngress，这里负责落到新会话草稿。
    // 读取 Compose 状态而不是一次性回调：分享可能在冷启动（Compose 尚未组合）时到达。
    val sharedContentRequest = AgentShareIngress.pending
    LaunchedEffect(sharedContentRequest?.token) {
        val request = sharedContentRequest ?: return@LaunchedEffect
        AgentShareIngress.consume(request.token)
        agentState.acceptSharedContent(request.plan)
        conversationPaneOpen = false
        navigator.popToHome()
    }

    LaunchedEffect(requestedArchivedConversation) {
        val request = requestedArchivedConversation ?: return@LaunchedEffect
        // 外部入口的结果要先导入归档才有会话；找不到时停在当前界面，不切到无关的会话。
        if (agentState.openArchivedConversation(request.source, request.conversationKey)) {
            conversationPaneOpen = false
            navigator.popToHome()
        }
        onArchivedConversationOpened()
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            conversationPaneOpen = false
            // 接管落到主聊天舞台：与主界面同一页面、同一侧边对话列表，不再开独立对话页。
            navigator.popToHome()
        }
        onAssistantConversationOpened(opened)
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        if (navigator.pop()) return
        // 已经在栈底（首页）再按返回：退到后台，不要 finish。
        // finish 会把唯一的 Activity 连同任务一起移除（实测 dumpsys 里该任务计数变 0），
        // 虚拟屏 / 单 Activity 场景下进程随后被回收 —— 用户看到的是「按返回，App 直接没了」，
        // 而不是回到桌面。退到后台既保留最近任务里的现场，也不影响从桌面重新进入。
        (context as? Activity)?.moveTaskToBack(true)
    }

    // 系统返回键（物理键 / 返回手势）也要走同一条路径。
    // 之前只把 popRoute 接给了界面上的返回按钮，系统返回没有根级处理 → 直接落到 Activity 的
    // 默认实现，栈底时 Activity 被 finish（真机 dumpsys 里 task 的 Activities 变成 []），
    // 用户看到的就是「按返回，App 直接没了」。有导航栈时先回退，栈底才退到后台。
    // 子页面自己注册的 BackHandler 在更内层、后注册，仍优先于这里。
    BackHandler { popRoute() }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        conversationPaneOpen = false
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = agentState.conversationPaneState,
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onLaunchKimiWeb = {
                requestExecutionNotifications()
                if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
                    appViewModel.launchKimiWeb { result ->
                        if (result is KimiWebLaunchResult.Failed) {
                            Toast.makeText(
                                context,
                                result.message(context),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pushRoute(AppRoute.LinuxEnvironment)
                }
            },
            kimiWebLabel = appViewModel.kimiWebState.actionLabel(context),
            canStopKimiWeb = appViewModel.kimiWebState.canStop,
            onStopKimiWeb = appViewModel::stopKimiWeb,
            onRefreshKimiWeb = appViewModel::refreshKimiWeb,
            onOpenBrowser = { pushRoute(AppRoute.Browser()) },
            onOpenCapabilities = { pushRoute(AppRoute.Capabilities) },
            onOpenWorkspace = { pushRoute(AppRoute.Workspace) },
            chatViewMode = chatViewMode,
            onChatViewModeChange = setChatViewMode,
            onSearchMessages = { chatSearchRequest += 1 },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationPin = { conversation ->
                agentState.toggleConversationPin(conversation.id)
            },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationStop = { conversation ->
                agentState.stopConversationRun(conversation.id)
            },
            onConversationExport = { conversation ->
                uiScope.launch {
                    try {
                        val fileName = ConversationMarkdownExporter.defaultFileName(
                            title = conversation.title.ifBlank { conversation.preview },
                            fallback = resources.getString(R.string.conversation_export_default_name),
                        )
                        val markdown = agentState.exportConversationMarkdown(conversation.id)
                            ?: error(resources.getString(R.string.conversation_export_failed))
                        // 与「完整历史」同一理由：系统 CreateDocument 会把任务连同选择器迁到主屏，
                        // 虚拟屏里根本用不了。Markdown 是一整串文本，直接写公共下载目录。
                        val sink = ConversationExportSink.create(
                            context = context,
                            fileName = fileName,
                            mimeType = "text/markdown",
                        )
                        try {
                            withContext(Dispatchers.IO) {
                                sink.stream.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                            }
                        } catch (failure: Throwable) {
                            sink.abort()
                            throw failure
                        }
                        sink.finish()
                        Toast.makeText(
                            context,
                            resources.getString(R.string.conversation_exported),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.conversation_export_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onConversationExportHistory = { conversation ->
                uiScope.launch {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.conversation_export_history_running),
                        Toast.LENGTH_SHORT,
                    ).show()
                    try {
                        val fileName = ConversationHistoryExporter.defaultFileName(
                            title = conversation.title.ifBlank { conversation.preview },
                            fallback = resources.getString(R.string.conversation_export_default_name),
                        )
                        // 不弹系统「保存到…」：那个选择器由系统决定挂在哪个显示上，
                        // 实测会把任务连同选择器一并迁到主屏（虚拟屏里根本用不了）。
                        // 直接写公共下载目录，流式写保证大会话（34 MB）不会拼成整串而 OOM。
                        val sink = ConversationExportSink.create(
                            context = context,
                            fileName = fileName,
                            mimeType = ConversationHistoryExporter.MIME_TYPE,
                        )
                        val summary = try {
                            sink.stream.use { output ->
                                agentState.exportConversationHistory(conversation.id, output)
                            }
                        } catch (failure: Throwable) {
                            sink.abort()
                            throw failure
                        }
                        sink.finish()
                        Toast.makeText(
                            context,
                            resources.getString(
                                R.string.conversation_export_history_saved,
                                sink.label + " · " + Formatter.formatShortFileSize(context, summary.bytes),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.conversation_export_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenCharacters = { pushRoute(AppRoute.Characters) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
            homeCharacter = if (route is AppRoute.Home) agentState.homeState.roleplayChatUi() else null,
            onOpenCharacter = {
                agentState.homeState.roleplay?.let { pushRoute(AppRoute.CharacterDetail(it.characterId)) }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                content()
            }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = swipeBackDirection.takeIf {
        LocalAppearanceSettings.current.swipeDismissEnabled
    }
    key(navigationResetKey) {
        NavDisplay(
            backStack = backStack,
            onBack = { popRoute() },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<AppRoute.Home>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Home) {
                    AgentHomeScreen(
                        state = agentState.homeState,
                        modelPickerState = agentState.modelPickerState,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        missingPermissionIds = permissionHealth.permissionHealthState.issueIds,
                        permissionIssuesKnown = permissionHealth.permissionHealthState.loaded,
                        onOpenPermissions = { pushRoute(AppRoute.Permissions) },
                        systemExit = agentState.lastExitCause,
                        onDismissSystemExit = { agentState.dismissExitNotice() },
                        chatViewMode = chatViewMode,
                        onChatViewModeChange = setChatViewMode,
                        searchOpenRequests = chatSearchRequest,
                        onOpenRunDetail = { runId -> pushRoute(AppRoute.RunInspector(runId)) },
                        onAction = { action ->
                            when (action) {
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                AgentHomeAction.CompactContext -> agentState.compactCurrentContext()
                                AgentHomeAction.LoadEarlierMessages -> agentState.loadEarlierMessages()
                                is AgentHomeAction.HistoryViewportChanged ->
                                    agentState.updateHistoryViewport(action.atLatest)
                                is AgentHomeAction.SteerAndSubmitMessage -> {
                                    requestExecutionNotifications()
                                    agentState.steerCurrentMessage(action.text)
                                }
                                is AgentHomeAction.AnswerAsk -> {
                                    requestExecutionNotifications()
                                    agentState.answerPendingAsk(action.text)
                                }
                                is AgentHomeAction.SteerQueuedMessage -> {
                                    requestExecutionNotifications()
                                    agentState.steerQueuedMessage(action.id)
                                }
                                is AgentHomeAction.RecallQueuedMessage ->
                                    agentState.recallQueuedMessage(action.id, action.currentDraftText)
                                is AgentHomeAction.RemoveQueuedMessage -> agentState.removeQueuedMessage(action.id)
                                is AgentHomeAction.DraftChanged -> agentState.updateDraft(action.text)
                                is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId)
                                is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                AgentHomeAction.ToggleRunPause -> agentState.toggleCurrentRunPause()
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentHomeAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentHomeAction.DeleteMessage -> {
                                    agentState.messageRevisionImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (agentState.homeState.roleplay != null || impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser())
                            }
                        },
                        historyLoadState = agentState.historyLoadState,
                        isLoadingConversation = agentState.isLoadingSelectedConversation,
                        isDrawerOpen = conversationPaneOpen,
                    )
                }
            }
            entry<AppRoute.Browser>(swipeDismiss = swipeDismiss) {
                // 浏览器会话按 sessionKey 隔离（上游 3.3.5）：手动浏览与各会话各用一页，退出再进来不丢页面。
                val browserSessionKey = (backStack.lastOrNull() as? AppRoute.Browser)?.sessionKey
                    ?: BrowserSessionRegistry.MANUAL_SESSION_KEY
                RoutedShell(route = AppRoute.Browser(browserSessionKey)) {
                    AgentBrowserScreen(
                        session = BrowserSessionRegistry.get(browserSessionKey),
                        onBack = { popRoute() },
                    )
                }
            }
            entry<AppRoute.Terminal>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                    )
                }
            }
            entry<AppRoute.Tools>(swipeDismiss = swipeDismiss) {
                // 工具目录（96 张卡片 × 2 次资源查找）只在打开这一页时构建：
                // 放在根组件里会在首帧同步跑一遍，而首页根本用不到它。
                val toolsState = remember { buildToolsState(context) }
                AgentToolsScreen(
                    state = toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser())
                            AgentToolsAction.OpenWebToolsSettings -> pushRoute(AppRoute.WebToolsSettings)
                            AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                            AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                        }
                    },
                )
            }
            entry<AppRoute.Skills>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    skills.refreshSkills()
                }
                AgentSkillsScreen(
                    state = skills.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZipPath -> skills.importSkillZipPath(action.path)
                            AgentSkillsAction.ConfirmZipReplacement -> skills.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> skills.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> skills.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> skills.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> skills.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> skills.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Plugins>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    plugins.refresh()
                }
                AgentPluginsScreen(
                    state = plugins.pluginsState,
                    onAction = { action ->
                        when (action) {
                            AgentPluginsAction.NavigateBack -> popRoute()
                            is AgentPluginsAction.TogglePlugin -> plugins.toggle(action.pluginId, action.enabled)
                            is AgentPluginsAction.UninstallPlugin -> plugins.uninstall(action.pluginId)
                            is AgentPluginsAction.RollbackPlugin ->
                                plugins.rollback(action.pluginId, action.version)
                            is AgentPluginsAction.ImportZipPath -> plugins.importZipFile(action.path)
                            is AgentPluginsAction.PrepareLinkInstall -> plugins.prepareLinkInstall(action.url)
                            AgentPluginsAction.ConfirmLinkInstall -> plugins.confirmLinkInstall()
                            is AgentPluginsAction.UpdateFromSource ->
                                plugins.updateFromSource(action.pluginId)
                            AgentPluginsAction.CheckUpdates -> plugins.checkUpdates()
                            AgentPluginsAction.CancelLinkInstall -> plugins.cancelLinkInstall()
                            AgentPluginsAction.DismissNotice -> plugins.dismissNotice()
                        }
                    },
                )
            }
            entry<AppRoute.Characters>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.Characters) {
                    if (backStack.lastOrNull() == AppRoute.Characters) characterStore.loadLibrary()
                }
                CharacterLibraryScreen(characterStore, { if (navigator.current() == AppRoute.Characters) pushRoute(it) }, ::popRoute)
            }
            entry<AppRoute.CharacterDetail>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadDetail(route.characterId)
                }
                CharacterDetailScreen(route.characterId, characterStore, { if (navigator.current() == route) pushRoute(it) }, ::popRoute) { binding, greeting ->
                    if (navigator.current() == route) {
                        agentState.startCharacterConversation(binding, greeting)
                        conversationPaneOpen = false
                        navigator.popToHome()
                        // 开始新故事直接呈现首页；重置导航呈现态，避免多层退栈扫过角色列表。
                        navigationResetKey++
                    }
                }
            }
            entry<AppRoute.CharacterEditor>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadEditor(route.characterId)
                }
                CharacterEditorScreen(route.characterId, characterStore, ::popRoute) { id ->
                    if (navigator.current() == route) navigator.replace(AppRoute.CharacterDetail(id))
                }
            }
            entry<AppRoute.CharacterPersona>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.CharacterPersona) {
                    if (backStack.lastOrNull() == AppRoute.CharacterPersona) characterStore.loadPersona()
                }
                CharacterPersonaScreen(characterStore) {
                    if (navigator.current() == AppRoute.CharacterPersona) popRoute()
                }
            }
            entry<AppRoute.CharacterMemory>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadMemory(route.characterId)
                }
                CharacterMemoryScreen(route.characterId, characterStore, ::popRoute)
            }
            entry<AppRoute.Permissions>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    permissionHealth.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = permissionHealth.permissionHealthState,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    LOCAL_NETWORK_PERMISSION_ITEM_ID -> requestLocalNetworkPermission()
                                    "calendar" -> locationPermissionLauncher.launch(io.github.mangi.eta.agent.device.CalendarPermissions.requested)
                                    "notification_policy" -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    "package:${context.packageName}".toUri()
                                                )
                                            )
                                        }
                                    }
                                    "background" -> {
                                        if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                            uiScope.launch(Dispatchers.IO) {
                                                BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                    it.execute(
                                                        BatteryOptimizationCommand.powerControlCommand(context.packageName),
                                                    )
                                                }
                                            }
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    "package:${context.packageName}".toUri()
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            "package:${context.packageName}".toUri()
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                permissionHealth.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "notifications" -> {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    }
                                    "root" -> pushRoute(AppRoute.SystemEnhance)
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = swipeDismiss) {
                SystemEnhanceScreen(
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                            AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                        }
                    },
                )
            }
            entry<AppRoute.Capabilities>(swipeDismiss = swipeDismiss) {
                // 总览要显示「技能启用数」，而技能列表原先只在技能页加载 —— 直接从菜单进入总览时
                // 会一直显示 0/0（不是真没有技能，而是没读过）。这里按需触发一次加载：
                // 只在「还没加载过」或「上次读取失败且列表为空」时发起，成功加载后不重复扫描。
                LaunchedEffect(Unit) {
                    val state = skills.skillsState
                    if (state.isLoading || (state.skills.isEmpty() && state.notice?.isError == true)) {
                        skills.refreshSkills()
                    }
                }
                // 工具数量按「当前设备可用」口径统计，与工具页默认 tab 的数字一致；
                // 这里只在进入总览页时计算一次，不额外轮询任何数据源。
                val deviceCapabilities = rememberDeviceCapabilities()
                val toolsState = remember { buildToolsState(context) }
                val toolCount = remember(toolsState, deviceCapabilities) {
                    projectToolGroups(
                        groups = toolsState.groups,
                        showAll = false,
                        rootGranted = deviceCapabilities.root.isGranted,
                        colorOs = deviceCapabilities.tools.colorOs,
                    ).sumOf { group -> group.tools.size }
                }
                CapabilityOverviewScreen(
                    missingPermissionCount = permissionHealth.permissionHealthState.issueCount,
                    toolCount = toolCount,
                    skillTotal = skills.skillsState.skills.size,
                    skillEnabled = skills.skillsState.skills.count { it.enabled },
                    skillLoading = skills.skillsState.isLoading,
                    skillUnavailable = !skills.skillsState.isLoading &&
                        skills.skillsState.skills.isEmpty() &&
                        skills.skillsState.notice?.isError == true,
                    providerCount = agentState.modelPickerState.providerGroups.size,
                    currentModel = agentState.modelPickerState.selectedModel?.displayName,
                    onOpen = { target ->
                        // 已在总览页时点自身入口不产生重复路由。
                        if (target != AppRoute.Capabilities) pushRoute(target)
                    },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.Workspace>(swipeDismiss = swipeDismiss) {
                WorkspaceScreen(
                    conversationId = agentState.conversationPaneState.selectedConversationId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.Settings>(swipeDismiss = swipeDismiss) {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.SpeechSettings>(swipeDismiss = swipeDismiss) {
                SpeechSettingsScreen(
                    onBack = ::popRoute,
                    onNavigate = { route -> pushRoute(route) },
                )
            }
            entry<AppRoute.SpeechRecognition>(swipeDismiss = swipeDismiss) {
                SpeechRecognitionScreen(
                    onBack = ::popRoute,
                    onOpenOss = { pushRoute(AppRoute.SpeechOss) },
                )
            }
            entry<AppRoute.SpeechSynthesis>(swipeDismiss = swipeDismiss) {
                SpeechSynthesisScreen(onBack = ::popRoute)
            }
            entry<AppRoute.SpeechOss>(swipeDismiss = swipeDismiss) {
                SpeechOssScreen(onBack = ::popRoute)
            }
            entry<AppRoute.WebToolsSettings>(swipeDismiss = swipeDismiss) {
                WebToolsSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Subagents>(swipeDismiss = swipeDismiss) {
                SubagentSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeDismiss) {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DataBackup>(swipeDismiss = swipeDismiss) {
                DataBackupScreen(
                    context = context,
                    onBack = ::popRoute,
                    onExport = agentState::exportBackup,
                    onInspect = agentState::inspectBackup,
                    onSnapshot = agentState::snapshotBeforeImport,
                    onImport = agentState::importBackup,
                )
            }
            entry<AppRoute.Memory>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    memory.refreshMemory()
                }
                AgentMemoryScreen(
                    state = memory.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            is AgentMemoryAction.ToggleEnabled -> memory.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> memory.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> memory.saveMemory()
                            AgentMemoryAction.Clear -> memory.clearMemory()
                            AgentMemoryAction.DismissNotice -> memory.dismissMemoryNotice()
                            is AgentMemoryAction.OpenSection -> memory.openSection(action.title)
                            is AgentMemoryAction.SectionDraftChanged ->
                                memory.updateSectionDraft(action.content)
                            AgentMemoryAction.SaveSection -> memory.saveSection()
                            AgentMemoryAction.CancelSection -> memory.cancelSection()
                        }
                    },
                )
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = swipeDismiss) {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.EnvVars>(swipeDismiss = swipeDismiss) {
                EnvVarsScreen(context = context, onBack = ::popRoute)
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = swipeDismiss) {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = swipeDismiss) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.Usage>(swipeDismiss = swipeDismiss) {
                UsageScreen(onBack = ::popRoute)
            }
            entry<AppRoute.RunInspector>(swipeDismiss = swipeDismiss) { route ->
                RunInspectorScreen(
                    runId = route.runId,
                    onBack = ::popRoute,
                    onOpenRun = { runId -> pushRoute(AppRoute.RunInspector(runId)) },
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = swipeDismiss) {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.CommunityCatalog>(swipeDismiss = swipeDismiss) {
                CommunityCatalogScreen(
                    store = communityCatalogStore,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.CommunityCatalogProvider>(swipeDismiss = swipeDismiss) { route ->
                CommunityCatalogProviderScreen(
                    catalogId = route.catalogId,
                    store = communityCatalogStore,
                    onImported = { providerId -> navigator.replace(AppRoute.ModelProviderDetail(providerId)) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServers>(swipeDismiss = swipeDismiss) {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = swipeDismiss) { route ->
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    onBack = ::popRoute
                )
            }
        }
    }

    characterStore.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.route_characters),
            summary = notice,
            cornerRadius = DialogDefaults.CornerRadius,
            onDismissRequest = characterStore::dismissNotice,
        ) {
            top.yukonga.miuix.kmp.basic.TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = characterStore::dismissNotice,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_rename_title),
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(
                if (conversation.isActiveRun) {
                    R.string.conversation_stop_and_delete_message
                } else {
                    R.string.conversation_delete_message
                },
            ),
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(
                    if (conversation.isActiveRun) {
                        R.string.conversation_stop_and_delete
                    } else {
                        R.string.action_delete
                    },
                ),
                destructive = true,
                onCancel = { conversationDeleteTarget = null },
                onConfirm = {
                    agentState.deleteConversation(conversation.id)
                    conversationDeleteTarget = null
                },
            )
        }
    }

    messageDeleteTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_delete_message_body)
            } else {
                pluralStringResource(
                    R.plurals.conversation_delete_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { messageDeleteTarget = null },
                onConfirm = {
                    agentState.deleteMessageTurn(target.messageId)
                    messageDeleteTarget = null
                },
            )
        }
    }

    messageRegenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_regenerate_current_turn)
            } else {
                pluralStringResource(
                    R.plurals.conversation_regenerate_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageRegenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_regenerate),
                destructive = true,
                onCancel = { messageRegenerateTarget = null },
                onConfirm = {
                    agentState.regenerateMessage(target.messageId)
                    messageRegenerateTarget = null
                },
            )
        }
    }
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)
