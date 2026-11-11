package io.github.mangi.eta.ui.components

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.WorkspaceDirectoryBrowser
import io.github.mangi.eta.ui.app.WorkspaceDirectoryEntry
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

/**
 * 应用内的路径选择器（目录 / 文件共用一套浏览逻辑）。
 *
 * 存在的理由见 [WorkspaceDirectoryBrowser] 的注释：系统 `OPEN_DOCUMENT_TREE` 与
 * `OpenMultipleDocuments` 在本机的虚拟屏上根本不可用（任务被搬回主屏，或窗口建到 display 0），
 * 所以选目录、选导入文件、选导出目录都必须自带。
 *
 * @param pickFiles `true` 时列出文件，点文件即回调（导入）；`false` 时只列目录，
 *   点「使用此文件夹」回调当前目录（选工作区 / 选导出目录）。
 * @param multipleFiles 仅在 [pickFiles] 为 `true` 时生效：点文件改为勾选，由「添加 N 项」
 *   一次性回调 [onPickMany]。聊天附件要一次选多张图 / 多个文件才需要它。
 */
@Composable
internal fun WorkspacePathPicker(
    initialPath: String,
    pickFiles: Boolean,
    title: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    multipleFiles: Boolean = false,
    onPickMany: (List<String>) -> Unit = {},
) {
    var current by rememberSaveable { mutableStateOf(initialPath) }
    val multi = pickFiles && multipleFiles
    // 勾选只在当前目录内有效：切目录还留着上一目录的路径，用户会看不到自己选了什么。
    var selected by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(current) { selected = emptyList() }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<WorkspaceDirectoryEntry>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    val browser = remember { WorkspaceDirectoryBrowser() }
    // 常用位置：没有「所有文件访问」也没有 Root 时，初始落点会退到 App 私有目录，
    // 而那棵树往上都列不出来 —— 没有快捷入口就等于「选不了安卓路径」。
    val context = LocalContext.current
    val sharedRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    val privateRoot = remember { context.filesDir.absolutePath }
    // 上一级必须在 content lambda（LazyListScope，非 @Composable）之外算好，
    // 否则 remember 会报 COMPOSABLE_INVOCATION。
    val parent = remember(current) { File(current).parent }

    BackHandler(onBack = onBack)
    LaunchedEffect(current, showHidden) {
        entries = null
        unavailable = false
        try {
            entries = browser.list(current, showHidden = showHidden, includeFiles = pickFiles)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            unavailable = true
        }
    }

    MiuixScaffoldPage(title = title, onBack = onBack) {
        item(key = "picker-current") {
            EtaPreference(
                title = stringResource(R.string.capability_workspace_pick_current),
                summary = current,
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
        }
        if (!pickFiles) {
            item(key = "picker-quick") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
                ) {
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_quick_shared),
                        summary = sharedRoot,
                        enabled = sharedRoot != current,
                        startAction = {
                            EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = { current = sharedRoot },
                    )
                    EtaPreferenceDivider(hasLeading = true)
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_quick_private),
                        summary = privateRoot,
                        enabled = privateRoot != current,
                        startAction = {
                            EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = { current = privateRoot },
                    )
                }
            }
        }
        if (!pickFiles) {
            item(key = "picker-confirm") {
                EtaTextButton(
                    text = stringResource(R.string.capability_workspace_pick_confirm),
                    // 目录列不出来时这个按钮点了也只会失败：导出会写到不存在的路径、
                    // 设为工作区会被校验拒掉。直接禁用，比让用户点完再看「操作失败」诚实。
                    enabled = !unavailable,
                    onClick = { onPick(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing),
                )
            }
        }
        if (multi) {
            item(key = "picker-multi-confirm") {
                EtaTextButton(
                    text = stringResource(R.string.capability_workspace_pick_many, selected.size),
                    enabled = selected.isNotEmpty(),
                    onClick = { onPickMany(selected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing),
                )
            }
        }
        if (unavailable) {
            item(key = "picker-unavailable") {
                EtaPreference(
                    title = stringResource(R.string.capability_workspace_pick_unavailable),
                    summary = stringResource(R.string.capability_workspace_pick_unavailable_summary),
                    modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
                )
            }
        }
        item(key = "picker-hidden") {
            EtaSwitchPreference(
                title = stringResource(R.string.capability_workspace_show_hidden),
                checked = showHidden,
                onCheckedChange = { showHidden = it },
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
        }
        if (parent != null && parent != current) {
            item(key = "picker-parent") {
                EtaPreferenceGroupItem(isFirst = true, isLast = false, hasLeadingIcon = true) {
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_workspace_parent),
                        startAction = {
                            EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = { current = parent },
                    )
                }
            }
        }
        val list = entries
        when {
            list == null -> item(key = "picker-loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
            else -> itemsIndexed(list, key = { _, entry -> entry.path }) { index, entry ->
                EtaPreferenceGroupItem(
                    isFirst = index == 0 && parent == null,
                    isLast = index == list.lastIndex,
                    hasLeadingIcon = true,
                ) {
                    EtaArrowPreference(
                        title = entry.name,
                        summary = if (entry.directory) {
                            stringResource(R.string.capability_workspace_directory)
                        } else if (multi && entry.path in selected) {
                            "✓ " + stringResource(R.string.capability_workspace_file)
                        } else {
                            stringResource(R.string.capability_workspace_file)
                        },
                        startAction = {
                            EtaPreferenceIcon(
                                icon = if (entry.directory) Icons.Rounded.Folder
                                else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = {
                            if (entry.directory) {
                                current = entry.path
                            } else if (multi) {
                                selected = if (entry.path in selected) {
                                    selected - entry.path
                                } else {
                                    selected + entry.path
                                }
                            } else {
                                onPick(entry.path)
                            }
                        },
                    )
                }
            }
        }
    }
}
