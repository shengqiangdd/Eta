package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsBar
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup
import java.io.File

/**
 * 会话侧边栏尺寸标尺。
 *
 * 外层水平边距（12.dp）+ 行内水平边距（16.dp）= 28.dp，与 Miuix 原生 [SmallTitle] 的默认内边距
 * 及 [InputField] 内部搜索图标起点完全对齐，保证搜索框、分组标题与对话标题处于同一条垂直基准线。
 */
private object ConversationPanelMetrics {
    val PaneHorizontalPadding = 12.dp
    val TopInset = 8.dp
    val AfterActionBar = 8.dp
    val BottomInset = 4.dp
    val ActionIconSize = 20.dp
    val FirstSectionTopPadding = 8.dp
    val SectionTopPadding = 16.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 2.dp
    val RowCornerRadius = 14.dp
    val RowHorizontalPadding = 16.dp
    val RowVerticalPadding = 11.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val EmptyVerticalPadding = 28.dp
    val DockVerticalPadding = 4.dp
    val DockEntryCornerRadius = 12.dp
    val DockEntryIconSize = 20.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 抬高一档到 surfaceContainer，保证两侧始终可分。浅色维持 surface，避免改变现有观感。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        return if (CouiTheme.isDark) Color(0xFF18191C) else Color(0xFFF3F4F6)
    }
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) colors.surface else colors.surfaceContainer
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationExportHistory: (ConversationSummaryUi) -> Unit,
    onConversationStop: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val groups = remember(state.conversations) { state.conversations.groupForDrawer() }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        color = conversationPaneContainerColor(),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        // 搜索与工具条占据独立布局空间，列表只在中间视口内滚动和回弹。
        Column(modifier = Modifier.fillMaxSize()) {
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.TopInset))
                    PaneActionBar(query = state.searchQuery, onSearchChange = onSearchChange)
                    if (query.isNotBlank() && (state.isSearchingHistory || state.unsearchedConversationCount > 0)) {
                        Text(
                            text = if (state.isSearchingHistory) {
                                stringResource(R.string.conversation_search_scanning_history)
                            } else {
                                stringResource(
                                    R.string.conversation_search_partial_3d9c41,
                                    state.unsearchedConversationCount,
                                )
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = ConversationPanelMetrics.AfterActionBar),
                        )
                    }
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                overscrollEffect = null,
            ) {
                // 扫描中列表为空时不要先说「没有匹配」：那会把「还没查完」说成「查过了没有」，
                // 与顶部「正在搜索历史消息…」自相矛盾。等这一轮查完再决定空态文案。
                if (state.conversations.isEmpty() && !state.isSearchingHistory) {
                    item {
                        EmptyConversations(
                            isSearching = query.isNotBlank(),
                            historyIncomplete = query.isNotBlank() && state.unsearchedConversationCount > 0,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                } else {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = "section-${group.section}") {
                            ConversationSectionHeader(
                                group = group,
                                isFirstSection = groupIndex == 0,
                            )
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onPin = { onConversationPin(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                                onRename = { onConversationRename(conversation) },
                                onExport = { onConversationExport(conversation) },
                                onExportHistory = { onConversationExportHistory(conversation) },
                                onStop = { onConversationStop(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                            )
                        }
                    }
                }
            }
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = ConversationPanelMetrics.RowHorizontalPadding),
                        thickness = 0.5.dp,
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.DockVerticalPadding))
                    PaneDock(
                        onOpenSettings = onOpenSettings,
                        onOpenModelProviders = onOpenModelProviders,
                        onOpenTools = onOpenTools,
                        onOpenSkills = onOpenSkills,
                        onOpenCharacters = onOpenCharacters,
                        onOpenPermissions = onOpenPermissions,
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.BottomInset))
                }
            }
        }
    }
}

@Composable
private fun PaneFixedRegion(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(conversationPaneContainerColor()),
    ) {
        content()
    }
}

@Composable
private fun PaneActionBar(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiSearchBarBox(
                query = query,
                onQueryChange = onSearchChange,
                placeholder = stringResource(R.string.conversation_search_hint),
                backgroundColor = conversationPaneContainerColor(),
            )
        }
        AppearanceUiStyle.MIUIX -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    modifier = Modifier.weight(1f),
                    insideMargin = DpSize.Zero,
                    expanded = false,
                    onExpandedChange = {},
                    inputField = {
                        InputField(
                            query = query,
                            onQueryChange = onSearchChange,
                            onSearch = onSearchChange,
                            expanded = false,
                            onExpandedChange = {},
                            label = stringResource(R.string.conversation_search_hint),
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        )
                    },
                    content = {},
                )
            }
        }
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    isFirstSection: Boolean,
) {
    SmallTitle(
        text = group.localizedLabel(),
        insideMargin = PaddingValues(
            start = ConversationPanelMetrics.RowHorizontalPadding,
            top = if (isFirstSection) {
                ConversationPanelMetrics.FirstSectionTopPadding
            } else {
                ConversationPanelMetrics.SectionTopPadding
            },
            end = ConversationPanelMetrics.RowHorizontalPadding,
            bottom = ConversationPanelMetrics.SectionBottomPadding,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportHistory: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val anchor = rememberCouiMenuAnchor()
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val couiRowShape = remember { CouiShape(14.dp, 2f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                anchorCenterY = coordinates.localToWindow(Offset.Zero).y + coordinates.size.height / 2f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .couiMenuAnchor(anchor)
                .then(
                    if (isCoui) {
                        Modifier
                            .clip(couiRowShape)
                            .background(
                                if (selected) {
                                    if (couiDark) Color(0xFF26292E) else Color.White
                                } else {
                                    Color.Transparent
                                },
                                couiRowShape,
                            )
                    } else {
                        Modifier.squircleSurface(
                            color = if (selected) {
                                MiuixTheme.colorScheme.tertiaryContainer
                            } else {
                                Color.Transparent
                            },
                            cornerRadius = ConversationPanelMetrics.RowCornerRadius,
                        )
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor.open { showActionMenu = true }
                    },
                )
                .padding(
                    horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                    vertical = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            conversation.characterName?.let { name ->
                CharacterAvatar(
                    name = name,
                    avatarPath = conversation.characterAvatarPath,
                    size = 32.dp,
                    shape = CircleShape,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
            val title = conversation.title.ifBlank { conversation.preview }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 分组只到「今天/昨天/周五」，再具体的时刻一直没地方显示：
                // 同一组里有好几个会话时，找「下午那个」只能一个个点开。
                Text(
                    text = conversation.timeLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // 最后一条消息摘要：标题永远是「首条消息」，几分钟前聊到哪只有摘要能说清。
            // 与标题相同（未命名会话用 preview 当标题）或空时不重复占一行。
            conversation.preview.takeIf { it.isNotBlank() && it != title }?.let { preview ->
                Text(
                    text = preview,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 标题/预览都不含关键词、只有正文命中的会话，必须说明「为什么它在这里」。
            if (conversation.contentMatchCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.conversation_search_content_hits,
                        conversation.contentMatchCount,
                        conversation.contentMatchCount,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 「重会话」标记：超过阈值才显示，避免轻会话也堆一行小字。
            if (conversation.messageCount >= HEAVY_CONVERSATION_MESSAGE_COUNT) {
                Text(
                    text = pluralStringResource(
                        R.plurals.conversation_heavy_message_count_5e8f21,
                        conversation.messageCount,
                        conversation.messageCount,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
            conversation.characterName?.takeIf { it != title }?.let { name ->
                Text(name, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            conversation.runStatus?.let { status ->
                val title = conversation.title.ifBlank { conversation.preview }
                Text(
                    text = title,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onTertiaryContainer
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
                conversation.characterName?.takeIf { it != title }?.let { name ->
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                conversation.runStatus?.let { status ->
                    Text(
                        text = status,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (conversation.isActiveRun) {
                if (conversation.isPaused) {
                    // 会话列表里必须能看出「这个 run 被暂停了」：否则用户切走后
                    // 只会看到一个永远不动的运行中圆点，以为卡死了。
                    Text(
                        text = stringResource(R.string.overlay_paused),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(end = 6.dp),
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }

        val pinText = stringResource(
            if (conversation.isPinned) {
                R.string.action_unpin_conversation
            } else {
                R.string.action_pin_conversation
            },
        )
        val renameText = stringResource(R.string.action_rename)
        val exportText = stringResource(R.string.action_export)
        val deleteText = stringResource(R.string.action_delete)
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val destructiveColor = CouiDialogDefaults.themeColors().destructive
                val menuItems = remember(pinText, renameText, exportText, deleteText, destructiveColor) {
                    listOf(
                        CouiMenuItem(value = 0, text = pinText, icon = Icons.Outlined.PushPin),
                        CouiMenuItem(value = 1, text = renameText, icon = Icons.Outlined.Edit),
                        CouiMenuItem(value = 2, text = exportText, icon = Icons.Outlined.Download),
                        CouiMenuItem(
                            value = 3,
                            text = deleteText,
                            icon = Icons.Outlined.Delete,
                            iconTint = destructiveColor,
                            dividerBefore = true,
                        ),
                    )
                }
                Menu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                    items = menuItems,
                    onItemClick = { item ->
                        showActionMenu = false
                        when (item.value) {
                            0 -> onTogglePin()
                            1 -> onRename()
                            2 -> onExport()
                            3 -> onDelete()
                        }
                    },
                    anchor = anchor,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowListPopup(
                    show = showActionMenu,
                    popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                    alignment = PopupPositionProvider.Align.BottomEnd,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    val pinItem = remember(pinText) {
                        DropdownItem(
                            text = pinText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    },
                )
            }
            val historyText = stringResource(R.string.conversation_export_history)
            val historyItem = remember(historyText) {
                DropdownItem(
                    text = historyText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val stopText = stringResource(R.string.action_stop_run)
            val stopItem = remember(stopText) {
                DropdownItem(
                    text = stopText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            // 只有正在跑的会话才多出「停止运行」：其它会话上没有东西可停。
            val menuSize = if (conversation.isActiveRun) 6 else 5
            ListPopupColumn {
                DropdownImpl(
                    item = pinItem,
                    optionSize = menuSize,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onPin()
                    },
                )
                DropdownImpl(
                    item = renameItem,
                    optionSize = menuSize,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = exportItem,
                    optionSize = menuSize,
                    isSelected = false,
                    index = 2,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onExport()
                    },
                )
                DropdownImpl(
                    item = historyItem,
                    optionSize = menuSize,
                    isSelected = false,
                    index = 3,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onExportHistory()
                    },
                )
                if (conversation.isActiveRun) {
                    DropdownImpl(
                        item = stopItem,
                        optionSize = menuSize,
                        isSelected = false,
                        index = 4,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onStop()
                        },
                    )
                }
                DropdownImpl(
                    item = deleteItem,
                    optionSize = menuSize,
                    isSelected = false,
                    index = if (conversation.isActiveRun) 5 else 4,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onDelete()
                    },
                )
                    }
                    val renameItem = remember(renameText) {
                        DropdownItem(
                            text = renameText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val exportItem = remember(exportText) {
                        DropdownItem(
                            text = exportText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val deleteItem = remember(deleteText) {
                        DropdownItem(
                            text = deleteText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                    val deleteColors = DropdownDefaults.dropdownColors(
                        contentColor = MiuixTheme.colorScheme.error,
                        selectedContentColor = MiuixTheme.colorScheme.error,
                        selectedIndicatorColor = MiuixTheme.colorScheme.error,
                    )
                    ListPopupColumn {
                        DropdownImpl(
                            item = pinItem,
                            optionSize = 4,
                            isSelected = false,
                            index = 0,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onTogglePin()
                            },
                        )
                        DropdownImpl(
                            item = renameItem,
                            optionSize = 4,
                            isSelected = false,
                            index = 1,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        DropdownImpl(
                            item = exportItem,
                            optionSize = 4,
                            isSelected = false,
                            index = 2,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onExport()
                            },
                        )
                        DropdownImpl(
                            item = deleteItem,
                            optionSize = 4,
                            isSelected = false,
                            index = 3,
                            dropdownColors = deleteColors,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversations(
    isSearching: Boolean,
    historyIncomplete: Boolean = false,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    // 库是空的并不等于「从来没有备份」：把现成的自动备份摆出来，比只说「还没有对话」有用。
    val backups by produceState(initialValue = emptyList<File>(), context) {
        value = withContext(Dispatchers.IO) {
            AutoBackupScheduler.autoBackupsFor(context.packageName)
        }
    }
    Column(
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(
            horizontal = ConversationPanelMetrics.RowHorizontalPadding,
            vertical = ConversationPanelMetrics.EmptyVerticalPadding,
        ),
    ) {
        Text(
            text = stringResource(
                when {
                    // 有会话没载入时，「没有匹配」只能代表已载入的范围，不能说得像全库都没有。
                    historyIncomplete -> R.string.conversation_no_results_partial
                    isSearching -> R.string.conversation_no_results
                    else -> R.string.conversation_empty
                },
            ),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
        )
        if (!isSearching && !historyIncomplete && backups.isNotEmpty()) {
            val latest = backups.first()
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.conversation_empty_restore_hint,
                    backups.size,
                    ConversationTimeLabels.label(latest.lastModified()),
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
            Text(
                text = stringResource(R.string.conversation_empty_restore_action),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PaneDock(
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DockEntry(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.route_settings),
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Cloud,
            label = stringResource(R.string.route_model_providers),
            icon = Icons.Outlined.Cloud,
            label = "模型",
            onClick = onOpenModelProviders,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Dashboard,
            label = stringResource(R.string.route_tools),
            icon = Icons.Outlined.Widgets,
            label = "工具",
            onClick = onOpenTools,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Outlined.AutoStories,
            label = "Skills",
            onClick = onOpenSkills,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Lock,
            label = stringResource(R.string.route_permissions),
            icon = Icons.Outlined.Shield,
            label = "权限",
            onClick = onOpenPermissions,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.SportsBar,
            label = stringResource(R.string.route_characters),
            icon = Icons.Outlined.SportsBar,
            label = "角色",
            onClick = onOpenCharacters,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DockEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .squircleSurface(
                color = Color.Transparent,
                cornerRadius = ConversationPanelMetrics.DockEntryCornerRadius,
            )
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(ConversationPanelMetrics.DockEntryIconSize),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}

/** 会话列表里多显示一行消息数的最小条数；低于它的会话看不出「重」，加了只是噪音。 */
private const val HEAVY_CONVERSATION_MESSAGE_COUNT = 2_000
