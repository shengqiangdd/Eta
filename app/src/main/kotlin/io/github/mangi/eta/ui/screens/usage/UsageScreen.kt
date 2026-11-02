package io.github.mangi.eta.ui.screens.usage

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.usage.AgentUsageLedger
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型用量面板。
 *
 * 数据来自本地账本 [AgentUsageLedger]：只在模型返回 usage 时追加一行，
 * 因此这里展示的是"实际用掉的 token"，不是估算。
 */
@Composable
internal fun UsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var days by rememberSaveable { mutableIntStateOf(7) }
    val summary by produceState<AgentUsageLedger.Summary?>(initialValue = null, days) {
        value = withContext(Dispatchers.IO) { AgentUsageLedger.get().summarize(days) }
    }
    // 「最近调用」与天数选择无关：它回答的是“刚才那笔花了多少”，固定看最近的记录。
    val recent by produceState<List<AgentUsageLedger.Entry>>(initialValue = emptyList(), Unit) {
        value = withContext(Dispatchers.IO) { AgentUsageLedger.get().recent() }
    }
    MiuixScaffoldPage(title = stringResource(R.string.settings_usage_title), onBack = onBack) {
        item("usage_range") {
            EtaCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(stringResource(R.string.usage_range_title), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 30, 90).forEach { option ->
                        RangeChip(
                            label = if (option == 1) {
                                stringResource(R.string.usage_range_today)
                            } else {
                                pluralStringResource(R.plurals.usage_range_days, option, option)
                            },
                            selected = days == option,
                            modifier = Modifier.weight(1f),
                            onClick = { days = option },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.usage_range_note),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                val clipboard = LocalClipboardManager.current
                val summaryForCopy = summary
                if (summaryForCopy != null && summaryForCopy.totals.calls > 0) {
                    Spacer(Modifier.height(6.dp))
                    io.github.mangi.eta.ui.components.EtaTextButton(
                        text = stringResource(R.string.usage_copy_summary),
                        onClick = {
                            clipboard.setText(AnnotatedString(usageSummaryText(summaryForCopy, days, context)))
                        },
                    )
                }
            }
        }
        val current = summary
        if (current == null) {
            item("usage_loading") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_loading))
                }
            }
            return@MiuixScaffoldPage
        }
        item("usage_totals") {
            EtaCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(stringResource(R.string.usage_totals_title), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(stringResource(R.string.usage_metric_total_tokens), current.totals.totalTokens, Modifier.weight(1f))
                    Metric(stringResource(R.string.usage_metric_calls), current.totals.calls, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(stringResource(R.string.usage_metric_input), current.totals.input, Modifier.weight(1f))
                    Metric(stringResource(R.string.usage_metric_output), current.totals.output, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(stringResource(R.string.usage_metric_cached), current.totals.cached, Modifier.weight(1f))
                    Metric(stringResource(R.string.usage_metric_reasoning), current.totals.reasoning, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(
                        stringResource(R.string.usage_metric_average_per_call),
                        current.averageTokensPerCall.toLong(),
                        Modifier.weight(1f),
                    )
                    Metric(
                        stringResource(R.string.usage_metric_cache_hit_rate),
                        (current.totals.cacheShare * 100).toLong(),
                        Modifier.weight(1f),
                        suffix = "%",
                    )
                }
                if (current.totals.cached > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.usage_cache_note),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (recent.isNotEmpty()) {
            item("usage_recent") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_recent_title), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    recent.forEach { entry ->
                        RecentCallRow(entry)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(R.string.usage_recent_note),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (current.byDay.isNotEmpty()) {
            item("usage_daily") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_daily_title), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    val max = remember(current) {
                        current.byDay.maxOfOrNull { it.totals.totalTokens }?.coerceAtLeast(1) ?: 1
                    }
                    current.byDay.takeLast(if (days <= 7) days else 14).forEach { bucket ->
                        DayRow(bucket, max)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (days > 14) {
                        Text(
                            pluralStringResource(R.plurals.usage_daily_note, days, days),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (current.byPurpose.isNotEmpty()) {
            item("usage_purpose") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_purpose_title), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    current.byPurpose.forEach { bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(purposeLabel(bucket.purpose, context), modifier = Modifier.weight(1f))
                            Text(
                                pluralStringResource(
                                    R.plurals.usage_purpose_calls,
                                    bucket.totals.calls.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                                    format(bucket.totals.calls),
                                    format(bucket.totals.totalTokens),
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        if (current.hourly.isNotEmpty()) {
            item("usage_hourly") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_hourly_title), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    HourStrip(current.hourly)
                }
            }
        }
        if (current.byModel.isNotEmpty()) {
            item("usage_models") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_models_title), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    current.byModel.take(8).forEach { bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(bucket.model)
                                Text(
                                    stringResource(
                                        R.string.usage_model_row,
                                        format(bucket.totals.input),
                                        format(bucket.totals.output),
                                    ),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(format(bucket.totals.totalTokens))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (current.totals.calls == 0L) {
            item("usage_empty") {
                EtaCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(stringResource(R.string.usage_empty))
                }
            }
        }
        item("usage_footer") {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RangeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primaryContainer
                else MiuixTheme.colorScheme.secondaryContainer,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer
            else MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun Metric(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    Column(modifier) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(format(value) + suffix, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RecentCallRow(entry: AgentUsageLedger.Entry) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            recentTimeLabel(entry.ts),
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.width(76.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(Modifier.weight(1f)) {
            Text(purposeLabel(entry.purpose, context))
            Text(
                entry.model,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(
                R.string.usage_recent_tokens,
                format(entry.input),
                format(entry.output),
            ),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private val recentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun recentTimeLabel(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(recentTimeFormatter)

@Composable
private fun DayRow(bucket: AgentUsageLedger.DayBucket, max: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            bucket.day.takeLast(5),
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.width(48.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        ) {
            val fraction = (bucket.totals.totalTokens.toFloat() / max).coerceIn(0f, 1f)
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }
        Text(
            format(bucket.totals.totalTokens),
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.width(64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)


/** 最近 24 小时的紧凑柱条：只标出有调用的整点，避免 24 个空标签占满一行。 */
@Composable
private fun HourStrip(buckets: List<AgentUsageLedger.HourBucket>) {
    val max = remember(buckets) {
        buckets.maxOfOrNull { it.totals.totalTokens }?.coerceAtLeast(1L) ?: 1L
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            val fraction = (bucket.totals.totalTokens.toFloat() / max).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((4 + fraction * 32).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (fraction > 0f) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.usage_hourly_note),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

private fun purposeLabel(purpose: String, context: Context): String = when (purpose) {
    "chat" -> context.getString(R.string.usage_purpose_chat)
    "compact" -> context.getString(R.string.usage_purpose_compact)
    "rewrite" -> context.getString(R.string.usage_purpose_rewrite)
    "unknown" -> context.getString(R.string.usage_purpose_unknown)
    else -> purpose
}

private fun usageSummaryText(
    summary: AgentUsageLedger.Summary,
    days: Int,
    context: Context,
): String = buildString {
    appendLine(context.resources.getQuantityString(R.plurals.usage_summary_title, days, days))
    appendLine(
        context.getString(
            R.string.usage_summary_totals,
            format(summary.totals.totalTokens),
            format(summary.totals.calls),
        ),
    )
    appendLine(
        context.getString(
            R.string.usage_model_row,
            format(summary.totals.input),
            format(summary.totals.output),
        ),
    )
    appendLine(
        context.getString(
            R.string.usage_summary_cache,
            format(summary.totals.cached),
            format(summary.totals.reasoning),
        ),
    )
    appendLine(context.getString(R.string.usage_summary_average, format(summary.averageTokensPerCall.toLong())))
    summary.byModel.take(5).forEach { bucket ->
        appendLine(
            context.getString(
                R.string.usage_summary_model,
                bucket.model,
                format(bucket.totals.totalTokens),
            ),
        )
    }
}
