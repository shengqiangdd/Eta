package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合导航的留白契约。
 *
 * 导航条浮在消息列表右侧、不参与布局：正文能不能读全，取决于**列表让出的宽度 ≥ 导航条实际占宽**。
 * 真机上曾经出过「行尾被不透明背景切掉」，所以把这条关系钉成用例 ——
 * 以后改按钮尺寸、内边距或贴边间距时，只要忘了同步留白，这里先红。
 */
class ChatTurnNavigatorGutterTest {

    @Test
    fun hiddenNavigatorReservesNothing() {
        assertEquals(0.dp, chatTurnNavigatorGutter(false))
    }

    @Test
    fun visibleNavigatorReservesItsOwnWidthPlusEdgeGap() {
        assertEquals(
            ChatTurnNavigatorMetrics.Width + ChatTurnNavigatorMetrics.EndGap,
            chatTurnNavigatorGutter(true),
        )
    }

    @Test
    fun gutterCoversTheWholeFloatingBar() {
        // 只让出导航条自身宽度不够：它还贴在屏幕右缘，正文照样被压住。
        assertTrue(
            chatTurnNavigatorGutter(true) >=
                ChatTurnNavigatorMetrics.Width + ChatTurnNavigatorMetrics.EndGap,
        )
    }

    @Test
    fun barWidthFollowsItsOwnParts() {
        assertEquals(
            maxOf(
                ChatTurnNavigatorMetrics.ColumnMinWidth,
                ChatTurnNavigatorMetrics.StepButtonSize + ChatTurnNavigatorMetrics.ColumnPadding * 2,
            ),
            ChatTurnNavigatorMetrics.Width,
        )
    }

    @Test
    fun minWidthStillFitsTheStepButton() {
        // 最小宽度比按钮还窄时，`widthIn` 会被子项撑开，Width 的计算就不再等于实际宽度。
        assertTrue(
            ChatTurnNavigatorMetrics.ColumnMinWidth >= ChatTurnNavigatorMetrics.StepButtonSize,
        )
    }
}
