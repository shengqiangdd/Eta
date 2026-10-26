package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onInspect: suspend (InputStream) -> EtaBackupSummary,
    onSnapshot: suspend () -> Unit,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportPath by remember { mutableStateOf<String?>(null) }
    var pendingSummary by remember { mutableStateOf<EtaBackupSummary?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 系统 CreateDocument / OpenDocument 在虚拟屏上会把整个任务搬到主屏（实测），
    // 备份的导入导出因此改走应用内选择器：只读路径，不碰系统文件选择器。
    var pickerMode by remember { mutableStateOf<BackupPickerMode?>(null) }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    fun exportTo(directory: String) {
        scope.launch {
            busy = true
            try {
                val target = uniqueBackupFile(File(directory), defaultBackupFileName())
                val summary = FileOutputStream(target).use { onExport(it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    fun inspectImport(path: String) {
        pendingImportPath = path
        // 先只读解析：把「将要导入什么」摆到确认框里。解析失败不弹框，也不碰库。
        scope.launch {
            busy = true
            try {
                pendingSummary = File(path).inputStream().use { onInspect(it) }
                showImportDialog = true
            } catch (throwable: Throwable) {
                pendingImportPath = null
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    when (pickerMode) {
        BackupPickerMode.EXPORT_DIRECTORY -> {
            WorkspacePathPicker(
                initialPath = WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.EXPORT)
                    ?: pickerStartPath(context),
                pickFiles = false,
                title = stringResource(R.string.capability_workspace_pick_folder),
                onBack = { pickerMode = null },
                onPick = { directory ->
                    pickerMode = null
                    WorkspacePickerHistory.remember(context, WorkspacePickerHistory.Kind.EXPORT, directory)
                    exportTo(directory)
                },
            )
            return
        }
        BackupPickerMode.IMPORT_FILE -> {
            WorkspacePathPicker(
                initialPath = WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.IMPORT)
                    ?: pickerStartPath(context),
                pickFiles = true,
                title = stringResource(R.string.capability_workspace_pick_file),
                onBack = { pickerMode = null },
                onPick = { path ->
                    pickerMode = null
                    WorkspacePickerHistory.remember(
                        context,
                        WorkspacePickerHistory.Kind.IMPORT,
                        importHistoryDirectory(path),
                    )
                    inspectImport(path)
                },
            )
            return
        }
        null -> Unit
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_actions))
        }
        item(key = "actions-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (busy) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busy,
                        )
                    },
                    onClick = {
                        pickerMode = BackupPickerMode.EXPORT_DIRECTORY
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = stringResource(R.string.data_backup_import_summary),
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Description,
                            loading = false,
                        )
                    },
                    onClick = {
                        pickerMode = BackupPickerMode.IMPORT_FILE
                    },
                )
            }
        }
    }

    if (showImportDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = pendingSummary?.let { preview ->
                context.getString(
                    R.string.data_backup_import_preview,
                    preview.conversationCount,
                    preview.messageCount,
                    preview.providerCount,
                    preview.characterCount,
                    formatMemorySize(preview.memoryBytes),
                )
            } ?: stringResource(R.string.data_backup_import_confirm_summary),
                onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportPath = null
                    pendingSummary = null
                }
            },
        ) {
            MiuixDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportPath = null
                    pendingSummary = null
                },
                onConfirm = {
                    val path = pendingImportPath ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        busy = true
                        try {
                            // 覆盖前先落一份整库快照：导入中途失败时还有原样可回退的副本。
                            // 快照失败就不导入 —— 没有回退点的破坏性操作不做。
                            onSnapshot()
                            val summary = File(path).inputStream().use { onImport(it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported,
                                    summary.conversationCount,
                                    summary.providerCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportPath = null
                            pendingSummary = null
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupIcon(icon: ImageVector, loading: Boolean) {
    Box(
        // 与 EtaPreferenceIcon 同宽，正文和分割线起点保持对齐。
        modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun formatMemorySize(bytes: Int): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"

/** 备份页自己的两种选择：导出选目录、导入选文件。 */
private enum class BackupPickerMode { EXPORT_DIRECTORY, IMPORT_FILE }

/**
 * 同名文件不覆盖：追加 -2、-3 …。
 *
 * 备份名精确到分钟，一分钟内连点两次导出就会撞名；直接覆盖等于把上一次的备份悄悄弄丢。
 */
private fun uniqueBackupFile(directory: File, name: String): File {
    val first = File(directory, name)
    if (!first.exists()) return first
    val stem = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "")
    var index = 2
    while (true) {
        val candidate = File(directory, if (extension.isEmpty()) "$stem-$index" else "$stem-$index.$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

