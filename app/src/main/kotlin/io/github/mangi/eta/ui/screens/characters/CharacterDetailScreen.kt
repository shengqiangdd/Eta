package io.github.mangi.eta.ui.screens.characters

import android.content.res.Resources
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.repository.ConversationExportSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal fun greetingTitle(resources: Resources, index: Int) = if (index == 0) {
    resources.getString(R.string.character_greeting_default)
} else {
    resources.getString(R.string.character_greeting_index, index + 1)
}

private data class CharacterTextPreview(val title: String, val text: String)

@Composable
internal fun CharacterDetailScreen(
    id: String,
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onStart: (RoleplayBinding, String) -> Unit,
) {
    var showCompatibility by rememberSaveable(id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(id) { mutableStateOf(false) }
    var preview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    val context = LocalContext.current
    // 系统 GetContent 在虚拟屏上会把任务搬到主屏（实测），换头像因此走应用内选择器。
    var pickingAvatar by remember { mutableStateOf(false) }
    if (pickingAvatar) {
        WorkspacePathPicker(
            initialPath = WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.IMPORT)
                ?: pickerStartPath(context),
            pickFiles = true,
            title = stringResource(R.string.capability_workspace_pick_file),
            onBack = { pickingAvatar = false },
            onPick = { path ->
                pickingAvatar = false
                WorkspacePickerHistory.remember(
                    context,
                    WorkspacePickerHistory.Kind.IMPORT,
                    importHistoryDirectory(path),
                )
                store.changeAvatarFromFile(id, path)
            },
        )
        return
    }
    val profile = store.selected?.takeIf { it.id == id }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // 导出不弹系统「保存到…」：那个选择器由系统决定挂在哪个显示上，实测会把任务连同
    // 选择器一并迁到主屏，虚拟屏里根本用不了。直接写公共下载目录，失败时再提示。
    fun exportCard(name: String, format: CharacterCardFormat, extension: String, mimeType: String) {
        scope.launch {
            try {
                val sink = ConversationExportSink.create(
                    context = context,
                    fileName = characterExportName(name, extension, resources),
                    mimeType = mimeType,
                )
                try {
                    sink.stream.use { CharacterRepository.export(id, format, it) }
                } catch (failure: Throwable) {
                    sink.abort()
                    throw failure
                }
                sink.finish()
                Toast.makeText(
                    context,
                    resources.getString(R.string.character_exported_to, sink.label),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.character_error_export),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    // LazyListScope 的 content lambda 不是 Composable 上下文，字符串必须在页面级先取好。
    val defaultUser = stringResource(R.string.character_default_user)
    val descriptionTitle = stringResource(R.string.character_description_title)
    MiuixScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
        if (profile == null) {
            item { CharacterPageMessage(if (store.busy) stringResource(R.string.character_reading) else stringResource(R.string.character_read_failed)) }
            return@MiuixScaffoldPage
        }
        val userName = if (store.usePersona) store.persona.name.ifBlank { defaultUser } else defaultUser
        item(key = "hero") {
            CharacterHero(
                profile = profile,
                busy = store.busy,
                onChangeAvatar = { if (!store.busy) pickingAvatar = true },
                onToggleFavorite = { store.toggleFavorite(id) },
                onStart = { store.startConversation(id, onStart) },
                onReadDescription = {
                    preview = CharacterTextPreview(
                        descriptionTitle,
                        CharacterMacros.expand(profile.card.description, profile.card, userName),
                    )
                },
                userName = userName,
            )
        }
        item(key = "persona") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.character_use_persona),
                    summary = store.persona.name.ifBlank { stringResource(R.string.character_persona_unset) },
                    checked = store.usePersona,
                    onCheckedChange = { store.usePersona = it },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.character_edit_persona), onClick = { onNavigate(AppRoute.CharacterPersona) })
            }
        }
        item(key = "greetings-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_greetings_section)) }
        item(key = "greetings") {
            // 预览与开始对话后看到的文本一致：宏按当前是否使用人设展开。
            val greetings = remember(profile.card, userName) {
                (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                    .map { CharacterMacros.expand(it, profile.card, userName) }
            }
            GreetingPager(
                greetings = greetings,
                selected = store.greetingIndex,
                onSelect = { store.greetingIndex = it },
                onRead = { index -> preview = CharacterTextPreview(greetingTitle(resources, index), greetings[index]) },
            )
        }
        item(key = "management-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_management_section)) }
        item(key = "management") {
            EtaPreferenceGroup {
                EtaArrowPreference(title = stringResource(R.string.route_character_editor), enabled = !store.busy, onClick = {
                    store.discardEditor()
                    onNavigate(AppRoute.CharacterEditor(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.route_character_memory), summary = stringResource(R.string.character_memory_summary), onClick = {
                    onNavigate(AppRoute.CharacterMemory(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.character_duplicate), enabled = !store.busy, onClick = {
                    store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.character_export_png),
                    enabled = !store.busy,
                    onClick = { exportCard(profile.card.name, CharacterCardFormat.PNG, "png", "image/png") },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.character_export_json),
                    enabled = !store.busy,
                    onClick = { exportCard(profile.card.name, CharacterCardFormat.JSON, "json", "application/json") },
                )
            }
        }
        if (store.compatibilityWarnings.isNotEmpty()) {
            item(key = "compatibility") {
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.character_compatibility),
                        summary = pluralStringResource(R.plurals.character_compatibility_summary, store.compatibilityWarnings.size, store.compatibilityWarnings.size),
                        onClick = { showCompatibility = !showCompatibility },
                    )
                    if (showCompatibility) {
                        store.compatibilityWarnings.forEach { warning ->
                            Text(
                                text = warning,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        // 破坏性操作单独成组并放在页面末尾，与日常管理操作拉开距离。
        item(key = "danger") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.character_delete),
                    titleColor = BasicComponentColors(
                        color = MiuixTheme.colorScheme.error,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                    ),
                    enabled = !store.busy,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.character_delete),
            summary = stringResource(R.string.character_delete_summary, profile.card.name),
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.character_delete_confirm),
                destructive = true,
                confirmEnabled = !store.busy,
                onCancel = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    store.delete(id) { onBack() }
                },
            )
        }
    }

    preview?.let { current ->
        ItemDescriptionDialog(
            title = current.title,
            description = current.text,
            onDismiss = { preview = null },
        )
    }
}

private fun characterExportName(name: String, extension: String, resources: Resources): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64)
        .ifBlank { resources.getString(R.string.character_export_default_name) }
    return "$basename.$extension"
}
