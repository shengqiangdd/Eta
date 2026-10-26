package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.trace.AgentNetworkRetryStats
import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.agent.trace.AgentToolUsageStats
import io.github.mangi.eta.ui.components.EtaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具页顶部的「最近使用」汇总：跨运行聚合 [AgentRunTraceStore] 里的工具记录。
 *
 * 只读最近 20 次运行：读 JSONL 是 IO，放在 Dispatchers.IO 上一次完成，
 * 不轮询、不写回。数据不足（没有运行记录）时整张卡片不显示。
 */
internal data class RecentRunStats(
    val tools: AgentToolUsageStats.Stats,
    val retries: AgentNetworkRetryStats.Stats,
)

@Composable
internal fun rememberRecentRunStats(): RecentRunStats? {
    val stats by produceState<RecentRunStats?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val traces = AgentRunTraceStore.list(limit = 20)
                .mapNotNull { run -> AgentRunTraceStore.read(run.runId) }
            RecentRunStats(
                tools = AgentToolUsageStats.aggregate(traces),
                retries = AgentNetworkRetryStats.aggregate(traces),
            )
        }
    }
    return stats
}

@Composable
internal fun ToolUsageSummaryCard(
    stats: AgentToolUsageStats.Stats,
    retryStats: AgentNetworkRetryStats.Stats? = null,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    EtaCard(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tool_usage_title),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.tool_usage_runs, stats.runs, stats.runs),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append(resources.getQuantityString(R.plurals.tool_usage_calls_total, stats.calls, stats.calls))
                if (stats.failures > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.tool_usage_failures,
                            stats.failures,
                            stats.failures,
                            percent(stats.failureRate),
                        ),
                    )
                }
                append(resources.getString(R.string.tool_usage_average, formatMs(stats.avgMs)))
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        retryStats?.takeIf { it.hasData }?.let { retries ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(resources.getString(R.string.tool_usage_retries, retries.retries))
                    append(resources.getString(R.string.tool_usage_retry_wait, formatMs(retries.avgDelayMs)))
                    if (retries.gatewayRetries > 0) {
                        append(
                            resources.getQuantityString(
                                R.plurals.tool_usage_gateway,
                                retries.gatewayRetries,
                                retries.gatewayRetries,
                            ),
                        )
                    }
                    val decided = retries.recoveredRuns + retries.failedRetryRuns
                    if (decided > 0) {
                        append(
                            resources.getString(
                                R.string.tool_usage_recovered,
                                retries.recoveredRuns,
                                decided,
                                percent(retries.recoveryRate),
                            ),
                        )
                    }
                },
                style = MiuixTheme.textStyles.footnote1,
                color = if (retries.failedRetryRuns > 0) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        stats.tools.take(5).forEach { tool ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = tool.name,
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = buildString {
                        append(resources.getQuantityString(R.plurals.tool_usage_tool_calls, tool.calls, tool.calls))
                        if (tool.failures > 0) {
                            append(
                                resources.getQuantityString(
                                    R.plurals.tool_usage_tool_failures,
                                    tool.failures,
                                    tool.failures,
                                ),
                            )
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (tool.failures > 0) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Text(
                    text = formatMs(tool.avgMs),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun percent(rate: Float): String = "${(rate * 100).toInt()}%"

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}
