package io.github.mangi.eta.ui

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.device.VirtualDisplayDebugHost
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.StartupTrace
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.AgentShareIngress
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.SharedContentIntentParser
import io.github.mangi.eta.ui.app.SharedContentIntentReader
import io.github.mangi.eta.ui.app.installStartupSplash
import io.github.mangi.eta.ui.components.AgentComposerBridge
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var requestedConversationId by mutableStateOf<String?>(null)
    private var requestedArchivedConversation by mutableStateOf<ArchivedConversationRequest?>(null)
    private var appliedPredictiveBackEnabled = true
    private var speechSettingsRequested by mutableStateOf(false)
    private var terminalRequested by mutableStateOf(false)

    private var debugOpenRoute: AppRoute? = null

    /**
     * 直达请求计数：同一个路由被连续请求两次也必须再触发一次。
     *
     * 原先 [debugOpenRoute] 是 `by lazy`（读一次就缓存），于是进程已经在跑时再发一次
     * `am start --es eta.route plugins` 只会走 onNewIntent —— `setIntent` 换掉了 intent，
     * 但 lazy 值早就算过（null），Compose 侧 `LaunchedEffect(openRoute)` 看不到变化，
     * 界面停在原地。实测过一次，dump 出来还是对话页，就是踩了这个坑。
     */
    private var debugOpenRouteTick by mutableIntStateOf(0)

    /**
     * 调试直达路由：`am start -n io.github.mangi.eta/.ui.MainActivity --es eta.route plugins`。
     *
     * 为什么需要：验证一个深层页面（插件、MCP、技能）只能靠一层层点抽屉，
     * 而抽屉在动画期间无障碍节点会持续失效，实测点偏过好几次。只在 debuggable 包里生效，
     * 正式版即使被外部 intent 指定也会忽略。
     */
    private fun consumeDebugOpenRoute(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent == null) return
        consumeDebugVirtualDisplay(intent)
        consumeInjectText(intent)
        val route = when (intent.getStringExtra(EXTRA_OPEN_ROUTE)) {
            "plugins" -> AppRoute.Plugins
            "settings" -> AppRoute.Settings
            "mcp" -> AppRoute.McpServers
            "skills" -> AppRoute.Skills
            "tools" -> AppRoute.Tools
            "env" -> AppRoute.EnvVars
            // 运行记录 / 运行详情：界面自测要用真实记录看排版，而它藏在设置二级页里。
            // 带 eta.run_id 时直接进那一次的详情，避免在虚拟屏上点击（会触发任务迁移）。
            "runs" -> AppRoute.RunInspector(
                intent.getStringExtra(EXTRA_RUN_ID)?.takeIf(String::isNotBlank),
            )
            else -> null
        } ?: return
        debugOpenRoute = route
        debugOpenRouteTick++
        // 消费后立刻抹掉 extra：Activity 因配置变化重建时，不会把用户莫名再推回这个页面。
        intent.removeExtra(EXTRA_OPEN_ROUTE)
    }

    /**
     * 测试版专用：`--es eta.debug.virtual_display create|release`。
     *
     * 虚拟屏只能由 Agent 工具调用创建，而 canary 通常没有模型凭据；这个入口让面板、
     * 预览与输入链路可以在测试版上验证，不必为了看一眼 UI 反复装正式包。
     * 正式包被 [ApplicationInfo.FLAG_DEBUGGABLE] 挡掉（本函数只在 consumeDebugOpenRoute 后调用）。
     */
    private fun consumeDebugVirtualDisplay(intent: Intent) {
        when (intent.getStringExtra(EXTRA_DEBUG_VIRTUAL_DISPLAY)) {
            "create" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.create(this),
            )
            "release" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.release(),
            )
            "create_canvas" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.createCanvas(this),
            )
            "release_canvas" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.releaseCanvas(this),
            )
            "probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.probe(
                    this,
                    intent.getIntExtra(EXTRA_DEBUG_DISPLAY_ID, -1),
                    intent.getStringExtra(EXTRA_DEBUG_PROBE_ACTION),
                    intent.getIntExtra(EXTRA_DEBUG_MAX_NODES, 60),
                ),
            )
            "tool_probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.toolProbe(this),
            )
            "workspace_probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.workspaceProbe(this),
            )
            "step_probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.stepProbe(
                    this,
                    intent.getStringExtra(EXTRA_DEBUG_INPUT_PKG),
                ),
            )
            "scope_probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.scopeProbe(
                    this,
                    intent.getStringExtra(EXTRA_DEBUG_INPUT_PKG),
                ),
            )
            "text_probe" -> android.util.Log.i(
                "EtaVirtualDisplayDebug",
                VirtualDisplayDebugHost.textProbe(
                    this,
                    intent.getStringExtra(EXTRA_DEBUG_INPUT_PKG),
                ),
            )
            else -> return
        }
        intent.removeExtra(EXTRA_DEBUG_VIRTUAL_DISPLAY)
    }

    /**
     * 虚拟屏文本注入（仅本进程）：把文本直接 commit 到当前焦点输入框。
     *
     * 走 am start extra 而不是系统剪贴板：剪贴板会弹「已复制」并可能惊动主屏输入法，
     * 在虚拟屏场景里就是干扰用户。中文无法用 input text 注入（KeyCharacterMap 返回 null），
     * 所以这条通道是虚拟屏中文输入的主路径。
     */
    private fun consumeInjectText(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_INJECT_TEXT)?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(EXTRA_INJECT_TEXT)
        // 优先写 Compose 输入框状态（不依赖 IME、不碰剪贴板）；没有输入框时再退回 InputConnection。
        if (AgentComposerBridge.appendText(text)) return
        window?.decorView?.post {
            val focus = window.decorView.findFocus() ?: return@post
            val connection = focus.onCreateInputConnection(EditorInfo()) ?: return@post
            connection.commitText(text, 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.markActivityStart()
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        consumeSharedContent(intent)
        consumeDebugOpenRoute(intent)
        // 首帧不再等 DataStore（冷启动首次读取要几百毫秒）：先用上次快照把界面画出来，
        // 真实外观由下面的 settingsFlow 纠正；启动画面因此不会一直挂在系统 Splash 上。
        val initialAppearance = AppearanceSettingsRepository.snapshot()
        appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = initialAppearance)

            LaunchedEffect(appearance.themeMode) {
                updateApplicationNightMode(appearance.themeMode)
            }

            LaunchedEffect(appearance.predictiveBackEnabled) {
                val enabled = appearance.predictiveBackEnabled
                if (enabled != appliedPredictiveBackEnabled &&
                    PredictiveBackController.apply(applicationInfo, enabled)
                ) {
                    appliedPredictiveBackEnabled = enabled
                    recreateWithoutTransition()
                }
            }

            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = true,
                onResolvedDarkModeChange = ::updateSystemBars,
            ) {
                AgentAppRoot(
                    openRoute = debugOpenRoute,
                    openRouteRequestId = debugOpenRouteTick,
                    assistantConversationKey = assistantConversationKey,
                    requestedConversationId = requestedConversationId,
                    onRequestedConversationOpened = { requestedConversationId = null; intent?.action = null },
                    requestedArchivedConversation = requestedArchivedConversation,
                    onArchivedConversationOpened = { requestedArchivedConversation = null; intent?.action = null },
                    openSpeechSettings = speechSettingsRequested,
                    openTerminal = terminalRequested,
                    onTerminalOpened = { terminalRequested = false; intent?.action = null },
                    onSpeechSettingsOpened = { speechSettingsRequested = false; intent?.action = null },
                    onAssistantConversationOpened = { opened ->
                        assistantConversationKey = null
                        if (opened) {
                            EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                        }
                    },
                )
            }
        }
        contentReady = true
        StartupTrace.mark("content-composed")
        logFirstFrame()
    }

    /** 首帧耗时打点：只在第一帧记一次，之后立即摘掉监听，不影响后续绘制。 */
    private fun logFirstFrame() {
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var logged = false
            override fun onDraw() {
                if (logged) return
                logged = true
                StartupTrace.mark("first-frame")
                decor.post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                }
            }
        }
        observer.addOnDrawListener(listener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
        consumeSharedContent(intent)
        consumeDebugOpenRoute(intent)
    }

    /**
     * 系统分享 / 选中文本进入 Eta。
     *
     * 解析失败（空文本、非本机 uri、不是分享 action）就当没收到：用户在别的应用里
     * 点分享，Eta 不响应或没有可接收的内容都不该变成一条打扰性提示。
     */
    private fun consumeSharedContent(intent: Intent?) {
        val input = SharedContentIntentReader.read(intent, contentResolver) ?: return
        val plan = SharedContentIntentParser.parse(input) ?: return
        AgentShareIngress.offer(plan)
        // 配置变化重建 Activity 时系统会把同一个 intent 再投递一次；分享只能消费一次。
        intent?.apply {
            action = null
            removeExtra(Intent.EXTRA_TEXT)
            removeExtra(Intent.EXTRA_SUBJECT)
            removeExtra(Intent.EXTRA_STREAM)
            removeExtra(Intent.EXTRA_PROCESS_TEXT)
            clipData = null
        }
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_TERMINAL) {
            terminalRequested = true
            return
        }
        if (intent?.action == ACTION_SPEECH_SETTINGS) {
            speechSettingsRequested = true
            return
        }
        if (intent?.action == ACTION_OPEN_CONVERSATION_ID) {
            requestedConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            return
        }
        if (intent?.action == ACTION_OPEN_ARCHIVED_CONVERSATION) {
            val source = intent.getStringExtra(EXTRA_HANDOFF_SOURCE)?.takeIf(String::isNotBlank)
            val key = intent.getStringExtra(EXTRA_CONVERSATION_KEY)?.takeIf(String::isNotBlank)
            requestedArchivedConversation = if (source != null && key != null) {
                ArchivedConversationRequest(source, key)
            } else {
                null
            }
            return
        }
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_OPEN_TERMINAL = "io.github.mangi.eta.action.OPEN_TERMINAL"
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.CONVERSATION_ID"
        /** 调试直达路由的 intent extra；只在 debuggable 包里生效。 */
        const val EXTRA_OPEN_ROUTE = "eta.route"
        /** 测试版虚拟屏调试入口（create/release）；正式包忽略。 */
        const val EXTRA_DEBUG_VIRTUAL_DISPLAY = "eta.debug.virtual_display"
        const val EXTRA_DEBUG_DISPLAY_ID = "eta.debug.display_id"
        const val EXTRA_DEBUG_PROBE_ACTION = "eta.debug.probe_action"
        /** 探针的 max_nodes，默认 60；验证体积预算时用 120。 */
        const val EXTRA_DEBUG_MAX_NODES = "eta.debug.max_nodes"
        /** STEP8 的输入验证目标包（默认便签）：要证明 input_verification=verified，得换一个有真 EditText 的页面。 */
        const val EXTRA_DEBUG_INPUT_PKG = "eta.debug.input_pkg"
        /** 虚拟屏文本注入：把文本直接写进本进程焦点输入框，不碰系统剪贴板。 */
        const val EXTRA_INJECT_TEXT = "eta.inject.text"
        const val EXTRA_RUN_ID = "eta.run_id"
        /** 系统助手等外部入口发起的 run 按来源与会话键归档，回到本体时打开对应的归档会话。 */
        const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.mangi.eta.action.OPEN_ARCHIVED_CONVERSATION"
        const val EXTRA_HANDOFF_SOURCE = "io.github.mangi.eta.extra.HANDOFF_SOURCE"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.extra.CONVERSATION_KEY"
    }
}
