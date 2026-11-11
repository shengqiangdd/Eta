package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentSessionUsageUi
import io.github.mangi.eta.ui.model.SpeedSource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话用量的锚定浮层卡片。
 *
 * 视觉结构取自上游 `671c5398` 的 `ChatStatsPopover`（squircle 卡面 + 阴影 + 分组标题 +
 * 明细行 + 可制表数字），但去掉了它的 COUI 样式分支 —— 本仓库没有 COUI 依赖，
 * 那段（18/583 行）在这里是死代码。
 *
 * 与上游的一处实质差别：上游卡片展示**单轮性能**（TTFT / decode 速度 / 模型与工具用时），
 * 那需要 `ttftMs`、`decodeMs`、`llmMs` 三个字段，本仓库目前都没采（消息表只有
 * `elapsed_seconds`）。所以这里展示的是**本会话累计用量** —— 输入/输出/缓存命中/轮次，
 * 数据全部来自已有的 `TokenUsageUi`。等性能字段真正落库后再补那一段，
 * 而不是先把卡片做出来却没有数据可填。
 */
@Composable
internal fun SessionUsagePopover(
    usage: AgentSessionUsageUi,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) { SessionUsagePositionProvider(density) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 256.dp, max = 328.dp)
                .dropShadow(
                    shape = RoundedCornerShape(18.dp),
                    shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = 0.12f),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 18.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cornerRadius = 18.dp,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            StatsSectionHeader(
                icon = Icons.Rounded.Upload,
                title = stringResource(R.string.usage_section_input),
                value = usage.promptTokens.takeIf { it > 0 }?.let { formatExactTokenCount(it) + " tok" },
            )
            if (usage.promptTokens > 0) {
                Spacer(Modifier.height(6.dp))
                StatsDetailRow(
                    label = stringResource(R.string.usage_row_cache_hit),
                    value = formatCacheHitPercent(usage.cacheHitRate),
                )
                StatsDetailRow(
                    label = stringResource(R.string.usage_row_uncached),
                    value = usage.uncachedInputTokens
                        ?.let { formatExactTokenCount(it) + " tok" }
                        ?: "—",
                )
            }
            // 只统计了部分消息时必须说清楚。
            //
            // 大会话不会把全部消息放进内存（真机 28 个会话里 21 个超过窗口，最大的
            // 49036 条而窗口只有 500 条）。不标注的话，这里那串「输入 N tok」
            // 会被当成整个会话的成本 —— 实测偏差可达两个数量级。
            if (usage.partialScope) {
                Spacer(Modifier.height(8.dp))
                StatsNoteRow(stringResource(R.string.usage_scope_partial))
            }

            StatsSectionDivider()

            StatsSectionHeader(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.usage_section_output),
                value = usage.totalOutputTokens?.let { formatExactTokenCount(it) + " tok" },
            )
            if (usage.reasoningTokens > 0) {
                Spacer(Modifier.height(6.dp))
                StatsDetailRow(
                    label = stringResource(R.string.usage_row_reasoning),
                    value = formatExactTokenCount(usage.reasoningTokens) + " tok",
                )
            }

            // 速度段：只在采集到首字时才出现。
            // 用 lastDecodeMs 除 lastOutputTokens（含推理）；用总耗时会把工具和排队算进去。
            val decodeSpeed = decodeSpeedValue(usage)
            val speedRecycled = usage.speedSource == SpeedSource.Recycled
            if (decodeSpeed != null || usage.lastFirstTokenMs != null || speedRecycled) {
                val unit = stringResource(R.string.usage_unit_tok_per_s)
                StatsSectionDivider()
                StatsSectionHeader(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.usage_section_speed),
                    value = decodeSpeed?.let { "$it $unit" },
                )
                usage.lastFirstTokenMs?.let { ms ->
                    Spacer(Modifier.height(6.dp))
                    StatsDetailRow(
                        label = stringResource(R.string.usage_row_first_token),
                        value = formatDurationMs(ms),
                    )
                }
                if (speedRecycled) {
                    // 只有「确实跑过、但记录被清理」才解释。
                    // 「没跑过」不需要说明（空会话显示一段提示是噪音），
                    // 「存储不可用」属于异常，也不该在这里教育用户。
                    Spacer(Modifier.height(8.dp))
                    StatsNoteRow(stringResource(R.string.usage_speed_unavailable_recycled))
                }
            }

            if (usage.requestCount > 0 || usage.contextTokens != null) {
                StatsSectionDivider()
                if (usage.requestCount > 0) {
                    StatsDetailRow(
                        label = stringResource(R.string.usage_row_turns),
                        value = usage.requestCount.toString(),
                    )
                }
                usage.contextTokens?.let { context ->
                    val window = usage.contextWindow
                    StatsDetailRow(
                        label = stringResource(R.string.usage_row_context),
                        value = if (window != null && window > 0) {
                            "${formatExactTokenCount(context)} / ${formatExactTokenCount(window)}"
                        } else {
                            formatExactTokenCount(context) + " tok"
                        },
                    )
                }
            }
        }
    }
}

/** 把卡片贴在胶囊上方；空间不足时翻到下方。 */
private class SessionUsagePositionProvider(private val density: Density) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = with(density) { 8.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= margin) above else anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}

@Composable
private fun StatsSectionHeader(
    icon: ImageVector,
    title: String,
    value: String?,
) {
    val primaryColor = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(14.dp).height(14.dp),
                tint = primaryColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = primaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = primaryColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatsSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/** 一行浅色说明文字：解释「为什么没有数据」，不是数据本身。 */
@Composable
private fun StatsNoteRow(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatsDetailRow(label: String, value: String) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** 精确分组（1234 → 1,234）。卡片里用精确值，胶囊上用缩写。 */
internal fun formatExactTokenCount(value: Int): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(value)

/**
 * 生成速度文案（tok/s）。
 *
 * 只用**解码耗时**除输出 token。若用整轮总耗时，一次长工具调用就会把
 * 速度拉到几十 tok/s 以下，看起来像模型很慢，实际只是工具耗时。
 * 任一数据缺失返回 null（卡片不显示这一行）。
 */
internal fun decodeSpeedValue(usage: AgentSessionUsageUi): String? {
    val decodeMs = usage.lastDecodeMs?.takeIf { it > 0 } ?: return null
    val tokens = usage.lastOutputTokens?.takeIf { it > 0 } ?: return null
    val tps = tokens * 1000.0 / decodeMs
    // 与胶囊一致：极低速度显示 <0.1，不显示 0.0（看起来像没输出）。
    return if (tps < 0.05) "<0.1" else String.format(java.util.Locale.US, "%.1f", tps)
}

/** 耗时：不到 1s 用毫秒，否则用秒并保留一位小数。 */
internal fun formatDurationMs(ms: Int): String {
    if (ms < 1_000) return "${ms}ms"
    val seconds = ms / 1000.0
    return if (seconds < 10) {
        String.format(java.util.Locale.US, "%.1fs", seconds)
    } else {
        "${Math.round(seconds)}s"
    }
}

/** 命中率；与胶囊一致：数据不足时显示 `—`，不显示 0%。 */
internal fun formatCacheHitPercent(rate: Float?): String =
    rate?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: "—"
