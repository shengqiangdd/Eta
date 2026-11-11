package io.github.mangi.eta.ui.screens.terminal

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.WorkspacePathPicker
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
    val uiStyle = LocalUiStyle.current
    val onOpenPublicAccess: () -> Unit = {
        try {
            accessLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (_: android.content.ActivityNotFoundException) {
            message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
        }
    }

    val listContent: LazyListScope.() -> Unit = {
        item(key = "workspace-info") {
            if (uiStyle == AppearanceUiStyle.COUI) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_private),
                        summary = stringResource(R.string.capability_workspace_private_summary),
                        showChevron = false,
                    )
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.capability_workspace_private),
                        summary = stringResource(R.string.capability_workspace_private_summary),
                    )
                }
            }
        }
        item(key = "actions") {
            val publicSummary = if (publicAccess) {
                stringResource(R.string.capability_workspace_public_granted)
            } else {
                stringResource(R.string.capability_workspace_public_summary)
            }
            if (uiStyle == AppearanceUiStyle.COUI) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_import),
                        enabled = !busy,
                        trailing = if (busy) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_public),
                        summary = publicSummary,
                        onClick = onOpenPublicAccess,
                    )
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_import),
                        enabled = !busy,
                        endActions = {
                            if (busy) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        },
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_public),
                        summary = publicSummary,
                        onClick = onOpenPublicAccess,
                    )
                }
            }
        }
        message?.let { current ->
            item(key = "message") {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    val dialogColors = CouiDialogDefaults.themeColors()
                    Group(modifier = Modifier.animateItem()) {
                        CouiPreference(
                            title = current.text,
                            titleColor = if (current.isError) dialogColors.destructive else Color.Unspecified,
                            showChevron = false,
                            trailing = {
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
                } else {
                    Card(
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
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
        }
        item(key = "path") {
            val sectionText = if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiSectionTitle(text = sectionText)
            } else {
                SmallTitle(sectionText)
            }
        }
        val currentEntries = entries
        val hasParent = path.isNotBlank()
        if (hasParent) {
            item(key = "parent") {
                EtaPreferenceGroupItem(
                    isFirst = true,
                    isLast = currentEntries.isNullOrEmpty(),
                    hasLeadingIcon = true,
                ) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        val colors = CouiPageDefaults.themeColors()
                        CouiPreference(
                            title = stringResource(R.string.capability_workspace_parent),
                            icon = { CouiSettingsIcon(Icons.Rounded.FolderOpen, colors.accent) },
                            onClick = { path = path.substringBeforeLast('/', "") },
                        )
                    } else {
                        ArrowPreference(
                            title = stringResource(R.string.capability_workspace_parent),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            },
                            onClick = { path = path.substringBeforeLast('/', "") },
                        )
                    }
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
            else -> itemsIndexed(currentEntries, key = { _, entry -> entry.path }) { index, entry ->
                val summary = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                else stringResource(
                    R.string.capability_workspace_file_export,
                    Formatter.formatShortFileSize(context, entry.size),
                )
                val icon = if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile
                val onEntryClick: () -> Unit = {
                    if (entry.directory) {
                        path = entry.path
                    } else {
                        pendingExport = entry.path
                        exportLauncher.launch(entry.name)
                    }
                }
                EtaPreferenceGroupItem(
                    isFirst = index == 0 && !hasParent,
                    isLast = index == currentEntries.lastIndex,
                    hasLeadingIcon = true,
                ) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        val colors = CouiPageDefaults.themeColors()
                        CouiPreference(
                            title = entry.name,
                            summary = summary,
                            icon = {
                                CouiSettingsIcon(
                                    icon = icon,
                                    tint = if (entry.directory) colors.accent else colors.summary,
                                )
                            },
                            enabled = !busy,
                            onClick = onEntryClick,
                        )
                    } else {
                        ArrowPreference(
                            title = entry.name,
                            summary = summary,
                            startAction = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                    tint = if (entry.directory) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            enabled = !busy,
                            onClick = onEntryClick,
                        )
                    }
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = stringResource(R.string.capability_workspace),
                onBack = onBack,
                content = listContent,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.capability_workspace),
                onBack = onBack,
                content = listContent,
            )
        }
    }
}

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
