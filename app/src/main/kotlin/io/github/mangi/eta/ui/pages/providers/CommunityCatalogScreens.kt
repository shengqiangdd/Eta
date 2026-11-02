package io.github.mangi.eta.ui.pages.providers

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.ui.app.CommunityCatalogStore
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaCheckboxPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import io.github.mangi.eta.ui.navigation.AppRoute
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun CommunityCatalogScreen(
    store: CommunityCatalogStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = store.catalog
    val providers = remember(catalog, query) {
        val term = query.trim()
        catalog?.providers.orEmpty().filter { provider ->
            term.isEmpty() ||
                provider.name.contains(term, ignoreCase = true) ||
                provider.id.contains(term, ignoreCase = true) ||
                provider.baseUrl.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) { store.openCatalog() }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_community_catalog),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = store::refresh,
                enabled = !store.loading && !store.refreshing,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.catalog_refresh_models))
            }
        },
    ) {
        item(key = "search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.catalog_search_provider),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "catalog_status") {
            ProviderSection(title = stringResource(R.string.catalog_status_section)) {
                EtaPreference(
                    title = stringResource(R.string.catalog_source_title),
                    summary = stringResource(R.string.catalog_source_summary),
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = stringResource(R.string.catalog_entries_title),
                    summary = stringResource(R.string.catalog_entries_summary),
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = catalog?.sourceLabel(resources) ?: if (store.loading) {
                        stringResource(R.string.catalog_loading_local)
                    } else {
                        stringResource(R.string.catalog_unavailable)
                    },
                    summary = catalog?.let { catalogSummary(it, resources) },
                )
                if (store.refreshing) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(title = stringResource(R.string.catalog_refreshing))
                }
                store.refreshError?.let { error ->
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        title = stringResource(R.string.catalog_refresh_failed),
                        summary = if (catalog != null) {
                            resources.getString(R.string.catalog_refresh_failed_kept, error)
                        } else {
                            error
                        },
                    )
                }
                if (store.loadError && catalog == null) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        title = stringResource(R.string.catalog_load_failed_title),
                        summary = stringResource(R.string.catalog_load_failed_summary),
                    )
                }
            }
        }

        item(key = "results_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.catalog_results_title, providers.size))
        }
        if (catalog == null && store.loading) {
            item(key = "loading") { CatalogLoadingState() }
        } else if (providers.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = if (catalog == null) {
                        stringResource(R.string.catalog_empty_no_catalog)
                    } else {
                        stringResource(R.string.catalog_empty_no_match)
                    },
                    summary = if (catalog == null) {
                        stringResource(R.string.catalog_empty_no_catalog_summary)
                    } else {
                        stringResource(R.string.catalog_empty_no_match_summary)
                    },
                    icon = if (catalog == null) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                )
            }
        } else {
            items(providers, key = { "provider:${it.id}" }, contentType = { "provider" }) { provider ->
                EtaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    pressFeedbackType = PressFeedbackType.Sink,
                    onClick = { onNavigate(AppRoute.CommunityCatalogProvider(provider.id)) },
                ) {
                    Text(
                        text = provider.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = provider.baseUrl,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.catalog_models_available,
                            provider.models.size,
                            provider.models.size,
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommunityCatalogProviderScreen(
    catalogId: String,
    store: CommunityCatalogStore,
    onImported: (String) -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(catalogId) { mutableStateOf("") }
    // 声明类型用只读 List：Compose 的 MutableCollectionMutableState 要求 State 里不放可变集合；
    // 实际存的是 ArrayList，因为 rememberSaveable 只能把 Bundle 认识的类型写进 saved state。
    // 每次修改都赋新实例（下面的 + / filterNot），不会绕过快照通知。
    var selectedIds by rememberSaveable(catalogId) { mutableStateOf<List<String>>(ArrayList()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importInFlight by remember { mutableStateOf(false) }
    var selection by remember(catalogId) { mutableStateOf<CommunityCatalogProvider?>(null) }
    val provider = selection ?: store.catalog?.providers?.firstOrNull { it.id == catalogId }
    val filteredModels = remember(provider, query) {
        val term = query.trim()
        provider?.models.orEmpty().filter { model ->
            term.isEmpty() ||
                model.displayName.contains(term, ignoreCase = true) ||
                model.modelId.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(catalogId) { store.ensureLoaded() }
    LaunchedEffect(provider) {
        if (selection == null && provider != null) selection = provider
    }

    MiuixScaffoldPage(
        title = provider?.name ?: stringResource(R.string.route_community_catalog_provider),
        onBack = onBack,
        actions = {
            EtaTextButton(
                text = stringResource(R.string.catalog_import_count, selectedIds.size),
                enabled = provider != null && selectedIds.isNotEmpty() && !importInFlight && !store.importing,
                onClick = {
                    val chosen = provider ?: return@EtaTextButton
                    if (importInFlight) return@EtaTextButton
                    importInFlight = true
                    importError = null
                    scope.launch {
                        try {
                            val addedId = store.importProvider(chosen, selectedIds.toSet())
                            onImported(addedId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalArgumentException) {
                            importError = resources.getString(R.string.catalog_error_selection_stale)
                        } catch (_: Throwable) {
                            importError = resources.getString(R.string.catalog_error_save_failed)
                        } finally {
                            importInFlight = false
                        }
                    }
                },
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
        if (provider == null) {
            item(key = "missing") {
                if (store.loading) {
                    CatalogLoadingState()
                } else {
                    ListEmptyState(
                        title = stringResource(R.string.catalog_missing_title),
                        summary = stringResource(R.string.catalog_missing_summary),
                    )
                }
            }
            return@MiuixScaffoldPage
        }

        item(key = "provider") {
            ProviderSection(title = stringResource(R.string.catalog_provider_section)) {
                EtaPreference(title = stringResource(R.string.catalog_provider_name), summary = provider.name)
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(title = null) {
                    Text(
                        text = "Base URL",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                    )
                    SelectionContainer {
                        Text(
                            text = provider.baseUrl,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            softWrap = true,
                        )
                    }
                }
            }
        }
        item(key = "selection_status") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.catalog_selected_count, selectedIds.size, provider.models.size),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(R.string.catalog_import_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                importError?.let { error ->
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "model_search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.catalog_search_models),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "model_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EtaTextButton(
                    text = stringResource(R.string.catalog_select_all),
                    enabled = filteredModels.isNotEmpty() && !store.importing,
                    onClick = {
                        selectedIds = ArrayList((selectedIds + filteredModels.map { it.modelId }).distinct())
                    },
                )
                EtaTextButton(
                    text = stringResource(R.string.catalog_clear_selection),
                    enabled = selectedIds.isNotEmpty() && !store.importing,
                    onClick = { selectedIds = arrayListOf() },
                )
            }
        }
        item(key = "models_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.catalog_models_title, filteredModels.size))
        }
        if (filteredModels.isEmpty()) {
            item(key = "empty_models") {
                ListEmptyState(title = stringResource(R.string.catalog_no_matching_models))
            }
        } else {
            // 与提供商自己的模型页一致：逐项组合，画成一个连续分组。
            itemsIndexed(filteredModels, key = { _, model -> "model:${model.modelId}" }, contentType = { _, _ -> "model" }) { index, model ->
                EtaPreferenceGroupItem(
                    isFirst = index == 0,
                    isLast = index == filteredModels.lastIndex,
                ) {
                    EtaCheckboxPreference(
                        title = model.displayName,
                        summary = buildString {
                            append(model.modelId)
                            append(" · ")
                            append(
                                resources.getString(
                                    R.string.catalog_context_tokens,
                                    formatCompactTokenCount(model.contextWindow),
                                ),
                            )
                            model.releaseDate?.let { append(" · "); append(it) }
                        },
                        checked = model.modelId in selectedIds,
                        enabled = !store.importing,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) {
                                ArrayList(selectedIds + model.modelId)
                            } else {
                                ArrayList(selectedIds.filterNot { it == model.modelId })
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogLoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(size = 28.dp)
    }
}

private fun CommunityCatalog.sourceLabel(resources: Resources): String = when (source) {
    CommunityCatalogSource.SNAPSHOT -> resources.getString(R.string.catalog_source_snapshot)
    CommunityCatalogSource.CACHE -> resources.getString(R.string.catalog_source_cache)
    CommunityCatalogSource.ONLINE -> resources.getString(R.string.catalog_source_online)
}

private fun catalogSummary(catalog: CommunityCatalog, resources: Resources): String {
    val updated = catalog.fetchedAt?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
    return listOfNotNull(
        resources.getQuantityString(
            R.plurals.catalog_summary_providers,
            catalog.providers.size,
            catalog.providers.size,
        ),
        updated?.let { resources.getString(R.string.catalog_summary_updated, it) },
    ).joinToString(" · ")
}
