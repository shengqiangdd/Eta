package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页底部常驻卡什么时候该占位。
 *
 * 这次改动针对的是一个很具体的抱怨：「任务都跑完了，卡片还一直挤在输入框上方」。
 * 判断条件从「有数据就画」改成「还有需要你知道的事才画」，所以每一条都要钉住 ——
 * 判据写宽了卡片继续堆，写窄了正在跑的任务反而看不见。
 */
class ChatStatusCardsTest {

    @Test
    fun runningLedgerIsAlwaysVisible() {
        assertTrue(ChatStatusCards.ledgerVisible(true, false, running = 1, failed = 0, stalled = 0))
    }

    @Test
    fun finishedLedgerCollapsesWhenNothingNeedsAttention() {
        assertFalse(
            "全部成功结束时不该再占一行",
            ChatStatusCards.ledgerVisible(true, false, running = 0, failed = 0, stalled = 0),
        )
    }

    @Test
    fun failedAndStalledKeepTheCardSoTheyDoNotDisappearSilently() {
        // 失败还没被处理，用户可能正要重派；静悄悄收掉等于把错误藏了。
        assertTrue(ChatStatusCards.ledgerVisible(true, false, running = 0, failed = 1, stalled = 0))
        assertTrue(ChatStatusCards.ledgerVisible(true, false, running = 0, failed = 0, stalled = 1))
    }

    @Test
    fun userPreferenceCanForceTheCardToStay() {
        // 关掉「自动收起」时要恢复旧行为：有记录就显示，哪怕全完成了。
        assertTrue(ChatStatusCards.ledgerVisible(true, true, running = 0, failed = 0, stalled = 0))
    }

    @Test
    fun emptyLedgerNeverDrawsEvenWhenPinned() {
        // 「始终显示」不能把空台账画成一个只有标题的空壳。
        assertFalse(ChatStatusCards.ledgerVisible(false, true, running = 0, failed = 0, stalled = 0))
    }

    @Test
    fun backgroundCardFollowsRunningTasksNotHistory() {
        assertTrue(ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 2, keepResumeAfterFinish = false))
        assertFalse(
            "只在「已结束」状态下留着没有任何可做的事",
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 0, keepResumeAfterFinish = false),
        )
    }

    @Test
    fun backgroundCardStaysWhenTheResumeButtonIsThePoint() {
        // 用户开了「结束后保留继续按钮」时，这一行本身就是入口，不能按「没在跑」收掉。
        assertTrue(
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 0, keepResumeAfterFinish = true),
        )
    }

    @Test
    fun backgroundCardWithoutRecordsNeverDraws() {
        assertFalse(ChatStatusCards.backgroundVisible(hasRecords = false, runningTotal = 0, keepResumeAfterFinish = true))
    }
}
