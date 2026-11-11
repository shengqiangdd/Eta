package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentToolDisableFilter
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import androidx.core.content.edit
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.projectToolGroups
import top.yukonga.miuix.kmp.basic.TabRow

private object ToolsMetrics {
    val GridHorizontalPadding = 16.dp
    val GridGap = 12.dp
}

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    val context = LocalContext.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    // 逐项禁用：读自本地 Agent 配置（逗号分隔的真实工具名），切换后立即写回并刷新本页。
    // 用 remember 而非 rememberSaveable：真实来源是 prefs，进程重建时重读即可。
    val prefs = remember { Prefs.localAgentPreferences(context) }
    var disabledTools by remember {
        mutableStateOf(AgentToolDisableFilter.parse(prefs.getString(Prefs.Keys.AGENT_DISABLED_TOOLS, null)))
    }
    val toggleDisabled: (String) -> Unit = remember(prefs, disabledTools) { { realName ->
        val next = if (realName in disabledTools) disabledTools - realName else disabledTools + realName
        // 空集合也照写：空串等价于「从未禁用过任何工具」。
        prefs.edit { putString(Prefs.Keys.AGENT_DISABLED_TOOLS, next.sorted().joinToString(",")) }
        disabledTools = next
    } }
    // 跨运行的使用汇总：数据来自运行追踪（只读最近 20 次），没有记录时卡片不出现。
    val recentStats = rememberRecentRunStats()
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            TabRow(
                tabs = listOf(stringResource(R.string.capability_current_device), stringResource(R.string.capability_all)),
                selectedTabIndex = if (showAll) 1 else 0,
                onTabSelected = { showAll = it == 1 },
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp),
            )
        }
        item(key = "capability-discovery") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.capability_enhancements),
                    summary = stringResource(R.string.capability_enhancements_summary),
                    onClick = { onAction(AgentToolsAction.OpenEnhancements) },
                )
            }
        }
        val stats = recentStats
        if (!showAll && stats != null && (stats.tools.hasData || stats.retries.hasData)) {
            item(key = "tool-usage") {
                ToolUsageSummaryCard(
                    stats = stats.tools,
                    retryStats = stats.retries,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }
        }
        groups.forEach { group ->
            item(key = "${group.id}-title") {
                EtaPreferenceGroupTitle(group.title)
            }
            items(
                items = group.tools.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                ToolGridRow(
                    tools = row,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                    disabledTools = disabledTools,
                    onToggleDisabled = toggleDisabled,
                )
            }
        }
    }
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    disabledTools: Set<String>,
    onToggleDisabled: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToolsMetrics.GridHorizontalPadding)
            .padding(bottom = ToolsMetrics.GridGap),
    ) {
        val useSingleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        if (useSingleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap)) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        disabled = actualToolName(tool.id) in disabledTools,
                        onToggleDisabled = { onToggleDisabled(actualToolName(tool.id)) },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
            ) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        disabled = actualToolName(tool.id) in disabledTools,
                        onToggleDisabled = { onToggleDisabled(actualToolName(tool.id)) },
                    )
                }
                if (tools.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
