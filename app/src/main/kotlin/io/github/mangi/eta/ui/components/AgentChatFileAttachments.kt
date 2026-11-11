package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.app.WorkspacePickerHistory
import io.github.mangi.eta.ui.app.importHistoryDirectory
import io.github.mangi.eta.ui.app.pickerStartPath
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 40.dp
internal val ChatInputActionIconSize = 24.dp

/** 附件入口的三种选择目标，全部走应用内路径选择器。 */
private enum class AttachmentPickerMode { IMAGE, FILE, FOLDER }

/** 记住上次附件所在目录：图片 / 文件记父目录，文件夹记它本身。 */
private fun rememberAttachmentDirectory(
    context: Context,
    pickedPath: String,
    pickedDirectory: Boolean,
) {
    WorkspacePickerHistory.remember(
        context,
        WorkspacePickerHistory.Kind.ATTACHMENT,
        if (pickedDirectory) pickedPath else importHistoryDirectory(pickedPath),
    )
}

@Composable
internal fun AgentAttachmentPickerButton(
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPopup by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    // 系统照片 / 文件 / 文件夹选择器都是 Activity：在本机虚拟屏上会把整个任务搬到主屏
    // （实测），于是附件入口统一改成应用内路径选择器，只读路径，不碰 SAF。
    var pickerMode by remember { mutableStateOf<AttachmentPickerMode?>(null) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
                modifier = Modifier.size(ChatInputActionIconSize),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        WindowListPopup(
            show = showPopup && popupAnchorTopPx > 0,
            popupPositionProvider = remember(popupAnchorTopPx) {
                InputPopupPositionProvider(popupAnchorTopPx)
            },
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            val options = listOf(
                stringResource(R.string.attachment_image),
                stringResource(R.string.attachment_file),
                stringResource(R.string.attachment_folder),
                stringResource(R.string.attachment_enter_path),
            )
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option,
                        optionSize = options.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            dismiss?.invoke()
                            when (index) {
                                0 -> pickerMode = AttachmentPickerMode.IMAGE
                                1 -> pickerMode = AttachmentPickerMode.FILE
                                2 -> pickerMode = AttachmentPickerMode.FOLDER
                                3 -> {
                                    pathInput = ""
                                    showPathDialog = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    val mode = pickerMode
    if (mode != null) {
        Dialog(
            onDismissRequest = { pickerMode = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                WorkspacePathPicker(
                    initialPath = WorkspacePickerHistory
                        .get(context, WorkspacePickerHistory.Kind.ATTACHMENT)
                        ?: pickerStartPath(context),
                    pickFiles = mode != AttachmentPickerMode.FOLDER,
                    title = stringResource(
                        when (mode) {
                            AttachmentPickerMode.IMAGE -> R.string.attachment_image
                            AttachmentPickerMode.FILE -> R.string.attachment_file
                            AttachmentPickerMode.FOLDER -> R.string.attachment_folder
                        }
                    ),
                    multipleFiles = mode != AttachmentPickerMode.FOLDER,
                    onBack = { pickerMode = null },
                    onPick = { path ->
                        pickerMode = null
                        rememberAttachmentDirectory(
                            context = context,
                            pickedPath = path,
                            pickedDirectory = mode == AttachmentPickerMode.FOLDER,
                        )
                        when (mode) {
                            AttachmentPickerMode.IMAGE -> onAttachImage(path)
                            AttachmentPickerMode.FILE -> onAttachFiles(listOf(path))
                            AttachmentPickerMode.FOLDER -> onAttachFolder(path)
                        }
                    },
                    onPickMany = { paths ->
                        pickerMode = null
                        paths.firstOrNull()?.let { path ->
                            rememberAttachmentDirectory(
                                context = context,
                                pickedPath = path,
                                pickedDirectory = false,
                            )
                        }
                        when (mode) {
                            AttachmentPickerMode.IMAGE -> paths.forEach(onAttachImage)
                            AttachmentPickerMode.FILE -> onAttachFiles(paths)
                            AttachmentPickerMode.FOLDER -> Unit
                        }
                    },
                )
            }
        }
    }

    WindowDialog(
        show = showPathDialog,
        cornerRadius = DialogDefaults.CornerRadius,
        title = stringResource(R.string.ui_input_file_path_36d474),
        summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        onDismissRequest = { showPathDialog = false },
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onCancel = { showPathDialog = false },
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 12.dp,
                    )
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}
