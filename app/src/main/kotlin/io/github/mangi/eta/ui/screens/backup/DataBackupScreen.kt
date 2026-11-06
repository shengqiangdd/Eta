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
import androidx.compose.runtime.collectAsState
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
import io.github.mangi.eta.data.backup.AutoBackupOutcome
import io.github.mangi.eta.data.backup.AutoBackupRepository
import io.github.mangi.eta.data.backup.AutoBackupScheduler
import io.github.mangi.eta.data.backup.StorageFailures
import io.github.mangi.eta.data.model.AutoBackupSettings
import io.github.mangi.eta.data.repository.EtaBackupException
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.app.ConversationTimeLabels
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownItem
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
    var busyOp by remember { mutableStateOf(BackupOp.IDLE) }
    val busy = busyOp != BackupOp.IDLE
    var pendingImportPath by remember { mutableStateOf<String?>(null) }
    var pendingSummary by remember { mutableStateOf<EtaBackupSummary?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 系统 CreateDocument / OpenDocument 在虚拟屏上会把整个任务搬到主屏（实测），
    // 备份的导入导出因此改走应用内选择器：只读路径，不碰系统文件选择器。
    var pickerMode by remember { mutableStateOf<BackupPickerMode?>(null) }
    // 自动备份状态跟着设置流走：开关、间隔、上次结果都在这里。
    val autoBackup by remember { AutoBackupRepository.settingsFlow() }
        .collectAsState(initial = null)

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(context, failureMessage(context, throwable), Toast.LENGTH_LONG).show()
    }

    fun exportTo(directory: String) {
        scope.launch {
            busyOp = BackupOp.EXPORT
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
                busyOp = BackupOp.IDLE
            }
        }
    }

    fun inspectImport(path: String) {
        pendingImportPath = path
        // 先只读解析：把「将要导入什么」摆到确认框里。解析失败不弹框，也不碰库。
        scope.launch {
            busyOp = BackupOp.IMPORT_PARSING
            try {
                pendingSummary = openSelectedBackup(context, path).use { onInspect(it) }
                showImportDialog = true
            } catch (throwable: Throwable) {
                pendingImportPath = null
                showFailure(throwable)
            } finally {
                busyOp = BackupOp.IDLE
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
        item(key = "auto-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_auto_title))
        }
        item(key = "auto-card") {
            val current = autoBackup
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.data_backup_auto_enable),
                    checked = current?.enabled == true,
                    summary = stringResource(R.string.data_backup_auto_enable_summary),
                    enabled = !busy && current != null,
                    onCheckedChange = { enabled ->
                        scope.launch { AutoBackupRepository.setEnabled(enabled) }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaDropdownPreference(
                    title = stringResource(R.string.data_backup_auto_interval),
                    items = AutoBackupScheduler.INTERVAL_CHOICES.map { hours ->
                        DropdownItem(text = intervalLabel(context, hours))
                    },
                    selectedIndex = AutoBackupScheduler.INTERVAL_CHOICES
                        .indexOf(current?.intervalHours ?: AutoBackupSettings.DEFAULT_INTERVAL_HOURS)
                        .coerceAtLeast(0),
                    enabled = !busy && current?.enabled == true,
                    onSelectedIndexChange = { index ->
                        val hours = AutoBackupScheduler.INTERVAL_CHOICES.getOrNull(index)
                        if (hours != null) {
                            scope.launch { AutoBackupRepository.setIntervalHours(hours) }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.data_backup_auto_last),
                    summary = autoBackupSummary(context, current),
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_run_now),
                    summary = if (busyOp == BackupOp.BACKUP) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_auto_run_now_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busyOp == BackupOp.BACKUP,
                        )
                    },
                    onClick = {
                        scope.launch {
                            busyOp = BackupOp.BACKUP
                            try {
                                val outcome = AutoBackupRepository.runNow(context)
                                Toast.makeText(
                                    context,
                                    autoBackupOutcomeMessage(context, outcome),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (throwable: Throwable) {
                                showFailure(throwable)
                            } finally {
                                busyOp = BackupOp.IDLE
                            }
                        }
                    },
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
                    summary = if (busyOp == BackupOp.EXPORT) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busyOp == BackupOp.EXPORT,
                        )
                    },
                    onClick = {
                        pickerMode = BackupPickerMode.EXPORT_DIRECTORY
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = when (busyOp) {
                        BackupOp.IMPORT_PARSING -> stringResource(R.string.data_backup_import_parsing)
                        BackupOp.IMPORTING -> stringResource(R.string.data_backup_working)
                        else -> stringResource(R.string.data_backup_import_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Description,
                            loading = busyOp == BackupOp.IMPORT_PARSING || busyOp == BackupOp.IMPORTING,
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
                        busyOp = BackupOp.IMPORTING
                        try {
                            // 覆盖前先落一份整库快照：导入中途失败时还有原样可回退的副本。
                            // 快照失败就不导入 —— 没有回退点的破坏性操作不做；并且要明确告诉用户
                            // 「一个字节都还没动」，否则失败会被理解成「导入了一半」。
                            try {
                                onSnapshot()
                            } catch (snapshotFailure: CancellationException) {
                                throw snapshotFailure
                            } catch (snapshotFailure: Throwable) {
                                throw EtaBackupException(
                                    context.getString(
                                        R.string.data_backup_snapshot_failed,
                                        failureMessage(context, snapshotFailure),
                                    ),
                                    snapshotFailure,
                                )
                            }
                            val summary = openSelectedBackup(context, path).use { onImport(it) }
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
                            busyOp = BackupOp.IDLE
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

/** 间隔选项的显示文案。 */
private fun intervalLabel(context: Context, hours: Int): String = when (hours) {
    6 -> context.getString(R.string.data_backup_auto_interval_6h)
    12 -> context.getString(R.string.data_backup_auto_interval_12h)
    24 -> context.getString(R.string.data_backup_auto_interval_24h)
    72 -> context.getString(R.string.data_backup_auto_interval_72h)
    168 -> context.getString(R.string.data_backup_auto_interval_168h)
    else -> context.resources.getQuantityString(
        R.plurals.data_backup_auto_interval_hours,
        hours,
        hours,
    )
}

/** 「上次备份」那一行：有错误先说错误（否则用户以为自动备份正在好好工作）。 */
private fun autoBackupSummary(context: Context, settings: AutoBackupSettings?): String {
    if (settings == null) return ""
    val error = settings.lastError
    if (!error.isNullOrBlank()) {
        return context.getString(R.string.data_backup_auto_failed, error)
    }
    if (settings.lastBackupAt <= 0L) {
        return context.getString(R.string.data_backup_auto_never)
    }
    val whenLabel = ConversationTimeLabels.label(settings.lastBackupAt)
    val bytes = settings.lastBackupPath
        ?.let { path -> File(path).takeIf { it.isFile }?.length() }
        ?: 0L
    return if (bytes > 0L) {
        context.getString(R.string.data_backup_auto_last_value, whenLabel, formatFileSize(bytes))
    } else {
        whenLabel
    }
}

private fun autoBackupOutcomeMessage(context: Context, outcome: AutoBackupOutcome): String {    val error = outcome.error
    return when {
        error != null -> context.getString(R.string.data_backup_auto_failed, error)
        outcome.skippedEmpty -> context.getString(R.string.data_backup_auto_skipped_empty)
        else -> context.getString(
            R.string.data_backup_auto_done,
            outcome.file?.name.orEmpty(),
            outcome.pruned,
        )
    }
}

/**
 * 打开用户选中的备份文件。
 *
 * 读不了要说「这个文件读不了」——原来的归因会把它说成「目标目录不可写」，把人指去改权限，
 * 而真正该做的是把文件换个位置（或确认它还在）。
 */
private fun openSelectedBackup(context: Context, path: String): InputStream = try {
    File(path).inputStream()
} catch (failure: IOException) {
    throw EtaBackupException(context.getString(R.string.data_backup_error_unreadable), failure)
}

/**
 * 把 errno 级失败翻译成用户能据以行动的话；其余保持原样（多半已经是写好的中文领域错误）。
 *
 * 领域层已经给出可操作说明时原样使用：再按 cause 归因一次，会把「所选文件读不了」
 * 改写回「目标目录不可写」（两者经常共享同一个 EACCES）。
 */
private fun failureMessage(context: Context, throwable: Throwable): String = when {
    throwable is EtaBackupException && !throwable.message.isNullOrBlank() -> throwable.message.orEmpty()
    StorageFailures.isOutOfSpace(throwable) -> context.getString(R.string.data_backup_error_no_space)
    StorageFailures.isNotWritable(throwable) -> context.getString(R.string.data_backup_error_not_writable)
    else -> throwable.message ?: context.getString(R.string.data_backup_failed)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
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

/** 哪个操作正在跑：三个按钮互斥，但「处理中」只能出现在真正在跑的那一个上。 */
private enum class BackupOp { IDLE, BACKUP, EXPORT, IMPORT_PARSING, IMPORTING }

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

