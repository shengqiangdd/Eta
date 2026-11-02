package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话侧栏的形态判据。
 *
 * 窄屏是抽屉（可拖动、盖住聊天），宽屏是常驻一栏（和聊天并排）。这两条路一旦判据漂移，
 * 会出现「列表画在左边但抽屉的遮罩还在」或者「宽屏点开列表只是把聊天挪了一下」这种怪状态。
 * 所以把边界和宽度约束钉成用例：改阈值、改夹取区间时这里先红。
 */
class ConversationPaneLayoutTest {

    @Test
    fun compactWidthKeepsTheDrawer() {
        assertEquals(ConversationPaneMode.Overlay, conversationPaneMode(599.dp))
        assertEquals(ConversationPaneMode.Overlay, conversationPaneMode(360.dp))
    }

    @Test
    fun wideThresholdDocksThePane() {
        // 600dp 与 WindowSizeClass 的 Medium 起点一致，也和页面留白共用同一个阈值。
        assertEquals(ConversationPaneMode.Docked, conversationPaneMode(600.dp))
        assertEquals(ConversationPaneMode.Docked, conversationPaneMode(1024.dp))
    }

    @Test
    fun drawerWidthKeepsTheOldFormula() {
        // 窄屏抽屉：84% 容器宽，上限 340dp —— 要能露出一点聊天当背景。
        assertEquals(336f, conversationPaneWidth(400.dp, ConversationPaneMode.Overlay).value, 0.5f)
        assertEquals(340f, conversationPaneWidth(1200.dp, ConversationPaneMode.Overlay).value, 0.5f)
    }

    @Test
    fun dockedWidthStaysInsideItsRange() {
        // 600dp 上 34% 只有 204dp，放不下搜索框，必须被下限抬到 260dp。
        assertEquals(260f, conversationPaneWidth(600.dp, ConversationPaneMode.Docked).value, 0.5f)
        // 再宽也不能让列表吃掉聊天：上限 360dp。
        assertEquals(360f, conversationPaneWidth(2000.dp, ConversationPaneMode.Docked).value, 0.5f)
    }

    @Test
    fun dockedPaneLeavesMajorityToChat() {
        var width = 600
        while (width <= 2400) {
            val pane = conversationPaneWidth(width.dp, ConversationPaneMode.Docked)
            assertTrue(
                "容器 ${width}dp 时侧栏 ${pane.value}dp 超过了一半",
                pane.value <= width / 2f,
            )
            width += 40
        }
    }

    @Test
    fun paneWidthNeverShrinksWithinAMode() {
        // 跨形态会跳变：599dp 是「盖住聊天的抽屉」340dp，600dp 变成「和聊天并存的常驻栏」260dp。
        // 两者语义不同，不该要求连成一条单调曲线；同一形态内则必须单调，
        // 否则旋转或分屏时列表会莫名变窄一下。
        var previous = 0f
        var width = 320
        while (width < 600) {
            val pane = conversationPaneWidth(width.dp, ConversationPaneMode.Overlay).value
            assertTrue("抽屉在 ${width}dp 时回缩了：$previous → $pane", pane >= previous - 0.01f)
            previous = pane
            width += 4
        }
        previous = 0f
        width = 600
        while (width <= 2400) {
            val pane = conversationPaneWidth(width.dp, ConversationPaneMode.Docked).value
            assertTrue("常驻栏在 ${width}dp 时回缩了：$previous → $pane", pane >= previous - 0.01f)
            previous = pane
            width += 4
        }
    }
}
