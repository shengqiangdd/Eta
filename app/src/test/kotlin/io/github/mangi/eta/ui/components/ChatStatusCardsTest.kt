package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
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
        assertTrue(
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 2, resumableRecently = false),
        )
        assertFalse(
            "只在「已结束」状态下留着没有任何可做的事",
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 0, resumableRecently = false),
        )
    }

    @Test
    fun backgroundCardStaysOnlyWhileTheResumeEntryIsFresh() {
        // 「继续」入口只在刚结束的短窗口内有效。窗口一过卡片必须收掉，
        // 否则只要开关开着就又变回常驻（用户抱怨的就是这个）。
        assertTrue(
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 0, resumableRecently = true),
        )
        assertFalse(
            "窗口过期后不该为了「继续」继续占位",
            ChatStatusCards.backgroundVisible(hasRecords = true, runningTotal = 0, resumableRecently = false),
        )
    }

    @Test
    fun backgroundCardWithoutRecordsNeverDraws() {
        assertFalse(ChatStatusCards.backgroundVisible(hasRecords = false, runningTotal = 0, resumableRecently = true))
    }

    @Test
    fun resumeEntryWindowHasTwoTiers() {
        // 默认档与「保留更久」档都必须钉住：数字变了要有人被提醒。
        val normal = ChatStatusCards.resumeEntryWindowMs(extended = false)
        val extended = ChatStatusCards.resumeEntryWindowMs(extended = true)
        assertEquals(2 * 60_000L, normal)
        assertEquals(10 * 60_000L, extended)
        assertTrue("延长档必须更长", extended > normal)
    }

    @Test
    fun resumableRecentlyRespectsWindow() {
        val now = 1_000_000L
        val normal = ChatStatusCards.resumeEntryWindowMs(extended = false)
        val extended = ChatStatusCards.resumeEntryWindowMs(extended = true)
        // 5 分钟前结束：默认档已过期，延长档仍在窗口内 —— 这就是新开关的意义。
        val fiveMinutesAgo = now - 5 * 60_000L
        assertFalse(ChatStatusCards.resumableRecently(listOf(fiveMinutesAgo), now, normal))
        assertTrue(ChatStatusCards.resumableRecently(listOf(fiveMinutesAgo), now, extended))
        // 边界：正好等于窗口仍显示，多 1ms 就不显示。
        assertTrue(ChatStatusCards.resumableRecently(listOf(now - normal), now, normal))
        assertFalse(ChatStatusCards.resumableRecently(listOf(now - normal - 1), now, normal))
        // 没有结束时间（null）的记录不能算「刚结束」。
        assertFalse(ChatStatusCards.resumableRecently(listOf(null), now, extended))
    }
}
