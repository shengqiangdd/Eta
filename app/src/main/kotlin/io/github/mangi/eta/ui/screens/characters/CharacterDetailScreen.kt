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
<<<<<<< HEAD
import android.widget.Toast
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.repository.ConversationExportSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.mangi.eta.R
=======
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
>>>>>>> origin/main
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
<<<<<<< HEAD
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
=======
import io.github.mangi.eta.ui.app.LocalUiStyle
>>>>>>> origin/main
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.navigation.AppRoute
<<<<<<< HEAD
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import top.yukonga.miuix.kmp.basic.BasicComponentColors
=======
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
>>>>>>> origin/main
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
<<<<<<< HEAD
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
=======
    val userName = if (store.usePersona) store.persona.name.ifBlank { "用户" } else "用户"

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试")
                    return@CouiScaffoldPage
                }
                CharacterHero(
                    profile = profile,
                    busy = store.busy,
                    onChangeAvatar = { avatarPicker.launch("image/*") },
                    onToggleFavorite = { store.toggleFavorite(id) },
                    onStart = { store.startConversation(id, onStart) },
                    onReadDescription = {
                        preview = CharacterTextPreview(
                            "角色设定",
                            CharacterMacros.expand(profile.card.description, profile.card, userName),
                        )
                    },
                    userName = userName,
                )
                Group {
                    CouiSwitchPreference(
                        title = "使用我的人设",
                        summary = store.persona.name.ifBlank { "未设置称呼" },
                        checked = store.usePersona,
                        onCheckedChange = { store.usePersona = it },
                    )
                    Divider()
                    CouiPreference(
                        title = "编辑我的人设",
                        onClick = { onNavigate(AppRoute.CharacterPersona) },
>>>>>>> origin/main
                    )
                }
                CouiSectionTitle(text = "开场白")
                val greetings = remember(profile.card, userName) {
                    (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                        .map { CharacterMacros.expand(it, profile.card, userName) }
                }
                GreetingPager(
                    greetings = greetings,
                    selected = store.greetingIndex,
                    onSelect = { store.greetingIndex = it },
                    onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                )
                Group(title = "管理") {
                    CouiPreference(
                        title = "编辑角色",
                        enabled = !store.busy,
                        onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = "剧情记忆",
                        summary = "此角色各次对话共享的故事与关系记录",
                        onClick = { onNavigate(AppRoute.CharacterMemory(id)) },
                    )
                    Divider()
                    CouiPreference(
                        title = "复制角色",
                        enabled = !store.busy,
                        onClick = { store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) } },
                    )
                    Divider()
                    CouiPreference(
                        title = "导出 PNG 角色卡",
                        enabled = !store.busy,
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                    )
                    Divider()
                    CouiPreference(
                        title = "导出 JSON 角色卡",
                        enabled = !store.busy,
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                    )
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    Group {
                        CouiPreference(
                            title = "兼容说明",
                            summary = "${store.compatibilityWarnings.size} 项内容按兼容范围处理",
                            onClick = { showCompatibility = !showCompatibility },
                        )
                        if (showCompatibility) {
                            Divider()
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
                Group {
                    CouiPreference(
                        title = "删除角色",
                        titleColor = dialogColors.destructive,
                        enabled = !store.busy,
                        showChevron = false,
                        onClick = { showDeleteConfirm = true },
                    )
                }
                CouiPageBottomSpacer()
            }
        }
<<<<<<< HEAD
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
=======

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    item { CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试") }
                    return@MiuixScaffoldPage
                }
                item(key = "hero") {
                    CharacterHero(
                        profile = profile,
                        busy = store.busy,
                        onChangeAvatar = { avatarPicker.launch("image/*") },
                        onToggleFavorite = { store.toggleFavorite(id) },
                        onStart = { store.startConversation(id, onStart) },
                        onReadDescription = {
                            preview = CharacterTextPreview(
                                "角色设定",
                                CharacterMacros.expand(profile.card.description, profile.card, userName),
                            )
                        },
                        userName = userName,
                    )
                }
                item(key = "persona") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = "使用我的人设",
                            summary = store.persona.name.ifBlank { "未设置称呼" },
                            checked = store.usePersona,
                            onCheckedChange = { store.usePersona = it },
                        )
                        ArrowPreference(title = "编辑我的人设", onClick = { onNavigate(AppRoute.CharacterPersona) })
                    }
                }
                item(key = "greetings") {
                    SmallTitle("开场白")
                    val greetings = remember(profile.card, userName) {
                        (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                            .map { CharacterMacros.expand(it, profile.card, userName) }
                    }
                    GreetingPager(
                        greetings = greetings,
                        selected = store.greetingIndex,
                        onSelect = { store.greetingIndex = it },
                        onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                    )
                }
                item(key = "management") {
                    SmallTitle("管理")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(title = "编辑角色", enabled = !store.busy, onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        })
                        ArrowPreference(title = "剧情记忆", summary = "此角色各次对话共享的故事与关系记录", onClick = {
                            onNavigate(AppRoute.CharacterMemory(id))
                        })
                        ArrowPreference(title = "复制角色", enabled = !store.busy, onClick = {
                            store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                        })
                        ArrowPreference(
                            title = "导出 PNG 角色卡",
                            enabled = !store.busy,
                            onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        )
                        ArrowPreference(
                            title = "导出 JSON 角色卡",
                            enabled = !store.busy,
                            onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                        )
                    }
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    item(key = "compatibility") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            ArrowPreference(
                                title = "兼容说明",
                                summary = "${store.compatibilityWarnings.size} 项内容按兼容范围处理",
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
                item(key = "danger") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "删除角色",
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            enabled = !store.busy,
                            onClick = { showDeleteConfirm = true },
                        )
                    }
                }
>>>>>>> origin/main
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
<<<<<<< HEAD
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
=======
        val message = "「${profile.card.name}」将从角色库移除，角色图片与剧情记忆一并删除；已有对话保留。此操作无法撤销。"
        val onConfirmDelete: () -> Unit = {
            showDeleteConfirm = false
            store.delete(id) { onBack() }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = "删除角色",
                    message = message,
                    confirmText = "删除",
                    confirmEnabled = !store.busy,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { showDeleteConfirm = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = "删除角色",
                    summary = message,
                    onDismissRequest = { showDeleteConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = "删除",
                        destructive = true,
                        confirmEnabled = !store.busy,
                        onCancel = { showDeleteConfirm = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
>>>>>>> origin/main
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
