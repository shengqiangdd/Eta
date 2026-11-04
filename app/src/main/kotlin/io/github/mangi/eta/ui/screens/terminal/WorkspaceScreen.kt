package io.github.mangi.eta.ui.screens.terminal

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.SessionWorkspaceStore
import io.github.mangi.eta.ui.app.ExportTargetBusy
import io.github.mangi.eta.ui.app.ImportNotFile
import io.github.mangi.eta.ui.app.ImportsTooLarge
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.app.WorkspaceFolderCheck
import io.github.mangi.eta.ui.app.WorkspaceFolderProblem
import io.github.mangi.eta.ui.app.WorkspaceFolderValidator
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkspaceScreen(conversationId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val resources = LocalResources.current
    val globalRoot = remember(context.applicationContext) {
        io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir)
    }
    // 会话工作区以 SessionWorkspaceStore 为准：文件工具（AgentLocalTools）读的是同一处。
    // 界面若继续用 TerminalRuntime.sessionWorkspacePath，就会出现「浏览的是 A、任务写的是 B」。
    var workspaceRevision by remember { mutableIntStateOf(0) }
    val sessionId = conversationId?.takeIf { it.isNotBlank() }
    val customWorkspace = remember(sessionId, workspaceRevision) {
        sessionId?.let { SessionWorkspaceStore.get(context, it) }
    }
    // 已保存但当前不可用（目录被删 / 存储未挂载 / 权限被撤销）：resolve 会静默回退，
    // 界面必须把这件事说出来，否则用户以为任务在自己的目录里跑。
    val customWorkspaceAvailable = remember(sessionId, workspaceRevision) {
        sessionId?.let { SessionWorkspaceStore.isStoredWorkspaceAvailable(context, it) } ?: true
    }
    val sessionRoot = remember(sessionId, workspaceRevision) {
        sessionId?.let { File(SessionWorkspaceStore.resolve(context, it)) }
    }
    // 无会话时页面只有全局工作区，它同样可以指向共享存储里的项目；
    // 否则用户从首页进入「工作区与文件」时根本没有选择文件夹的入口（只能去设置里手输）。
    val globalWorkspace = remember(workspaceRevision) {
        io.github.mangi.eta.agent.terminal.TerminalRuntime.customWorkspacePath
    }
    // 只有有会话时才显示范围切换；无会话时固定全局工作区。
    var useSession by rememberSaveable(conversationId) { mutableStateOf(sessionRoot != null) }
    val activeRoot = if (useSession) sessionRoot else null
    val store = remember(activeRoot) { WorkspaceFileStore(context, activeRoot) }
    val rootPath = (activeRoot ?: globalRoot).path
    val scope = rememberCoroutineScope()
    // 内置目录选择器：系统 OPEN_DOCUMENT_TREE 在本机虚拟屏上不可用（任务被搬回主屏或
    // 窗口建到 display 0），所以选目录改由本页自己完成。见 WorkspaceDirectoryBrowser。
    // null=不显示选择器；folder=选工作区目录；import=选要导入的文件；export=选导出目录。
    var pickerMode by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    // null 表示尚未读到首个结果；与“目录为空”区分，避免加载期间闪出空态。
    var entries by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<WorkspaceMessage?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    // 导入 / 导出同样走应用内选择器：系统 OpenMultipleDocuments / CreateDocument 在本机虚拟屏上
    // 会被 WindowManager 搬回主屏，等于把用户正在看的画面抢走（实测见 AGENT_RUNTIME §工作区）。
    val startImport: (String) -> Unit = { source ->
        scope.launch {
            busy = true
            try {
                store.importPath(source)
                path = "imports"
                revision++
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_imported), isError = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ImportsTooLarge) {
                // 上限 32 MiB：说清楚是「太大」而不是笼统的「失败」，否则用户会反复重试。
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_import_too_large), isError = true)
            } catch (_: ImportNotFile) {
                // 选错了目标（文件夹 / 已消失的路径）：提示去查权限会把人带偏。
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_import_not_file), isError = true)
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    val startExport: (String, String) -> Unit = { source, directory ->
        scope.launch {
            busy = true
            try {
                val target = store.exportToPath(source, directory)
                // 报出真实落地路径：重名会自动加序号，用户需要看到这个差异。
                message = WorkspaceMessage(
                    resources.getString(R.string.capability_workspace_exported_to, target),
                    isError = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ExportTargetBusy) {
                // 「名字被占」与「操作失败」是两回事：导出本身是对的，只差一个不冲突的名字。
                // 也不能自动重试 —— 序号用尽是确定性失败，重试只会再等一轮。
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_export_name_busy), isError = true)
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    // 目录来源只有内置选择器；不管从哪来，都走同一套校验，避免「界面能选、任务里报错」。
    val applyFolder: (String) -> Unit = applyFolder@{ selected ->
        val scopeId = sessionId ?: return@applyFolder
        scope.launch {
            busy = true
            try {
                when (val check = WorkspaceFolderValidator.check(selected)) {
                    is WorkspaceFolderCheck.Ready -> {
                        if (SessionWorkspaceStore.set(context, scopeId, check.path)) {
                            useSession = true
                            workspaceRevision++
                            message = WorkspaceMessage(
                                resources.getString(R.string.capability_workspace_folder_saved),
                                isError = false,
                            )
                        } else {
                            message = WorkspaceMessage(
                                resources.getString(R.string.capability_workspace_failed),
                                isError = true,
                            )
                        }
                    }
                    is WorkspaceFolderCheck.Rejected -> message = WorkspaceMessage(
                        resources.getString(check.problem.messageRes()),
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    // 无会话时选中的目录写入全局工作区（设置页的「终端工作区」用的是同一份设置）。
    // 校验与提示必须和会话工作区完全一致，否则会出现「这里能选、那里报错」。
    val applyGlobalFolder: (String) -> Unit = applyGlobalFolder@{ selected ->
        scope.launch {
            busy = true
            try {
                when (val check = WorkspaceFolderValidator.check(selected)) {
                    is WorkspaceFolderCheck.Ready -> {
                        val error = io.github.mangi.eta.agent.terminal.TerminalRuntime
                            .applyCustomWorkspace(context, check.path)
                        if (error == null) {
                            workspaceRevision++
                            message = WorkspaceMessage(
                                resources.getString(R.string.capability_workspace_folder_saved),
                                isError = false,
                            )
                        } else {
                            message = WorkspaceMessage(error, isError = true)
                        }
                    }
                    is WorkspaceFolderCheck.Rejected -> message = WorkspaceMessage(
                        resources.getString(check.problem.messageRes()),
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path) { entries = null }
    LaunchedEffect(path, revision) {
        try {
            entries = store.list(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
        }
    }
    LaunchedEffect(activeRoot) {
        path = ""
        entries = null
        revision++
    }
    // 选择器默认落点：有全文件访问或 Root 时给共享存储根（要选的几乎都在那里）；
    // 都没有就退到本会话私有目录，至少不会一进选择器就是一页「无法列出」。
    val browseRoot = remember(activeRoot) {
        if (Environment.isExternalStorageManager() || RootAccess.isGranted) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            sessionRoot?.path ?: globalRoot.path
        }
    }
    val mode = pickerMode
    if (mode != null) {
        /* 选择器落点优先级：用户上次真正用过的目录 → 当前设置 / 共享存储根。
           记忆值不做可访问性预检：无「所有文件访问」时 `File.isDirectory()` 会把
           公共目录误判成不可用，而选择器自己能通过 root 列出它。 */
        val remembered = remember(mode) {
            when (mode) {
                "import" -> WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.IMPORT)
                "export" -> WorkspacePickerHistory.get(context, WorkspacePickerHistory.Kind.EXPORT)
                else -> null
            }
        }
        WorkspacePathPicker(
            initialPath = when (mode) {
                "folder" -> customWorkspace ?: browseRoot
                // 全局工作区还没有设置时也优先落在共享存储根：从首页进来想选的
                // 通常就是公共目录里的项目，而不是 App 私有目录。
                "folder-global" -> globalWorkspace ?: browseRoot
                else -> remembered ?: browseRoot
            },
            pickFiles = mode == "import",
            title = stringResource(
                when (mode) {
                    "import" -> R.string.capability_workspace_pick_file
                    "export" -> R.string.capability_workspace_export_pick
                    else -> R.string.capability_workspace_pick_folder
                },
            ),
            onBack = {
                pickerMode = null
                pendingExport = null
            },
            onPick = { selected ->
                val picked = mode
                pickerMode = null
                when (picked) {
                    "import" -> {
                        // 记住的是「文件所在的目录」；下次导入直接落在那里。
                        WorkspacePickerHistory.remember(
                            context,
                            WorkspacePickerHistory.Kind.IMPORT,
                            importHistoryDirectory(selected),
                        )
                        startImport(selected)
                    }
                    "export" -> {
                        WorkspacePickerHistory.remember(
                            context,
                            WorkspacePickerHistory.Kind.EXPORT,
                            selected,
                        )
                        pendingExport?.let { source ->
                            pendingExport = null
                            startExport(source, selected)
                        }
                    }
                    "folder-global" -> applyGlobalFolder(selected)
                    else -> applyFolder(selected)
                }
            },
        )
    } else {
    MiuixScaffoldPage(title = stringResource(R.string.capability_workspace), onBack = onBack) {
        item(key = "workspace-info") {
            EtaPreference(
                title = stringResource(
                    when {
                        !useSession -> R.string.capability_workspace_scope_global
                        customWorkspace == null -> R.string.capability_workspace_private
                        !customWorkspaceAvailable -> R.string.capability_workspace_custom_unavailable
                        else -> R.string.capability_workspace_custom
                    },
                ),
                summary = rootPath,
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
        }
        if (customWorkspace != null && !customWorkspaceAvailable) {
            item(key = "workspace-custom-unavailable") {
                EtaPreference(
                    title = stringResource(R.string.capability_workspace_custom_unavailable_notice),
                    summary = stringResource(R.string.capability_workspace_custom_unavailable_notice_summary),
                    modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
                )
            }
        }
        item(key = "actions") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing)) {
                if (sessionRoot != null) {
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_scope),
                        summary = stringResource(
                            if (useSession) R.string.capability_workspace_scope_current
                            else R.string.capability_workspace_scope_global,
                        ),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Folder, tint = EtaPreferenceColors.Orange) },
                        onClick = { useSession = !useSession },
                    )
                    EtaPreferenceDivider(hasLeading = true)
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_folder),
                        summary = customWorkspace
                            ?: stringResource(R.string.capability_workspace_folder_default),
                        enabled = !busy,
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Folder, tint = EtaPreferenceColors.Orange) },
                        onClick = { pickerMode = "folder" },
                    )
                    if (customWorkspace != null) {
                        EtaPreferenceDivider(hasLeading = true)
                        EtaArrowPreference(
                            title = stringResource(R.string.capability_workspace_folder_reset),
                            summary = stringResource(R.string.capability_workspace_folder_reset_summary),
                            enabled = !busy,
                            startAction = { EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange) },
                            onClick = {
                                sessionId?.let { id ->
                                    SessionWorkspaceStore.clearWorkspace(context, id)
                                    // 恢复默认就是回到本会话私有目录；若此前停在「全局工作区」，
                                    // 不清这一步用户会看到提示成功、范围却仍是全局。
                                    useSession = true
                                    workspaceRevision++
                                    message = WorkspaceMessage(
                                        resources.getString(R.string.capability_workspace_folder_reset_done),
                                        isError = false,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    // 无会话时同样要能选文件夹；写入的是全局工作区（所有对话共用）。
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_global_folder),
                        summary = globalWorkspace
                            ?: stringResource(R.string.capability_workspace_global_folder_default),
                        enabled = !busy,
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Folder, tint = EtaPreferenceColors.Orange) },
                        onClick = { pickerMode = "folder-global" },
                    )
                }
                EtaArrowPreference(
                    title = stringResource(R.string.capability_workspace_import),
                    enabled = !busy,
                    startAction = {
                        if (busy) {
                            Box(Modifier.size(EtaPreferenceDefaults.IconSize), contentAlignment = Alignment.Center) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        } else {
                            EtaPreferenceIcon(Icons.Rounded.DriveFolderUpload, tint = EtaPreferenceColors.Orange)
                        }
                    },
                    onClick = { pickerMode = "import" },
                )

                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.capability_workspace_public),
                    startAction = { EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange) },
                    summary = if (publicAccess) stringResource(R.string.capability_workspace_public_granted) else stringResource(R.string.capability_workspace_public_summary),
                    onClick = {
                        if (!isOnDefaultDisplay(view)) {
                            // 非主屏启动系统设置会把整个任务搬到默认屏（实测），改为提示。
                            message = WorkspaceMessage(
                                resources.getString(R.string.capability_workspace_public_other_display),
                                isError = false,
                            )
                        } else {
                            try {
                                accessLauncher.launch(allFilesAccessIntent(context))
                            } catch (_: android.content.ActivityNotFoundException) {
                                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
                            }
                        }
                    },
                )
            }
        }
        message?.let { current ->
            item(key = "message") {
                EtaPreferenceGroup(modifier = Modifier.animateItem().padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(bottom = EtaPreferenceDefaults.GroupSpacing)) {
                    EtaPreference(
                        title = current.text,
                        titleColor = BasicComponentDefaults.titleColor(
                            color = if (current.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackground,
                        ),
                        endActions = {
                            IconButton(onClick = { message = null }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                    )
                }
            }
        }
        item(key = "path") {
            EtaPreferenceGroupTitle(if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path)
        }
        val currentEntries = entries
        val hasParent = path.isNotBlank()
        if (hasParent) {
            item(key = "parent") {
                EtaPreferenceGroupItem(
                    isFirst = true,
                    isLast = currentEntries.isNullOrEmpty(),
                    hasLeading = true,
                ) {
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_parent),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange) },
                        onClick = { path = path.substringBeforeLast('/', "") },
                    )
                }
            }
        }
        when {
            currentEntries == null -> item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
            currentEntries.isEmpty() -> item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_workspace_empty),
                    summary = stringResource(R.string.capability_workspace_empty_summary),
                    icon = Icons.Rounded.FolderOpen,
                )
            }
            // 文件逐项进入 Lazy 列表，但画成一个连续分组，与其他设置页的分组列表一致。
            else -> itemsIndexed(currentEntries, key = { _, entry -> entry.path }) { index, entry ->
                EtaPreferenceGroupItem(
                    isFirst = index == 0 && !hasParent,
                    isLast = index == currentEntries.lastIndex,
                    hasLeading = true,
                ) {
                    EtaArrowPreference(
                        title = entry.name,
                        summary = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                            else stringResource(
                                R.string.capability_workspace_file_export,
                                Formatter.formatShortFileSize(context, entry.size),
                            ),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                enabled = !busy,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        enabled = !busy,
                        onClick = {
                            if (entry.directory) {
                                path = entry.path
                            } else {
                                pendingExport = entry.path
                                pickerMode = "export"
                            }
                        },
                    )
                }
            }
        }
    }
    }
}

/** 导入导出结果；失败需要醒目区分，不能和成功提示同色。 */
private data class WorkspaceMessage(val text: String, val isError: Boolean)

/** 文件夹不可用的原因 → 文案。会话与全局工作区共用同一套解释。 */
private fun WorkspaceFolderProblem.messageRes(): Int = when (this) {
    WorkspaceFolderProblem.BLOCKED -> R.string.capability_workspace_folder_blocked
    WorkspaceFolderProblem.RESTRICTED -> R.string.capability_workspace_folder_restricted
    WorkspaceFolderProblem.NO_APP_ACCESS -> R.string.capability_workspace_folder_no_access
    WorkspaceFolderProblem.NOT_DIRECTORY -> R.string.capability_workspace_folder_not_directory
    WorkspaceFolderProblem.NOT_WRITABLE -> R.string.capability_workspace_folder_not_writable
    WorkspaceFolderProblem.UNAVAILABLE -> R.string.capability_workspace_failed
}
