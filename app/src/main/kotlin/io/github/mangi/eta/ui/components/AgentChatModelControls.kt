package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
<<<<<<< HEAD
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
=======
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
>>>>>>> origin/main
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentSessionUsageFormatter
import io.github.mangi.eta.ui.model.AgentSessionUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    /**
     * 会话累计用量（输入/输出/缓存命中）；null 表示不展示这一段。
     * 与 [usage] 分开：[usage] 描述的是当前窗口占用，这里是跨轮累计。
     */
    sessionUsage: AgentSessionUsageUi? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)
    var showUsagePopover by remember { mutableStateOf(false) }
    val progress = usage.progress
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    // 常态低占用采用中性细环，避免小比例蓝色缺口在输入栏里看起来像卡住的加载圈；高水位再亮起预警色。
    val progressColor = when {
        progress == null -> ink.copy(alpha = if (isDark) 0.36f else 0.32f)
        progress >= 0.92f -> StatusError
        progress >= 0.75f -> StatusWarning
        else -> ink.copy(alpha = if (isDark) 0.48f else 0.42f)
    }
    val trackColor = ink.copy(alpha = if (isDark) 0.12f else 0.08f)
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val detail = when {
        usage.contextWindow == null -> summary
        usage.contextTokens == null -> stringResource(R.string.context_usage_after_response)
        else -> stringResource(R.string.context_usage_previous_response, summary)
    }

    if (showUsagePopover && sessionUsage != null) {
        SessionUsagePopover(
            usage = sessionUsage,
            onDismissRequest = { showUsagePopover = false },
        )
    }

    val usageDescription = stringResource(
        R.string.context_usage_description,
        detail.replace('\n', ' '),
    )
<<<<<<< HEAD
    val tooltipColors = TooltipDefaults.richTooltipColors()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = {
                    Text(
                        text = stringResource(R.string.ui_contextual_usage_d12810),
                        color = tooltipColors.titleContentColor,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                },
                action = when {
                    // 有用量数据时优先给「查看用量」：卡片里的信息量远多于 tooltip 正文。
                    sessionUsage != null && !sessionUsage.isEmpty -> {
                        {
                            TextButton(
                                text = stringResource(R.string.usage_open_action),
                                onClick = {
                                    tooltipState.dismiss()
                                    showUsagePopover = true
                                },
                                minWidth = 0.dp,
                                minHeight = 34.dp,
                                cornerRadius = 17.dp,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    canCompact -> {
                        {
                            TextButton(
                                text = stringResource(R.string.context_compact_action),
                                onClick = {
                                    onCompact()
                                    tooltipState.dismiss()
                                },
                                minWidth = 0.dp,
                                minHeight = 34.dp,
                                cornerRadius = 17.dp,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    else -> null
                },
                colors = tooltipColors,
            ) {
                Text(
                    text = detail,
                    color = tooltipColors.contentColor,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        },
        state = tooltipState,
        focusable = true,
        modifier = modifier,
    ) {
=======
    var showPopover by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
>>>>>>> origin/main
        IconButton(
            onClick = { showPopover = !showPopover },
            minWidth = 34.dp,
            minHeight = 34.dp,
        ) {
            CircularProgressIndicator(
                progress = progress ?: 0f,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = progressColor,
                    disabledForegroundColor = progressColor,
                    backgroundColor = trackColor,
                ),
                strokeWidth = 1.8.dp,
                size = 16.dp,
                modifier = Modifier.semantics {
                    contentDescription = usageDescription
                },
            )
        }
        if (showPopover) {
            ContextUsageStatsPopoverPopup(
                usage = usage,
                summaryText = summary,
                detailText = detail,
                canCompact = canCompact,
                onCompact = onCompact,
                onDismissRequest = { showPopover = false },
            )
        }
    }
}
