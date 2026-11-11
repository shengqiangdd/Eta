package io.github.mangi.eta.ui

import android.content.ComponentName
import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
<<<<<<< HEAD
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
=======
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
>>>>>>> origin/main
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
<<<<<<< HEAD
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
=======
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
>>>>>>> origin/main
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.ui.app.AgentKeepScreenStatus
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.AgentBusySendModePreference
import io.github.mangi.eta.ui.components.AgentTranscriptViewPreference
import io.github.mangi.eta.ui.components.AgentWorkCollapseTimingPreference
import io.github.mangi.eta.ui.components.AcpDefaultAgentPreference
import io.github.mangi.eta.ui.components.AcpModelPreference
import io.github.mangi.eta.ui.components.AcpDelegationPreference
import io.github.mangi.eta.ui.components.AgentModePreference
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaCollapsibleSection
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiIconDivider
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
<<<<<<< HEAD
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.TextField
=======
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
>>>>>>> origin/main
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private object SettingsIconColors {
    val Blue = Color(0xFF4285F4)
    val Green = StatusSuccess
    val Orange = Color(0xFFFA7B17)
    val Yellow = StatusWarning
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
) {
    val glyphSize = when (icon) {
        Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> 24.dp
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
    }
}

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }
    // 终端工作区：自定义路径存 SharedPreferences，这里只维护对话框与展示状态。
    var workspaceDialog by remember { mutableStateOf(false) }
    var workspaceInput by remember { mutableStateOf("") }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var workspacePath by remember { mutableStateOf(TerminalRuntime.customWorkspacePath) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 关于组：版本信息与更新检查。结果对话框在列表外渲染，状态需要页面级 owner。
    val appPackageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val appVersionName = appPackageInfo.versionName.orEmpty()
    val appVersionSummary = "${appPackageInfo.versionName} (${appPackageInfo.longVersionCode})"
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    val checkForUpdate: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            coroutineScope.launch {
                val release = runCatching {
                    withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatest() }
                }.getOrNull()
                checkingUpdate = false
                when {
                    release == null -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_check_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    AppUpdateChecker.isNewer(release.version, appVersionName) -> availableUpdate = release
                    else -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_already_latest),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(EtaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    // 设置搜索：非空时只渲染命中的组，并忽略折叠状态（见 SETTINGS_*_KEYWORDS）。
    // 用 remember 而不是 rememberSaveable：搜索词是瞬时状态，离开设置页
    // （composable 销毁）就该清空。否则下次进来看到的是被过滤过的列表，
    // 只剩几个组，像是设置项丢了一半。
    var settingsQuery by remember { mutableStateOf("") }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        target.displayName(context)
    }
    val onPowerTargetSelect: (Int) -> Unit = { index ->
        val target = powerAssistantTargets.getOrNull(index)
        val targetPrefs = prefs
        if (target != null && targetPrefs != null) {
            if (putStringSync(
                    prefs = targetPrefs,
                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                    value = target.persistedValue,
                )
            ) {
                powerAssistantTarget = target
                enhancementHistory.recordCommittedTarget(target)
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val onAccessibilityProtectionToggle: (Boolean) -> Unit = { enabled ->
        if (!accessibilityProtectionPending) {
            accessibilityProtectionPending = true
            AccessibilityProtectionClient.setEnabled(
                context = context,
                enabled = enabled,
            ) { result ->
                accessibilityProtectionPending = false
                accessibilityProtectionEnabled = result.enabled
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                val failureMessage = when (result.status) {
                    AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                    AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                        context.getString(R.string.accessibility_protection_unavailable)
                    AccessibilityProtectionClient.ControlStatus.REJECTED ->
                        context.getString(R.string.accessibility_protection_rejected)
                }
                if (failureMessage != null) {
                    Toast.makeText(
                        context.applicationContext,
                        failureMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

<<<<<<< HEAD
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
        header = {
            // 固定在列表上方，不随设置项滚走：它在第一个位置，滑到下面的组之后
            // 想改关键词还得先滑回顶部。
            InputField(
                query = settingsQuery,
                onQueryChange = { settingsQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.settings_search_hint),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            )
        },
    ) {
            // 一个组都没命中时给一句提示：否则用户会以为是筛选坏了，
            // 而不是「确实没有这个设置项」。
            if (settingsQuery.isNotBlank() && !SETTINGS_ALL_KEYWORDS.any { settingsSectionMatches(settingsQuery, it) }) {
                item(key = "settings_no_match") {
                    Text(
                        text = stringResource(R.string.settings_no_match),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                }
            }
            // ── LLM 提供商 ──────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_AGENT_KEYWORDS)) item(key = "section_agent") {
                var agentExpanded by rememberSaveable { mutableStateOf(true) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.settings_model_usage_title),
                    icon = Icons.Rounded.Cloud,
                    expanded = settingsSectionExpanded(settingsQuery, agentExpanded),
                    onToggle = { agentExpanded = !agentExpanded },
                ) {
                    EtaArrowPreference(
=======
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(
                title = stringResource(R.string.ui_set_up_7debf9),
                onBack = onBack,
            ) {
                Group(title = stringResource(R.string.settings_llm_providers)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        icon = { CouiSettingsIcon(Icons.Rounded.Cloud, SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )
<<<<<<< HEAD

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_usage_title),
                        summary = stringResource(R.string.settings_usage_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.SportsBar,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Usage) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_runs_title),
                        summary = stringResource(R.string.settings_runs_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Timeline,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.RunInspector()) },
                    )
                }
            }

            // ── 对话与委派 ────────────────────────────────────────────
            // 从「模型提供商」里拆出来：思考、委派代理、委派专用模型、重试
            // 讲的是「模型怎么用」，不是「模型在哪」。合在一起时这一组有 7 项、
            // 又默认展开，等于每次进设置都要先滑过一整屏。
            if (settingsSectionMatches(settingsQuery, SETTINGS_DELEGATION_KEYWORDS)) item(key = "section_agent_behavior") {
                var delegationExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.settings_chat_delegation_title),
                    icon = Icons.Rounded.AccountTree,
                    expanded = settingsSectionExpanded(settingsQuery, delegationExpanded),
                    onToggle = { delegationExpanded = !delegationExpanded },
                ) {
=======
                    CouiIconDivider()
>>>>>>> origin/main
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                        iconTint = SettingsIconColors.Blue,
                    )

                    EtaPreferenceDivider()
                    AcpDelegationPreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_acp_delegation_title),
                        icon = Icons.Rounded.Code,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    AcpDefaultAgentPreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_acp_default_agent_title),
                        icon = Icons.Rounded.AccountTree,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    // 委派专用提供商/模型：默认「跟随主模型」，不动设置就看不到行为变化。
                    // 已成一项：提供商与模型本来就该一起选（模型名跨提供商不通用，
                    // 选提供商的唯一目的就是缩小模型范围）。
                    AcpModelPreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_acp_model_title),
                        icon = Icons.Rounded.Cloud,
                        iconTint = EtaPreferenceColors.Green,
                    )

                    EtaPreferenceDivider()
                    io.github.mangi.eta.ui.components.ModelRetryPreferences()
                }
            }

            // ── 聊天与界面 ────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_CHAT_UI_KEYWORDS)) item(key = "section_chat_ui") {
                var chatUiExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.settings_chat_ui_title),
                    icon = Icons.Rounded.ChatBubble,
                    expanded = settingsSectionExpanded(settingsQuery, chatUiExpanded),
                    onToggle = { chatUiExpanded = !chatUiExpanded },
                ) {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_chat_cards_title),
                        summary = stringResource(R.string.settings_chat_cards_summary),
                        key = Prefs.Keys.AGENT_CHAT_CARDS_AUTO_COLLAPSE,
                        icon = Icons.Rounded.Compress,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    // 开关开着也分两种：正在保屏 / 开着但没任务。开关本身看不出区别，
                    // 在说明文字里直接给实况，用户不用去猜功能有没有生效。
                    val keepScreenSummary = when {
                        !AgentKeepScreenStatus.enabled ->
                            stringResource(R.string.settings_keep_screen_on_summary)
                        AgentKeepScreenStatus.holding ->
                            stringResource(R.string.settings_keep_screen_on_active)
                        else ->
                            stringResource(R.string.settings_keep_screen_on_idle)
                    }
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_keep_screen_on_title),
                        summary = keepScreenSummary,
                        key = Prefs.Keys.AGENT_KEEP_SCREEN_ON,
                        icon = Icons.Rounded.LightMode,
                        iconTint = EtaPreferenceColors.Yellow,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_overlay_capsule_title),
                        summary = stringResource(R.string.settings_overlay_capsule_summary),
                        key = Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED,
                        icon = Icons.Rounded.VisibilityOff,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_overlay_glow_title),
                        summary = stringResource(R.string.settings_overlay_glow_summary),
                        key = Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED,
                        icon = Icons.Rounded.Flare,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    AgentModePreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_agent_mode_title),
                        icon = Icons.Rounded.Checklist,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    AgentBusySendModePreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_busy_send_title),
                        icon = Icons.Rounded.TouchApp,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    AgentWorkCollapseTimingPreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_work_collapse_title),
                        icon = Icons.Rounded.Compress,
                        iconTint = EtaPreferenceColors.Green,
                    )

                    EtaPreferenceDivider()
                    AgentTranscriptViewPreference(
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_transcript_view_title),
                        icon = Icons.Rounded.ViewAgenda,
                        iconTint = EtaPreferenceColors.Yellow,
                    )
                }

<<<<<<< HEAD
            // ── 上下文与扩展 ────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_CONTEXT_KEYWORDS)) item(key = "section_context_extensions") {
                var contextExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.settings_context_extensions),
                    icon = Icons.Rounded.Apps,
                    expanded = settingsSectionExpanded(settingsQuery, contextExpanded),
                    onToggle = { contextExpanded = !contextExpanded },
                ) {
=======
                Group(title = stringResource(R.string.settings_context_extensions)) {
>>>>>>> origin/main
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_auto_compaction),
                        key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                        icon = Icons.Rounded.Layers,
                        iconTint = SettingsIconColors.Blue,
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        icon = {
                            CouiSettingsIcon(
                                Icons.AutoMirrored.Rounded.MenuBook,
                                SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = "Subagent",
                        icon = { CouiSettingsIcon(Icons.Rounded.Hub, SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.Subagents) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.route_skills),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.ImportContacts,
                                SettingsIconColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )
<<<<<<< HEAD

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.route_plugins),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Extension,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Plugins) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
=======
                    CouiIconDivider()
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.route_mcp_servers),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.AccountTree,
                                SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )
<<<<<<< HEAD

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_characters_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.SportsBar,
                                tint = EtaPreferenceColors.Orange,
=======
                    CouiIconDivider()
                    CouiPreference(
                        title = "角色",
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.SportsBar,
                                SettingsIconColors.Orange,
>>>>>>> origin/main
                            )
                        },
                        onClick = { onNavigate(AppRoute.Characters) },
                    )
                }

<<<<<<< HEAD
            // ── 工具 ───────────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_TOOLS_KEYWORDS)) item(key = "section_tools") {
                var toolsExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.ui_tool_a72ef1),
                    icon = Icons.Rounded.Build,
                    expanded = settingsSectionExpanded(settingsQuery, toolsExpanded),
                    onToggle = { toolsExpanded = !toolsExpanded },
                ) {
                    EtaArrowPreference(
=======
                Group(title = stringResource(R.string.ui_tool_a72ef1)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.settings_tools_list),
                        icon = { CouiSettingsIcon(Icons.Rounded.Dashboard, SettingsIconColors.Green) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.web_tools_settings_title),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.TravelExplore,
                                SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )
                    CouiIconDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_virtual_display_title),
                        key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                        icon = Icons.Rounded.Layers,
                        iconTint = SettingsIconColors.Blue,
                    )
<<<<<<< HEAD

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                        iconTint = EtaPreferenceColors.Green,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                        iconTint = EtaPreferenceColors.Green,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.setting_background_resume_button_title),
                        summary = stringResource(R.string.setting_background_resume_button_summary),
                        key = Prefs.Keys.AGENT_BACKGROUND_RESUME_BUTTON,
                        icon = Icons.Rounded.Checklist,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.setting_background_resume_window_long_title),
                        summary = stringResource(R.string.setting_background_resume_window_long_summary),
                        key = Prefs.Keys.AGENT_BACKGROUND_RESUME_WINDOW_LONG,
                        icon = Icons.Rounded.Timer,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.setting_background_resume_auto_title),
                        summary = stringResource(R.string.setting_background_resume_auto_summary),
                        key = Prefs.Keys.AGENT_BACKGROUND_RESUME_AUTO,
                        icon = Icons.Rounded.SystemUpdate,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
=======
                    CouiIconDivider()
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.Inventory2,
                                SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.env_vars_title),
                        summary = stringResource(R.string.env_vars_settings_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Key,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.EnvVars) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.terminal_workspace_title),
                        summary = workspacePath ?: stringResource(R.string.terminal_workspace_summary_default),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Folder,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = {
                            workspaceInput = workspacePath.orEmpty()
                            workspaceError = null
                            workspaceDialog = true
                        },
                    )
                }

<<<<<<< HEAD
            if (settingsSectionMatches(settingsQuery, SETTINGS_ENHANCE_KEYWORDS)) item(key = "system_enhancements") {
                EtaPreferenceGroup {
                    EtaArrowPreference(
=======
                Group {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.capability_enhancements),
                        icon = { CouiSettingsIcon(Icons.Rounded.Security, SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }

<<<<<<< HEAD
            // ── 系统助手接管 ──────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_ASSISTANT_KEYWORDS)) item(key = "section_assistant_takeover") {
                var assistantExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
                    icon = Icons.Rounded.PowerSettingsNew,
                    expanded = settingsSectionExpanded(settingsQuery, assistantExpanded),
                    onToggle = { assistantExpanded = !assistantExpanded },
                ) {
                    EtaArrowPreference(
=======
                Group(title = stringResource(R.string.ui_system_assistant_takes_over_f46043)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.SupportAgent,
                                SettingsIconColors.Green,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                    if (prefs != null || hasConnectedFramework) {
                        CouiIconDivider()
                        CouiDropdownPreference(
                            title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                            items = powerAssistantItems,
                            selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                            onSelectedIndexChange = onPowerTargetSelect,
                            icon = {
                                CouiSettingsIcon(
                                    icon = Icons.Rounded.PowerSettingsNew,
                                    tint = SettingsIconColors.Yellow,
                                    enabled = prefs != null,
                                )
                            },
                            enabled = prefs != null,
                        )
                        CouiIconDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                            key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                            icon = Icons.Rounded.Settings,
                            iconTint = SettingsIconColors.Green,
                        )
                    }
                }

<<<<<<< HEAD
            if (prefs != null || hasConnectedFramework) {
                // ── 厂商助手兼容入口 ──────────────────────────────────────────
                if (settingsSectionMatches(settingsQuery, SETTINGS_OEM_KEYWORDS)) item(key = "section_oem_assistant_compatibility") {
                    var vendorExpanded by rememberSaveable { mutableStateOf(false) }
                    EtaCollapsibleSection(
                        title = stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a),
                        icon = Icons.Rounded.SupportAgent,
                        expanded = settingsSectionExpanded(settingsQuery, vendorExpanded),
                        onToggle = { vendorExpanded = !vendorExpanded },
                    ) {
=======
                if (prefs != null || hasConnectedFramework) {
                    Group(title = stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a)) {
>>>>>>> origin/main
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                            key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                            icon = Icons.Rounded.Cloud,
                            iconTint = SettingsIconColors.Blue,
                        )
                        CouiIconDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                            key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                            icon = Icons.Rounded.FilterAlt,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                }

<<<<<<< HEAD
            if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
                // ── Gemini ─────────────────────────────────────────────────
                if (settingsSectionMatches(settingsQuery, SETTINGS_GEMINI_KEYWORDS)) item(key = "section_gemini") {
                    var geminiExpanded by rememberSaveable { mutableStateOf(false) }
                    EtaCollapsibleSection(
                        title = "Gemini",
                        icon = Icons.Rounded.AutoAwesome,
                        expanded = settingsSectionExpanded(settingsQuery, geminiExpanded),
                        onToggle = { geminiExpanded = !geminiExpanded },
                    ) {
                        if (prefs != null || hasConnectedFramework) {
=======
                if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
                    Group(title = "Gemini") {
                        val hasFrameworkRows = prefs != null || hasConnectedFramework
                        if (hasFrameworkRows) {
>>>>>>> origin/main
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                                key = Prefs.Keys.HOTWORD_SELF_HEAL,
                                icon = Icons.Rounded.Hearing,
                                iconTint = SettingsIconColors.Green,
                            )
                            CouiIconDivider()
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                                key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                                icon = Icons.Rounded.Lock,
                                iconTint = SettingsIconColors.Blue,
                            )
                            CouiIconDivider()
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                                key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                                icon = Icons.Rounded.SettingsVoice,
                                iconTint = SettingsIconColors.Green,
                            )
                        }
                        if (capabilities.root.isGranted || hasUsedSystemizer) {
                            if (hasFrameworkRows) {
                                CouiIconDivider()
                            }
                            CouiPreference(
                                title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                                summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.Inventory,
                                        tint = SettingsIconColors.Orange,
                                        enabled = !installingSystemizer,
                                    )
                                },
                                enabled = !installingSystemizer,
                                onClick = {
                                    if (!capabilities.root.isGranted) {
                                        onNavigate(AppRoute.SystemEnhance)
                                    } else if (!installingSystemizer) {
                                        showSystemizerDialog = true
                                    }
                                },
                            )
                        }
                    }
                }

<<<<<<< HEAD
            if (prefs != null || hasConnectedFramework) {
                // ── 一圈即搜 ────────────────────────────────────────────────
                if (settingsSectionMatches(settingsQuery, SETTINGS_CIRCLE_KEYWORDS)) item(key = "section_circle_to_search") {
                    var circleExpanded by rememberSaveable { mutableStateOf(false) }
                    EtaCollapsibleSection(
                        title = stringResource(R.string.ui_search_in_one_turn_179584),
                        icon = Icons.Rounded.Search,
                        expanded = settingsSectionExpanded(settingsQuery, circleExpanded),
                        onToggle = { circleExpanded = !circleExpanded },
                    ) {
=======
                if (prefs != null || hasConnectedFramework) {
                    Group(title = stringResource(R.string.ui_search_in_one_turn_179584)) {
>>>>>>> origin/main
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                            key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                            icon = Icons.Rounded.SwipeUp,
                            iconTint = SettingsIconColors.Blue,
                        )
                        CouiIconDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                            key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                            icon = Icons.Rounded.TouchApp,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                }

<<<<<<< HEAD
            // ── 通用 ────────────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_GENERAL_KEYWORDS)) item(key = "section_general") {
                var generalExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.settings_general),
                    icon = Icons.Rounded.Settings,
                    expanded = settingsSectionExpanded(settingsQuery, generalExpanded),
                    onToggle = { generalExpanded = !generalExpanded },
                ) {
                    EtaArrowPreference(
=======
                Group(title = stringResource(R.string.settings_general)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.appearance_title),
                        icon = { CouiSettingsIcon(Icons.Rounded.Palette, SettingsIconColors.Orange) },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.speech_settings_title),
                        icon = { CouiSettingsIcon(Icons.Rounded.Mic, SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.SpeechSettings) },
                    )
                    CouiIconDivider()
                    LanguagePreference(
                        iconTint = SettingsIconColors.Blue,
                        startAction = {
                            CouiSettingsIcon(Icons.Rounded.Language, SettingsIconColors.Blue)
                        },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.data_backup_title),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.Description,
                                SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                }

<<<<<<< HEAD
            // ── 权限 ────────────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_PERMISSIONS_KEYWORDS)) item(key = "section_permissions") {
                var permissionsExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.ui_permissions_560165),
                    icon = Icons.Rounded.Shield,
                    expanded = settingsSectionExpanded(settingsQuery, permissionsExpanded),
                    onToggle = { permissionsExpanded = !permissionsExpanded },
                ) {
                    EtaArrowPreference(
=======
                Group(title = stringResource(R.string.ui_permissions_560165)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        value = stringResource(
                            if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                        ),
                        valueColor = if (overlayGranted) pageColors.summary else dialogColors.destructive,
                        icon = { CouiSettingsIcon(Icons.Rounded.Layers, SettingsIconColors.Blue) },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    CouiIconDivider()
                    val accEnabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                    CouiPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        value = stringResource(
                            if (accEnabled) R.string.status_enabled else R.string.status_disabled,
                        ),
                        valueColor = if (accEnabled) pageColors.summary else pageColors.accent,
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.AccessibilityNew,
                                SettingsIconColors.Blue,
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                    )
                    if (AccessibilityProtectionClient.isSupported() && (prefs != null || hasConnectedFramework)) {
                        CouiIconDivider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = onAccessibilityProtectionToggle,
                            icon = {
                                CouiSettingsIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = SettingsIconColors.Blue,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }
                }

<<<<<<< HEAD
            // ── 关于 ────────────────────────────────────────────────────
            if (settingsSectionMatches(settingsQuery, SETTINGS_ABOUT_KEYWORDS)) item(key = "section_about") {
                var aboutExpanded by rememberSaveable { mutableStateOf(false) }
                EtaCollapsibleSection(
                    title = stringResource(R.string.ui_about_bed172),
                    icon = Icons.Rounded.Description,
                    expanded = settingsSectionExpanded(settingsQuery, aboutExpanded),
                    onToggle = { aboutExpanded = !aboutExpanded },
                ) {
                    EtaPreference(
=======
                Group(title = stringResource(R.string.ui_about_bed172)) {
                    CouiPreference(
>>>>>>> origin/main
                        title = stringResource(R.string.ui_about_version_title),
                        summary = appVersionSummary,
                        icon = { CouiSettingsIcon(Icons.Rounded.Info, SettingsIconColors.Blue) },
                        showChevron = false,
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_about_update_title),
                        summary = if (checkingUpdate) {
                            stringResource(R.string.ui_about_update_checking)
                        } else {
                            null
                        },
                        icon = {
                            CouiSettingsIcon(
                                icon = Icons.Rounded.SystemUpdate,
                                tint = SettingsIconColors.Green,
                                enabled = !checkingUpdate,
                            )
                        },
                        enabled = !checkingUpdate,
                        onClick = checkForUpdate,
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_about_feedback_title),
                        icon = {
                            CouiSettingsIcon(
                                Icons.Rounded.BugReport,
                                SettingsIconColors.Orange,
                            )
                        },
                        onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_about_github_star_title),
                        summary = stringResource(R.string.ui_about_github_star_hint),
                        icon = { CouiSettingsIcon(Icons.Rounded.Code, SettingsIconColors.Blue) },
                        onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.ui_set_up_7debf9),
                onBack = onBack,
            ) {
                item(key = "section_agent") {
                    SmallTitle(stringResource(R.string.settings_llm_providers))
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.ui_model_provider_e8c7f5),
                            summary = providerSummary,
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Cloud,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ModelProviders) },
                        )
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                            key = Prefs.Keys.AGENT_THINKING_ENABLED,
                            icon = Icons.Rounded.Psychology,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                }

                item(key = "section_context_extensions") {
                    SmallTitle(stringResource(R.string.settings_context_extensions))
                    SettingsGroupCard {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = SettingsIconColors.Blue,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_memory_b55ff5),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                                    tint = SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Memory) },
                        )
                        ArrowPreference(
                            title = "Subagent",
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Hub,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.route_skills),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.ImportContacts,
                                    tint = SettingsIconColors.Green,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Skills) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.route_mcp_servers),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.AccountTree,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.McpServers) },
                        )
                        ArrowPreference(
                            title = "角色",
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.SportsBar,
                                    tint = SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Characters) },
                        )
                    }
                }

                item(key = "section_tools") {
                    SmallTitle(stringResource(R.string.ui_tool_a72ef1))
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.settings_tools_list),
                            startAction = { SettingsIcon(Icons.Rounded.Dashboard, tint = SettingsIconColors.Green) },
                            onClick = { onNavigate(AppRoute.Tools) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.web_tools_settings_title),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.TravelExplore,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.WebToolsSettings) },
                        )
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_virtual_display_title),
                            key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = SettingsIconColors.Blue,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Inventory2,
                                    tint = SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                    }
                }

                item(key = "system_enhancements") {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            startAction = { SettingsIcon(Icons.Rounded.Security, tint = SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }

                item(key = "section_assistant_takeover") {
                    SmallTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.SupportAgent,
                                    tint = SettingsIconColors.Green,
                                )
                            },
                            onClick = openAssistantSettings,
                        )
                        if (prefs != null || hasConnectedFramework) {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                                items = powerAssistantItems,
                                selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                                onSelectedIndexChange = onPowerTargetSelect,
                                startAction = {
                                    SettingsIcon(
                                        icon = Icons.Rounded.PowerSettingsNew,
                                        tint = SettingsIconColors.Yellow,
                                        enabled = prefs != null,
                                    )
                                },
                                enabled = prefs != null,
                            )
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                                key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                                icon = Icons.Rounded.Settings,
                                iconTint = SettingsIconColors.Green,
                            )
                        }
                    }
                }

                if (prefs != null || hasConnectedFramework) {
                    item(key = "section_oem_assistant_compatibility") {
                        SmallTitle(stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a))
                        SettingsGroupCard {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                                key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                                icon = Icons.Rounded.Cloud,
                                iconTint = SettingsIconColors.Blue,
                            )
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                                key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                                icon = Icons.Rounded.FilterAlt,
                                iconTint = SettingsIconColors.Blue,
                            )
                        }
                    }
                }

                if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
                    item(key = "section_gemini") {
                        SmallTitle("Gemini")
                        SettingsGroupCard {
                            if (prefs != null || hasConnectedFramework) {
                                SwitchPref(
                                    context = context,
                                    prefs = prefs,
                                    title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                                    key = Prefs.Keys.HOTWORD_SELF_HEAL,
                                    icon = Icons.Rounded.Hearing,
                                    iconTint = SettingsIconColors.Green,
                                )
                                SwitchPref(
                                    context = context,
                                    prefs = prefs,
                                    title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                                    key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                                    icon = Icons.Rounded.Lock,
                                    iconTint = SettingsIconColors.Blue,
                                )
                                SwitchPref(
                                    context = context,
                                    prefs = prefs,
                                    title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                                    key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                                    icon = Icons.Rounded.SettingsVoice,
                                    iconTint = SettingsIconColors.Green,
                                )
                            }
                            if (capabilities.root.isGranted || hasUsedSystemizer) {
                                ArrowPreference(
                                    title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                                    startAction = {
                                        SettingsIcon(
                                            icon = Icons.Rounded.Inventory,
                                            tint = SettingsIconColors.Orange,
                                            enabled = !installingSystemizer,
                                        )
                                    },
                                    summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                                    enabled = !installingSystemizer,
                                    holdDownState = showSystemizerDialog,
                                    onClick = {
                                        if (!capabilities.root.isGranted) {
                                            onNavigate(AppRoute.SystemEnhance)
                                        } else if (!installingSystemizer) {
                                            showSystemizerDialog = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (prefs != null || hasConnectedFramework) {
                    item(key = "section_circle_to_search") {
                        SmallTitle(stringResource(R.string.ui_search_in_one_turn_179584))
                        SettingsGroupCard {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                                key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                                icon = Icons.Rounded.SwipeUp,
                                iconTint = SettingsIconColors.Blue,
                            )
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                                key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                                icon = Icons.Rounded.TouchApp,
                                iconTint = SettingsIconColors.Blue,
                            )
                        }
                    }
                }

                item(key = "section_general") {
                    SmallTitle(stringResource(R.string.settings_general))
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.appearance_title),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Palette,
                                    tint = SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.AppearanceSettings) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.speech_settings_title),
                            startAction = { SettingsIcon(icon = Icons.Rounded.Mic, tint = SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.SpeechSettings) },
                        )
                        LanguagePreference(
                            iconTint = SettingsIconColors.Blue,
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Language,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_title),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Description,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                    }
                }

                item(key = "section_permissions") {
                    SmallTitle(stringResource(R.string.ui_permissions_560165))
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.ui_floating_window_permissions_076b77),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Layers,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            endActions = {
                                Text(
                                    text = stringResource(
                                        if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (overlayGranted) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = {
                                if (!overlayGranted) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.AccessibilityNew,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            endActions = {
                                val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                                Text(
                                    text = stringResource(
                                        if (enabled) R.string.status_enabled else R.string.status_disabled,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (enabled) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                )
                            },
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    )
                                }
                            },
                        )
                        if (AccessibilityProtectionClient.isSupported() && (prefs != null || hasConnectedFramework)) {
                            SwitchPreference(
                                title = stringResource(R.string.ui_enforce_accessibility_55e838),
                                checked = accessibilityProtectionEnabled,
                                onCheckedChange = onAccessibilityProtectionToggle,
                                startAction = {
                                    SettingsIcon(
                                        icon = Icons.Rounded.VerifiedUser,
                                        tint = SettingsIconColors.Blue,
                                        enabled = prefs != null && !accessibilityProtectionPending,
                                    )
                                },
                                enabled = prefs != null && !accessibilityProtectionPending,
                            )
                        }
                    }
                }

                item(key = "section_about") {
                    SmallTitle(stringResource(R.string.ui_about_bed172))
                    SettingsGroupCard {
                        BasicComponent(
                            title = stringResource(R.string.ui_about_version_title),
                            summary = appVersionSummary,
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Info,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_update_title),
                            summary = if (checkingUpdate) {
                                stringResource(R.string.ui_about_update_checking)
                            } else {
                                null
                            },
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.SystemUpdate,
                                    tint = SettingsIconColors.Green,
                                    enabled = !checkingUpdate,
                                )
                            },
                            enabled = !checkingUpdate,
                            onClick = checkForUpdate,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_feedback_title),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.BugReport,
                                    tint = SettingsIconColors.Orange,
                                )
                            },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_about_github_star_title),
                            summary = stringResource(R.string.ui_about_github_star_hint),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Code,
                                    tint = SettingsIconColors.Blue,
                                )
                            },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                        )
                    }
                }
            }
        }
    }

    SystemizerConfirmDialog(
        show = showSystemizerDialog,
        installing = installingSystemizer,
        onDismissRequest = {
            if (!installingSystemizer) {
                showSystemizerDialog = false
            }
        },
        onConfirm = {
            if (installingSystemizer) return@SystemizerConfirmDialog
            if (!capabilities.root.isGranted) {
                showSystemizerDialog = false
                onNavigate(AppRoute.SystemEnhance)
                return@SystemizerConfirmDialog
            }
            enhancementHistory.recordSystemizerUse()
            hasUsedSystemizer = true
            showSystemizerDialog = false
            installingSystemizer = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GoogleAppSystemizerInstaller(context.applicationContext).install()
                }
                installingSystemizer = false
                Toast.makeText(
                    context.applicationContext,
                    result.toToastMessage(context),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
    )

    availableUpdate?.let { update ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_update_available_title),
                    message = stringResource(R.string.ui_update_available_message, update.version, appVersionName),
                    confirmText = stringResource(R.string.ui_update_go_download),
                    onConfirm = {
                        availableUpdate = null
                        openUrl(update.url)
                    },
                    onDismissRequest = { availableUpdate = null },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_update_available_title),
                    summary = stringResource(R.string.ui_update_available_message, update.version, appVersionName),
                    onDismissRequest = { availableUpdate = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.ui_update_go_download),
                        onCancel = { availableUpdate = null },
                        onConfirm = {
                            availableUpdate = null
                            openUrl(update.url)
                        },
                    )
                }
            }
        }
<<<<<<< HEAD

        if (workspaceDialog) {
            EtaWindowDialog(
                show = true,
                title = stringResource(R.string.terminal_workspace_title),
                summary = stringResource(R.string.terminal_workspace_dialog_summary),
                onDismissRequest = { workspaceDialog = false },
            ) {
                Column {
                    TextField(
                        value = workspaceInput,
                        onValueChange = { workspaceInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    workspaceError?.let { message ->
                        Text(
                            text = message,
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_save),
                        onCancel = { workspaceDialog = false },
                        onConfirm = {
                            val error = TerminalRuntime.applyCustomWorkspace(context, workspaceInput)
                            if (error == null) {
                                workspacePath = TerminalRuntime.customWorkspacePath
                                workspaceDialog = false
                            } else {
                                workspaceError = error
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
=======
    }
>>>>>>> origin/main
}

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val confirmText = if (installing) {
        stringResource(R.string.status_processing)
    } else {
        stringResource(R.string.action_confirm)
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = show,
                title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                message = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
                confirmText = confirmText,
                confirmEnabled = !installing,
                cancelEnabled = !installing,
                onConfirm = onConfirm,
                onDismissRequest = onDismissRequest,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = show,
                title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
                onDismissRequest = onDismissRequest,
            ) {
                MiuixDialogActions(
                    confirmText = confirmText,
                    cancelEnabled = !installing,
                    confirmEnabled = !installing,
                    onCancel = onDismissRequest,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val onToggle: (Boolean) -> Unit = { value ->
        val targetPrefs = prefs
        if (targetPrefs != null) {
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiSwitchPreference(
                title = title,
                summary = summary,
                checked = checked,
                onCheckedChange = onToggle,
                icon = { CouiSettingsIcon(icon = icon, tint = iconTint, enabled = enabled) },
                enabled = enabled,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            SwitchPreference(
                title = title,
                summary = summary,
                checked = checked,
                onCheckedChange = onToggle,
                startAction = {
                    SettingsIcon(icon = icon, enabled = enabled, tint = iconTint)
                },
                enabled = enabled,
            )
        }
    }
}

private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean,
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String,
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }

/**
 * 设置搜索的匹配规则：命中任意关键词即显示该组。
 *
 * 匹配的是组内每个条目的叫法，而不只是组标题 —— 用户搜「委派」时目标项在
 * 「模型提供商」组里，只看标题永远搜不到，而这正是搜索要解决的问题。
 * 关键词中英混排：界面语言和输入习惯经常不一致。
 */
/**
 * 某组此刻是否展开。
 *
 * 搜索时一律展开：命中的组如果还保持用户上次的折叠状态，搜索结果就只剩一行标题，
 * 用户还得再点一次才能看到自己要找的那一项 —— 搜索等于没搜到。
 */
internal fun settingsSectionExpanded(query: String, userExpanded: Boolean): Boolean =
    query.isNotBlank() || userExpanded

internal fun settingsSectionMatches(query: String, keywords: List<String>): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return keywords.any { it.contains(q, ignoreCase = true) }
}

// 各组的关键词表。加设置项时记得同步补一行，否则那一项搜不到。
internal val SETTINGS_AGENT_KEYWORDS = listOf(
    "模型", "提供商", "用量", "统计", "运行", "记录",
    "provider", "model", "usage", "run",
)
internal val SETTINGS_DELEGATION_KEYWORDS = listOf(
    "思考", "委派", "代理", "重试", "任务", "codex", "claude", "acp", "delegation", "retry",
)
internal val SETTINGS_CHAT_UI_KEYWORDS = listOf(
    "聊天", "界面", "卡片", "屏幕", "唤醒", "悬浮", "胶囊", "光晕", "助手模式",
    "发送", "任务卡", "详情", "chat", "ui",
)
internal val SETTINGS_CONTEXT_KEYWORDS = listOf(
    "上下文", "压缩", "记忆", "技能", "插件", "角色", "context", "skill", "plugin", "mcp",
)
internal val SETTINGS_TOOLS_KEYWORDS = listOf(
    "工具", "联网", "浏览", "网页", "虚拟屏", "设备", "敏感", "终端", "文件",
    "后台", "工作区", "变量", "tools", "browser", "linux", "terminal",
)
internal val SETTINGS_ENHANCE_KEYWORDS = listOf("能力", "增强", "capability", "enhancement")
internal val SETTINGS_ASSISTANT_KEYWORDS = listOf(
    "助手", "接管", "电源键", "长按", "默认", "assistant", "power",
)
internal val SETTINGS_OEM_KEYWORDS = listOf("小布", "小爱", "厂商", "前缀", "xiaobu", "xiaomi")
internal val SETTINGS_GEMINI_KEYWORDS = listOf(
    "gemini", "谷歌", "google", "语音", "唤醒", "锁屏", "系统应用",
)
internal val SETTINGS_CIRCLE_KEYWORDS = listOf("圈选", "搜索", "手势", "两指", "circle", "search")
internal val SETTINGS_GENERAL_KEYWORDS = listOf(
    "通用", "外观", "语音", "声音", "备份", "数据",
    "general", "appearance", "speech", "backup",
)
internal val SETTINGS_PERMISSIONS_KEYWORDS = listOf(
    "权限", "悬浮窗", "无障碍", "permission", "accessibility", "overlay",
)
internal val SETTINGS_ABOUT_KEYWORDS = listOf(
    "关于", "版本", "更新", "反馈", "about", "version", "update", "github",
)

/** 全部关键词表，用来判断「一个组都没命中」。 */
internal val SETTINGS_ALL_KEYWORDS = listOf(
    SETTINGS_AGENT_KEYWORDS,
    SETTINGS_DELEGATION_KEYWORDS,
    SETTINGS_CHAT_UI_KEYWORDS,
    SETTINGS_CONTEXT_KEYWORDS,
    SETTINGS_TOOLS_KEYWORDS,
    SETTINGS_ENHANCE_KEYWORDS,
    SETTINGS_ASSISTANT_KEYWORDS,
    SETTINGS_OEM_KEYWORDS,
    SETTINGS_GEMINI_KEYWORDS,
    SETTINGS_CIRCLE_KEYWORDS,
    SETTINGS_GENERAL_KEYWORDS,
    SETTINGS_PERMISSIONS_KEYWORDS,
    SETTINGS_ABOUT_KEYWORDS,
)
