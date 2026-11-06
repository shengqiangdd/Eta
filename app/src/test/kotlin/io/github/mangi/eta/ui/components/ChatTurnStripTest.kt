package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合导航条的布局契约。
 *
 * 上一版贴在消息区右侧，列表必须为它让出 44dp 宽 —— 本机 347dp 宽的屏上每行正文少 13%，
 * 用户直接指出「多轮消息时消息内容被挤掉一块」。现在导航条在输入框上方，宽度与正文无关。
 *
 * 这组用例守两件事：
 * 1. 显示判据只有一处（聊天内搜索激活时必须消失，否则两种跳转会打架）；
 * 2. 胶囊尺寸自洽（按钮放得进、计数器与箭头之间有留白），改尺寸时别把数字挤没。
 */
class ChatTurnStripTest {

    @Test
    fun stripHiddenUntilThereIsMoreThanOneTurn() {
        // 只有一次提问时没有「上一个 / 下一个」可言，画出来只会白占一行高度。
        assertFalse(chatTurnStripVisible(turnNavigationEnabled = true, total = 0))
        assertFalse(chatTurnStripVisible(turnNavigationEnabled = true, total = 1))
        assertTrue(chatTurnStripVisible(turnNavigationEnabled = true, total = 2))
    }

    @Test
    fun stripHiddenWhileSearchIsActive() {
        // 搜索激活时隐藏：两种跳转都会滚列表，同时出现会让人不知道点的是哪个。
        assertFalse(chatTurnStripVisible(turnNavigationEnabled = false, total = 12))
    }

    @Test
    fun stripHiddenWhenListFitsOnOneScreen() {
        // 一屏放得下时上下箭头没有可跳的目标：画出来只会白占一行高度，点了也不动。
        assertFalse(chatTurnStripVisible(turnNavigationEnabled = true, total = 2, canScroll = false))
        assertTrue(chatTurnStripVisible(turnNavigationEnabled = true, total = 2, canScroll = true))
    }

    @Test
    fun labelShowsDashBeforeTheFirstTurn() {
        // 视口停在开场卡片上时显示「—/N」：显示 1/N 会让人以为自己正看着第 1 回合。
        assertEquals("—/12", chatTurnStripLabel(currentTurn = null, total = 12))
        assertEquals("—/0", chatTurnStripLabel(currentTurn = null, total = 0))
    }

    @Test
    fun labelIsOneBasedAndClamped() {
        assertEquals("1/12", chatTurnStripLabel(currentTurn = 0, total = 12))
        assertEquals("12/12", chatTurnStripLabel(currentTurn = 11, total = 12))
        // 编辑历史消息会把后续回合截掉，currentTurn 可能比新的 total 还大。
        assertEquals("5/5", chatTurnStripLabel(currentTurn = 19, total = 5))
    }

    @Test
    fun stripHeightFitsTheStepButton() {
        assertEquals(
            ChatTurnStripMetrics.StepButtonSize + ChatTurnStripMetrics.VerticalPadding * 2,
            ChatTurnStripMetrics.Height,
        )
        // 按钮比胶囊还高时会被裁掉一半，点起来只剩一条缝。
        assertTrue(ChatTurnStripMetrics.Height >= ChatTurnStripMetrics.StepButtonSize)
    }

    @Test
    fun labelKeepsSpaceFromTheArrows() {
        // 0 留白时「12」和箭头会粘成一个符号，读不出这是两侧按钮的中间值。
        assertTrue(ChatTurnStripMetrics.LabelHorizontalPadding > 0.dp)
    }
}
