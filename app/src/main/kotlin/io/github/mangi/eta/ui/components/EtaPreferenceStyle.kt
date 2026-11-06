package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object EtaPreferenceColors {
    val Blue = Color(0xFF0080FF)
    val Green = StatusSuccess
    val Orange = Color(0xFFFF7700)
    val Yellow = StatusWarning
}

internal object EtaPreferenceDefaults {
    val SidePadding = 16.dp
    val GroupSpacing = 16.dp
    val IconSize = 24.dp
    val IconTextGap = 16.dp
    val ContentStart = SidePadding + IconSize + IconTextGap
    fun contentStart(hasLeading: Boolean) = if (hasLeading) ContentStart else SidePadding
    val RowMinHeight = 52.dp
}

@Composable
internal fun EtaPreferenceTheme(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val appearance = LocalAppearanceSettings.current
    val pageColors = if (!appearance.monetEnabled && colors.background.luminance() > 0.5f) {
        colors.copy(background = Color(0xFFF0F1F2), primary = EtaPreferenceColors.Blue)
    } else {
        colors
    }
    MiuixTheme(colors = pageColors, content = content)
}

@Composable
internal fun EtaPreferenceGroup(
    modifier: Modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(bottom = EtaPreferenceDefaults.GroupSpacing),
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaCard(modifier = modifier, insideMargin = insideMargin, colors = colors, content = content)
}

@Composable
internal fun EtaPreferenceGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
    )
}

/**
 * 可折叠的一组设置：标题 + 卡片。
 *
 * 折叠时整组只剩标题一行，展开才有卡片。内容用 [ColumnScope] 而不是任意 lambda：
 * 各组内部都按「一行一个 preference + 分隔线」排列，和 [EtaPreferenceGroup] 一致。
 */
@Composable
internal fun EtaCollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 展开/收起时让高度平滑过渡。这里原来写的是 `AnimatedVisibility(visible = true)`：
    // 恒为真，等于没有任何动画——展开是瞬间蹦出来的，收起也是。
    // 现在按内容真实高度做尺寸动画；标题位置本身仍是两套布局（卡片内/卡片外），
    // 但高度变化不再突兀。
    Column(modifier = Modifier.animateContentSize()) {
        if (expanded) {
            EtaCollapsibleGroupTitle(text = title, expanded = true, onToggle = onToggle)
            EtaPreferenceGroup(content = content)
        } else {
            // 折叠时把标题装进和内容同款的卡片：否则整页只剩一串没有背景的灰字，
            // 和旁边展开的卡片完全不是一个视觉语言。
            EtaPreferenceGroup {
                EtaCollapsibleGroupTitle(
                    text = title,
                    expanded = false,
                    onToggle = onToggle,
                    insideCard = true,
                    icon = icon,
                )
            }
        }
    }
}

/**
 * 可折叠的分组标题。
 *
 * 设置页现在有 60 多项、要滑好几屏，而大多数组一个月改不了一次。
 * 收起来只留标题一行，点标题展开；右侧写条数，避免「收起来就不知道里面有什么」。
 * 箭头用上下而不是左右：这里纵向展开，左右箭头会让人以为是进二级页。
 */
@Composable
internal fun EtaCollapsibleGroupTitle(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    // 放在卡片里时，左右 16dp 由卡片自己让出来，这里再加会双倍缩进。
    insideCard: Boolean = false,
    // 折叠时这张卡片只剩标题一行，没有图标会很空；展开时标题在卡片外，
    // 不要图标，避免和卡片里第一个条目的图标打架。
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (insideCard) Modifier
                else Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(top = 2.dp, bottom = 6.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(
                start = 16.dp,
                end = 10.dp,
                top = if (insideCard) 14.dp else 6.dp,
                bottom = if (insideCard) 14.dp else 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (insideCard && icon != null) {
            EtaPreferenceIcon(icon = icon, tint = EtaPreferenceColors.Blue)
            Spacer(modifier = Modifier.width(EtaPreferenceDefaults.IconTextGap))
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.subtitle,
            // 折叠时这行就是卡片里唯一的内容，用摘要色会整页发灰；
            // 展开时它只是卡片上方的分组标签，保持原来的灰。
            color = if (insideCard) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    // 满幅轮廓稍作光学校正，但所有图标都占相同宽度，保证正文和分割线对齐。
    val glyphSize = when (icon) {
        Icons.Rounded.Extension, Icons.Rounded.TheaterComedy, Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> EtaPreferenceDefaults.IconSize
    }
    Box(modifier.size(EtaPreferenceDefaults.IconSize), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface,
        )
    }
}

@Composable
internal fun EtaPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = EtaPreferenceDefaults.contentStart(hasLeading), end = EtaPreferenceDefaults.SidePadding),
        thickness = 0.33.dp,
        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
    )
}
