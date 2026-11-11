package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Item
import com.itos.coui.radiobutton.RadioButton
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
<<<<<<< HEAD
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaOverlayDialog
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaPreferenceRow
=======
import io.github.mangi.eta.ui.app.LocalUiStyle
>>>>>>> origin/main
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiIconDivider
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

<<<<<<< HEAD
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        onBack = onBack,
        // 搜索框固定在列表上方：它是列表第一项时，提供商一多、往下一滑就没了，
        // 想改关键词还得先滑回顶部。与设置页同一套处理。
        header = {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.ui_search_provider_74e049),
                modifier = Modifier
                    .fillMaxWidth()
                    // 左右留白由 MiuixScaffoldPage 按列表同一份 sidePadding 补齐。
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        },
    ) {

        item(key = "create_section") {
            ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                EtaArrowPreference(
                    title = stringResource(R.string.route_community_catalog),
                    summary = stringResource(R.string.route_community_catalog_summary),
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Storefront, tint = EtaPreferenceColors.Blue) },
                    onClick = { onNavigate(AppRoute.CommunityCatalog) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "新增 Gemini",
                    summary = "使用 API Key 连接原生 Interactions API",
                    startAction = { ProviderBrandIcon(ProviderSourceTypes.GEMINI) },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Gemini)) },
                )
=======
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
                CouiSearchBarBox(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.ui_search_provider_74e049),
                )

                ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                    CouiPreference(
                        title = "从 models.dev 添加",
                        summary = "浏览 models.dev 社区目录并选择模型",
                        icon = {
                            CouiSettingsIcon(
                                icon = Icons.Rounded.TravelExplore,
                                tint = colors.accent,
                            )
                        },
                        onClick = { onNavigate(AppRoute.CommunityCatalog) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                        summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                        icon = { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = stringResource(R.string.ui_new_anthropic_db6098),
                        summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                        icon = { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                    )
                    CouiIconDivider()
                    CouiPreference(
                        title = "新增 Gemini",
                        summary = "使用 API Key 连接原生 Interactions API",
                        icon = { ProviderBrandIcon(ProviderSourceTypes.GEMINI) },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Gemini)) },
                    )
                }

                ProviderSection(
                    title = pluralStringResource(
                        R.plurals.provider_configured_count,
                        filteredProviders.size,
                        filteredProviders.size,
                    ),
                ) {
                    if (filteredProviders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiText(
                                text = if (searchQuery.isBlank()) {
                                    stringResource(R.string.provider_empty)
                                } else {
                                    stringResource(R.string.provider_no_matches)
                                },
                                kind = CouiTextKind.PreferenceSummary,
                                color = colors.summary,
                            )
                        }
                    } else {
                        filteredProviders.forEachIndexed { index, provider ->
                            ProviderListItem(
                                provider = provider,
                                isSelected = provider.id == selectedProviderId,
                                onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                                onDelete = if (!provider.isBuiltIn) {
                                    { providerToDelete = provider }
                                } else {
                                    null
                                },
                                onSelect = {
                                    scope.launch {
                                        RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    }
                                },
                            )
                            if (index < filteredProviders.lastIndex) {
                                CouiIconDivider()
                            }
                        }
                    }
                }

                CouiPageBottomSpacer()
>>>>>>> origin/main
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
                item(key = "search") {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.ui_search_provider_74e049),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp, bottom = 4.dp),
                    )
                }

                item(key = "create_section") {
                    ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                        ArrowPreference(
                            title = "从 models.dev 添加",
                            summary = "浏览 models.dev 社区目录并选择模型",
                            startAction = {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.TravelExplore,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = { onNavigate(AppRoute.CommunityCatalog) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                            summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                            startAction = {
                                ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                            },
                            onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_new_anthropic_db6098),
                            summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                            startAction = {
                                ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                            },
                            onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                        )
                        ArrowPreference(
                            title = "新增 Gemini",
                            summary = "使用 API Key 连接原生 Interactions API",
                            startAction = { ProviderBrandIcon(ProviderSourceTypes.GEMINI) },
                            onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Gemini)) },
                        )
                    }
                }

                item(key = "list_section") {
                    ProviderSection(title = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size)) {
                        if (filteredProviders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.provider_empty)
                                    } else {
                                        stringResource(R.string.provider_no_matches)
                                    },
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        } else {
                            filteredProviders.forEach { provider ->
                                ProviderListItem(
                                    provider = provider,
                                    isSelected = provider.id == selectedProviderId,
                                    onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                                    onDelete = if (!provider.isBuiltIn) {
                                        { providerToDelete = provider }
                                    } else {
                                        null
                                    },
                                    onSelect = {
                                        scope.launch {
                                            RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (providerToDelete != null) {
        val target = providerToDelete
        val onConfirmDelete: () -> Unit = {
            scope.launch {
                target?.let { provider ->
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
                providerToDelete = null
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_remove_provider_9f848f),
                    message = stringResource(R.string.provider_delete_summary, target?.name.orEmpty()),
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { providerToDelete = null },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDialog(
                    show = true,
                    title = stringResource(R.string.ui_remove_provider_9f848f),
                    summary = stringResource(R.string.provider_delete_summary, target?.name.orEmpty()),
                    onDismissRequest = { providerToDelete = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.ui_delete_3755f5),
                        destructive = true,
                        onCancel = { providerToDelete = null },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }
}

/**
 * 列表项右侧的选择按钮是否可点。
 *
 * 禁用提供商不能成为当前提供商：`setSelectedProviderId` 内部会用
 * `takeIf { it.isEnabled }` 静静丢弃它。UI 必须提前拦住，否则用户点一下
 * 什么都不发生，也不报错。
 */
internal fun canSelectProvider(provider: ProviderSetting): Boolean = provider.isEnabled

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val subtitle = listOfNotNull(
        provider.typeLabel,
        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
        stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
        stringResource(R.string.ui_disabled_0fe5a9).takeIf { !provider.isEnabled },
    ).joinToString(" · ")

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Item(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClick = onDelete)
                    .graphicsLayer { alpha = opacity },
                icon = { ProviderIcon(provider) },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CouiText(
                            text = provider.name,
                            kind = CouiTextKind.PreferenceTitle,
                            color = if (isSelected) colors.accent else colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isSelected) {
                            TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                        }
                    }
                },
                summary = {
                    if (provider.baseUrl.isNotBlank()) {
                        CouiText(
                            text = provider.baseUrl,
                            kind = CouiTextKind.PreferenceSummary,
                            color = colors.summary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CouiText(
                        text = subtitle,
                        kind = CouiTextKind.Description,
                        color = colors.summary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                },
                trailing = {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(32.dp)
                            .clickable(
                                enabled = provider.isEnabled,
                                role = Role.RadioButton,
                                onClick = onSelect,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RadioButton(
                            modifier = Modifier.size(24.dp),
                            selected = isSelected,
                            enabled = provider.isEnabled,
                        )
                    }
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            BasicComponent(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClick = onDelete)
                    .graphicsLayer { alpha = opacity },
                startAction = { ProviderIcon(provider) },
                endActions = {
                    Checkbox(
                        state = ToggleableState(isSelected),
                        onClick = if (provider.isEnabled) onSelect else null,
                        enabled = provider.isEnabled,
                    )
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = provider.name,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSelected) {
                        TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                    }
                }
                Text(
                    text = provider.baseUrl,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
<<<<<<< HEAD
            }
        },
        endActions = {
            // 禁用提供商不可选：`setSelectedProviderId` 内部有 `takeIf { it.isEnabled }`，
            // 传进去会被静静丢弃。原来这里无条件下发 onClick，用户点一下禁用项
            // 没有任何反应（也不报错），只能看着界面自己猜。
            // IconButton 的 onClick 不接受 null（上游换成的 Checkbox 接受），
            // 所以用 `enabled = false` 让点击在组件层失效，同时保持无障碍语义可读。
            IconButton(
                onClick = if (canSelectProvider(provider)) onSelect else ({ }),
                enabled = canSelectProvider(provider),
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = when {
                        !canSelectProvider(provider) -> stringResource(R.string.ui_disabled_0fe5a9)
                        isSelected -> stringResource(R.string.provider_selected)
                        else -> stringResource(R.string.provider_set_current)
                    },
                    tint = when {
                        !canSelectProvider(provider) -> MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.4f)
                        isSelected -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                    },
=======
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
>>>>>>> origin/main
                )
            }
        }
    }
}
