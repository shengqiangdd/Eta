package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.widget.Toast
<<<<<<< HEAD
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
=======
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
>>>>>>> origin/main
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
<<<<<<< HEAD
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
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
=======
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
>>>>>>> origin/main
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
<<<<<<< HEAD
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
=======
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
>>>>>>> origin/main
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

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
    // -1 表示还没读到可估算的比例（文件读不了 / 大小未知）；0~100 是已读的压缩字节占比。
    var parsePercent by remember { mutableIntStateOf(-1) }
    // 导出已写入的字节数。目标文件事先不知道会多大，所以只报「写了多少」，
    // 不假装有百分比 —— 导入那边分母是文件大小，导出这边没有分母。
    var exportWritten by remember { mutableLongStateOf(0L) }
    // 失败说明放对话框而不是 Toast：Toast 单行约 22 字，长文案会被截成「…数据…」，
    // 而「数据未改动」恰恰是最不能丢的那半句（真机上就是这样被截掉的）。
    var failureNotice by remember { mutableStateOf<String?>(null) }
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
        // 带上 message 与 cause：只打类型时，`EtaBackupException` 本身什么都不说明，
        // 而真正的原因（EACCES？ENOENT？格式？）全在 message / cause 里。截断 200 字避免长行。
        val detail = throwable.message?.take(200)?.replace('\n', ' ').orEmpty()
        val causeDetail = throwable.cause?.message?.take(200)?.replace('\n', ' ').orEmpty()
        AndroidAgentLogger.error(
            "Agent backup UI failed: type=${throwable.safeLogType()} message=$detail cause=$causeDetail\n" +
                throwable.safeStackTrace(),
        )
        failureNotice = failureMessage(context, throwable)
    }

    fun exportTo(directory: String) {
        scope.launch {
            busyOp = BackupOp.EXPORT
            exportWritten = 0L
            val targetDir = File(directory)
            // 上一次若在导出途中被杀，catch 里的 partial.delete() 根本不会执行，用户的目录里
            // 会留下一个几十到几百 MB 的 `.part`。它既不是可用备份，也不会被 uniqueBackupFile
            // 当成重名（那个只看正式名），所以只会一直占着空间。动手前先扫掉自家的临时文件。
            // 清理结果**不单独弹 Toast**：紧接着就是导出完成的提示，两条会互相顶掉
            // （Android 用新 Toast 替换旧的），真机上最后只剩一条。合并进成功文案。
            val cleaned = cleanOrphanPartials(targetDir)
            val target = uniqueBackupFile(targetDir, defaultBackupFileName())
            // 先写 .part，成功后才改名：中途失败或被杀只会留下一个显而易见的临时文件，
            // 不会让用户把「写了一半的备份」当成能用的备份。旧实现靠 cache 中转来保证
            // 「目标要么完整要么没动」，代价是同一份数据写两遍、磁盘要两份。
            val partial = File(target.parentFile, target.name + PARTIAL_SUFFIX)
            try {
                val summary = FileOutputStream(partial).use { raw ->
                    onExport(CountingOutputStream(raw) { written -> exportWritten = written })
                }
                if (!partial.renameTo(target)) {
                    throw EtaBackupException(context.getString(R.string.data_backup_error_rename))
                }
                val base = context.getString(
                    R.string.data_backup_exported,
                    summary.conversationCount,
                    summary.providerCount,
                )
                // 清理与导出合并成一句：分开弹会被后者顶掉，用户永远看不到前一条。
                val message = if (cleaned.count > 0) {
                    base + "；" + context.getString(
                        R.string.data_backup_cleaned_partials,
                        cleaned.count,
                        formatBytes(cleaned.bytes),
                    )
                } else {
                    base
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } catch (throwable: Throwable) {
                partial.delete()
                showFailure(throwable)
            } finally {
                busyOp = BackupOp.IDLE
                exportWritten = 0L
            }
        }
    }

    fun inspectImport(path: String) {
        pendingImportPath = path
        // 先只读解析：把「将要导入什么」摆到确认框里。解析失败不弹框，也不碰库。
        val total = File(path).length()
        parsePercent = -1
        scope.launch {
            busyOp = BackupOp.IMPORT_PARSING
            try {
                pendingSummary = openSelectedBackup(context, path).use { raw ->
                    // 进度按「已读压缩字节 / 文件大小」算：全量回退时 stage 也要读到底，进度能走完。
                    onInspect(ProgressInputStream(raw, total) { percent -> parsePercent = percent })
                }
                showImportDialog = true
            } catch (throwable: Throwable) {
                pendingImportPath = null
                showFailure(throwable)
            } finally {
                parsePercent = -1
                busyOp = BackupOp.IDLE
            }
        }
    }

<<<<<<< HEAD
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
                        if (exportWritten > 0L) {
                            stringResource(
                                R.string.data_backup_export_progress,
                                (exportWritten / (1024 * 1024)).toInt(),
                            )
                        } else {
                            stringResource(R.string.data_backup_working)
                        }
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
                        BackupOp.IMPORT_PARSING -> if (parsePercent >= 0) {
                            stringResource(R.string.data_backup_import_parsing_progress, parsePercent)
                        } else {
                            stringResource(R.string.data_backup_import_parsing)
                        }
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
=======
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.data_backup_title),
                onBack = onBack,
            ) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.data_backup_warning_title),
                        summary = stringResource(R.string.data_backup_warning_summary),
                        showChevron = false,
                    )
                }
                Group(title = stringResource(R.string.data_backup_actions)) {
                    CouiPreference(
                        title = stringResource(R.string.data_backup_export),
                        summary = if (busy) {
                            stringResource(R.string.data_backup_working)
                        } else {
                            stringResource(R.string.data_backup_export_summary)
                        },
                        enabled = !busy,
                        trailing = if (busy) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        onClick = { exportLauncher.launch(defaultBackupFileName()) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.data_backup_import),
                        summary = stringResource(R.string.data_backup_import_summary),
                        enabled = !busy,
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                    )
                }
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.data_backup_title),
                onBack = onBack,
            ) {
                item(key = "warning") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.data_backup_warning_title),
                            summary = stringResource(R.string.data_backup_warning_summary),
                        )
                    }
                }
                item(key = "actions") {
                    SmallTitle(stringResource(R.string.data_backup_actions))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_export),
                            summary = if (busy) {
                                stringResource(R.string.data_backup_working)
                            } else {
                                stringResource(R.string.data_backup_export_summary)
                            },
                            enabled = !busy,
                            endActions = {
                                if (busy) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            onClick = {
                                exportLauncher.launch(defaultBackupFileName())
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_import),
                            summary = stringResource(R.string.data_backup_import_summary),
                            enabled = !busy,
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                        )
                    }
                }
>>>>>>> origin/main
            }
        }
    }

    failureNotice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_failed_title),
            summary = notice,
            onDismissRequest = { failureNotice = null },
        ) {
            EtaTextButton(
                text = stringResource(R.string.action_close),
                onClick = { failureNotice = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showImportDialog) {
<<<<<<< HEAD
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
=======
        val confirmText = if (busy) {
            stringResource(R.string.data_backup_working)
>>>>>>> origin/main
        } else {
            stringResource(R.string.action_import)
        }
        val onDismissImport: () -> Unit = {
            if (!busy) {
                showImportDialog = false
                pendingImportUri = null
            }
        }
        val onConfirmImport: () -> Unit = {
            val uri = pendingImportUri
            if (uri != null) {
                showImportDialog = false
                scope.launch {
                    busy = true
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error(context.getString(R.string.data_backup_file_open_failed))
                        val summary = input.use { onImport(it) }
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
                        pendingImportUri = null
                        busy = false
                    }
                }
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.data_backup_import_confirm_title),
                    message = stringResource(R.string.data_backup_import_confirm_summary),
                    confirmText = confirmText,
                    confirmEnabled = !busy,
                    cancelEnabled = !busy,
                    destructive = true,
                    onConfirm = onConfirmImport,
                    onDismissRequest = onDismissImport,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.data_backup_import_confirm_title),
                    summary = stringResource(R.string.data_backup_import_confirm_summary),
                    onDismissRequest = onDismissImport,
                ) {
                    MiuixDialogActions(
                        confirmText = confirmText,
                        destructive = true,
                        cancelEnabled = !busy,
                        confirmEnabled = !busy,
                        onCancel = onDismissImport,
                        onConfirm = onConfirmImport,
                    )
                }
            }
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
 * 数字节进度。
 *
 * 只在**最外层原始流**上计数，所以分母就是文件大小；解析器内部再包 gzip、再缓存重放，
 * 都不影响这里的口径。百分比只在跨整数位时回调一次，避免每读 8 KB 就重组一次界面。
 *
 * total ≤ 0（拿不到文件大小）时干脆不报进度，界面退回到「不知道还要多久」的原提示。
 */
private class ProgressInputStream(
    input: InputStream,
    private val total: Long,
    private val onPercent: (Int) -> Unit,
) : FilterInputStream(input) {
    private var readBytes = 0L
    private var lastPercent = -1

    override fun read(): Int = super.read().also { if (it >= 0) advance(1L) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) advance(it.toLong()) }

    private fun advance(count: Long) {
        readBytes += count
        if (total <= 0L) return
        val percent = ((readBytes * 100) / total).toInt().coerceIn(0, 100)
        if (percent != lastPercent) {
            lastPercent = percent
            onPercent(percent)
        }
    }
}

/**
 * 数字节并降频回调：直接写目标流之后，界面得能看到「真的在动」。
 *
 * 每 4 MB 回报一次（406 MB 的备份约 100 次，界面不费力）；
 * 必须重写 `write(ByteArray, off, len)` —— `FilterOutputStream` 的默认实现是逐字节
 * 转调 `write(int)`，那样一次批量写会被记成上万次，回调也会被拆碎。
 */
private class CountingOutputStream(
    target: OutputStream,
    private val onWritten: (Long) -> Unit,
) : FilterOutputStream(target) {
    private var written = 0L
    private var reported = 0L

    override fun write(b: Int) {
        out.write(b)
        account(1L)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        account(length.toLong())
    }

    private fun account(count: Long) {
        written += count
        if (written - reported >= REPORT_STEP) {
            reported = written
            onWritten(written)
        }
    }

    private companion object {
        const val REPORT_STEP = 4L * 1024 * 1024
    }
}

/** 导出时先写这个后缀，成功后才改名。 */
private const val PARTIAL_SUFFIX = ".part"

/**
 * 孤儿临时文件的完整后缀（正式名 + [PARTIAL_SUFFIX]），用来判断「这是上次导出留下的」。
 *
 * 不能只看 `.part`：那个后缀太通用，目录里用户的别的临时文件会被误删。
 * 与 [defaultBackupFileName] 的命名绑定，改命名时 `DataBackupPartialCleanupTest` 会红。
 */
internal const val BACKUP_PARTIAL_SUFFIX = ".eta-backup.json" + PARTIAL_SUFFIX

/**
 * 删掉目录里上次中断留下的导出临时文件，返回删了几个、释放了多少字节。
 *
 * 进程被杀时 `partial.delete()` 之前那段 catch 根本不会跑到，所以清理只能放到下一次
 * 动手之前做。目录读不了（不存在 / 无权限）时返回空结果 —— 清理失败不该挡住导出本身。
 */
internal fun cleanOrphanPartials(directory: File): PartialCleanup {
    val children = directory.listFiles() ?: return PartialCleanup(0, 0L)
    var removed = 0
    var bytes = 0L
    for (child in children) {
        if (!child.isFile || !child.name.endsWith(BACKUP_PARTIAL_SUFFIX)) continue
        val size = child.length()
        if (child.delete()) {
            removed++
            bytes += size
        } else {
            AndroidAgentLogger.error("Agent backup: cannot delete stale partial ${child.name.take(80)}")
        }
    }
    return PartialCleanup(removed, bytes)
}

/** 清理结果：只报「几个」不报「多大」时，38.9 MB 和 3 KB 看起来一模一样。 */
internal data class PartialCleanup(val count: Int, val bytes: Long)

/** 人类可读的字节数；只用于提示文案。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (value >= 100) "${value.toInt()} ${units[index]}"
    else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
}

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

