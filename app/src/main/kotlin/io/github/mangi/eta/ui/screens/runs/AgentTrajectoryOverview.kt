package io.github.mangi.eta.ui.screens.runs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.agent.trace.AgentRunTrajectory
import io.github.mangi.eta.ui.components.EtaCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轨迹时间总览：把整次运行压成一条横向时间条。
 *
 * 参考 dsh 的 trajectory timeline：模型请求 / 工具 / 重试 / 压缩按真实起止时间排列，
 * 宽度就是耗时占比；点按某一轮会筛选下方明细（再点一次清除）。
 *
 * 这里刻意不做缩放与拖动区间——手机上横向空间小，点按切轮已能回答
 * 「哪一轮最慢、慢在请求还是工具」，比精确框选更实用。
 */
@Composable
internal fun TrajectoryOverviewCard(
    trajectory: AgentRunTrajectory.Trajectory,
    selectedTurn: Int?,
    onSelectTurn: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!trajectory.hasTurns) return
    val turns = trajectory.turns
    EtaCard(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.trajectory_title),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = selectedTurn?.let { stringResource(R.string.trajectory_selected_hint, it + 1) }
                    ?: stringResource(R.string.trajectory_clear_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(trajectory, turns.size) {
                    detectTapGestures { position ->
                        val ratio = (position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val ms = (ratio * trajectory.totalMs).toLong()
                        val hit = turns.firstOrNull { ms >= it.startMs && ms < it.endMs }
                            ?: turns.lastOrNull()
                        if (hit != null) {
                            onSelectTurn(if (selectedTurn == hit.index) null else hit.index)
                        }
                    }
                },
        ) {
            drawRect(color = Color(0xFF10151F))
            // 轮次交替底色：即使某个轮里全是瞬时记录，也能看出边界。
            turns.forEach { turn ->
                if (turn.index % 2 == 1) {
                    val x = turn.startMs.toFloat() / trajectory.totalMs * size.width
                    val width = turn.durationMs.toFloat() / trajectory.totalMs * size.width
                    drawRect(
                        color = Color.White.copy(alpha = 0.05f),
                        topLeft = Offset(x, 0f),
                        size = Size(maxOf(width, 1f), size.height),
                    )
                }
            }
            trajectory.segments.forEach { segment ->
                val alpha = if (selectedTurn == null || segment.turnIndex == selectedTurn) 1f else 0.22f
                val x = segment.startMs.toFloat() / trajectory.totalMs * size.width
                val width = maxOf(segment.durationMs.toFloat() / trajectory.totalMs * size.width, 2f)
                drawRect(
                    color = segmentColor(segment.kind, segment.failed).copy(alpha = alpha),
                    topLeft = Offset(x, size.height * 0.24f),
                    size = Size(width, size.height * 0.52f),
                )
            }
            turns.drop(1).forEach { turn ->
                val x = turn.startMs.toFloat() / trajectory.totalMs * size.width
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(stringResource(R.string.trajectory_legend_request), Color(0xFF4C8DFF))
            LegendDot(stringResource(R.string.trajectory_legend_tool), Color(0xFF35B37E))
            LegendDot(stringResource(R.string.trajectory_legend_retry), Color(0xFFF5A524))
            LegendDot(stringResource(R.string.trajectory_legend_failure), Color(0xFFE5484D))
            LegendDot(stringResource(R.string.trajectory_legend_compaction), Color(0xFF9B6BFF))
        }
        val slowest = trajectory.segments.maxByOrNull { it.durationMs }
        if (slowest != null && slowest.durationMs > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.trajectory_slowest,
                    slowest.turnIndex + 1,
                    slowest.label,
                    formatSpan(slowest.durationMs),
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(color = color)
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun formatSpan(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

private fun segmentColor(kind: String, failed: Boolean): Color = when {
    failed -> Color(0xFFE5484D)
    kind == AgentRunTraceStore.KIND_TOOL -> Color(0xFF35B37E)
    kind == AgentRunTraceStore.KIND_REQUEST -> Color(0xFF4C8DFF)
    kind == AgentRunTraceStore.KIND_RESPONSE -> Color(0xFF2FB3A3)
    kind == AgentRunTraceStore.KIND_RETRY -> Color(0xFFF5A524)
    kind == AgentRunTraceStore.KIND_COMPACT -> Color(0xFF9B6BFF)
    kind == AgentRunTraceStore.KIND_THINKING -> Color(0xFF8E8E93)
    kind == AgentRunTraceStore.KIND_TEXT -> Color(0xFF62C3FF)
    kind == AgentRunTraceStore.KIND_USAGE -> Color(0xFFB9BDC6)
    kind == AgentRunTraceStore.KIND_DONE -> Color(0xFF35B37E)
    else -> Color(0xFF9AA0A6)
}
