package io.github.mangi.eta.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalPlatformDensity

/**
 * 宽屏阈值：600dp，与 WindowSizeClass 的 Medium 起点一致。
 *
 * 页面留白和会话侧栏形态共用这一份判据 —— 两处各写一个数，迟早会出现
 * 「内容按宽屏居中、侧栏却还当自己是手机」这种自相矛盾的画面。
 */
internal val WideScreenMinWidth = 600.dp
private val MaxPageContentWidth = 800.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

internal fun isWideScreen(containerWidthPx: Int, density: androidx.compose.ui.unit.Density): Boolean =
    with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/**
 * 列表本身保持全宽，只把内容限制在居中的最大宽度内，避免宽屏两侧形成滚动死区。
 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (isWideScreen) {
            ((maxWidth - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(sidePadding)
    }
}

/**
 * 二级页内容只补水平方向的屏幕缺口与手势区；顶部由 TopAppBar、底部由页面末尾留白负责。
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)
