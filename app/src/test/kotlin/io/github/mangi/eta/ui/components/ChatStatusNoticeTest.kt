package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.runtime.AppExitCause
import io.github.mangi.eta.data.model.ChatViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStatusNoticeTest {
    @Test
    fun `一切正常时不占位`() {
        assertEquals(emptyList<ChatStatusNoticeKind>(), chatStatusNotices(missingPermissionCount = 0, journalDeferred = false))
    }

    @Test
    fun `缺权限时提示权限`() {
        assertEquals(listOf(ChatStatusNoticeKind.PermissionIssues), chatStatusNotices(missingPermissionCount = 2, journalDeferred = false))
    }

    @Test
    fun `历史过大时提示不可改写`() {
        assertEquals(listOf(ChatStatusNoticeKind.HistoryDeferred), chatStatusNotices(missingPermissionCount = 0, journalDeferred = true))
    }

    @Test
    fun `两者同时存在时先权限后历史`() {
        assertEquals(
            listOf(ChatStatusNoticeKind.PermissionIssues, ChatStatusNoticeKind.HistoryDeferred),
            chatStatusNotices(missingPermissionCount = 3, journalDeferred = true),
        )
    }

    @Test
    fun `负数计数视为正常`() {
        assertEquals(emptyList<ChatStatusNoticeKind>(), chatStatusNotices(missingPermissionCount = -1, journalDeferred = false))
    }

    @Test
    fun `消息未载入时提示正在载入`() {
        assertEquals(
            listOf(ChatStatusNoticeKind.MessagesLoading),
            chatStatusNotices(missingPermissionCount = 0, journalDeferred = false, messagesDeferred = true),
        )
    }

    @Test
    fun `载入中排在权限与历史之前`() {
        assertEquals(
            listOf(
                ChatStatusNoticeKind.MessagesLoading,
                ChatStatusNoticeKind.PermissionIssues,
                ChatStatusNoticeKind.HistoryDeferred,
            ),
            chatStatusNotices(missingPermissionCount = 1, journalDeferred = true, messagesDeferred = true),
        )
    }

    @Test
    fun `收窄视图藏了消息时附一条提示`() {
        assertEquals(
            listOf(ChatStatusNoticeKind.ViewModeFiltered),
            chatStatusNotices(
                missingPermissionCount = 0,
                journalDeferred = false,
                hiddenMessageCount = 12,
            ),
        )
    }

    @Test
    fun `收窄视图没藏到东西时不占位`() {
        assertEquals(
            emptyList<ChatStatusNoticeKind>(),
            chatStatusNotices(
                missingPermissionCount = 0,
                journalDeferred = false,
                hiddenMessageCount = 0,
            ),
        )
    }

    @Test
    fun `收窄视图排在真实故障之后`() {
        assertEquals(
            listOf(
                ChatStatusNoticeKind.PermissionIssues,
                ChatStatusNoticeKind.HistoryDeferred,
                ChatStatusNoticeKind.ViewModeFiltered,
            ),
            chatStatusNotices(
                missingPermissionCount = 2,
                journalDeferred = true,
                hiddenMessageCount = 3,
            ),
        )
    }

    @Test
    fun `进程被系统终止时说明原因`() {
        assertEquals(
            listOf(ChatStatusNoticeKind.SystemExit),
            chatStatusNotices(
                missingPermissionCount = 0,
                journalDeferred = false,
                systemExit = AppExitCause.LOW_MEMORY,
            ),
        )
    }

    @Test
    fun `正常退出不占位`() {
        assertEquals(
            emptyList<ChatStatusNoticeKind>(),
            chatStatusNotices(
                missingPermissionCount = 0,
                journalDeferred = false,
                systemExit = AppExitCause.NONE,
            ),
        )
    }

    @Test
    fun `系统终止排在权限之后历史之前`() {
        assertEquals(
            listOf(
                ChatStatusNoticeKind.PermissionIssues,
                ChatStatusNoticeKind.SystemExit,
                ChatStatusNoticeKind.HistoryDeferred,
            ),
            chatStatusNotices(
                missingPermissionCount = 1,
                journalDeferred = true,
                systemExit = AppExitCause.SYSTEM_KILL,
            ),
        )
    }

    // ---- 忽略模型：关掉之后不该再打扰，但条件真的消失后要能重新提醒 ----

    @Test
    fun `关掉权限提示后同一批问题不再打扰`() {
        val ids = setOf("background", "overlay")
        val dismissed = ChatStatusNoticeDismissal()
            .dismiss(ChatStatusNoticeKind.PermissionIssues, ids, ChatViewMode.ALL)

        assertTrue(dismissed.hides(ChatStatusNoticeKind.PermissionIssues, ids, ChatViewMode.ALL))
    }

    @Test
    fun `修好其中一项不会把剩下的又弹回来`() {
        val dismissed = ChatStatusNoticeDismissal().dismiss(
            ChatStatusNoticeKind.PermissionIssues,
            setOf("background", "overlay", "location"),
            ChatViewMode.ALL,
        )

        assertTrue(
            dismissed.hides(
                ChatStatusNoticeKind.PermissionIssues,
                setOf("background", "overlay"),
                ChatViewMode.ALL,
            ),
        )
    }

    @Test
    fun `新出现的权限问题会重新提醒`() {
        val dismissed = ChatStatusNoticeDismissal()
            .dismiss(ChatStatusNoticeKind.PermissionIssues, setOf("background"), ChatViewMode.ALL)

        assertFalse(
            dismissed.hides(
                ChatStatusNoticeKind.PermissionIssues,
                setOf("background", "calendar"),
                ChatViewMode.ALL,
            ),
        )
    }

    @Test
    fun `首次检查完成前不清空忽略记录`() {
        val dismissed = ChatStatusNoticeDismissal(
            dismissedPermissionIssueIds = setOf("background", "overlay"),
        )

        val reconciled = dismissed.reconcile(
            currentPermissionIssueIds = emptySet(),
            permissionIssuesKnown = false,
            messagesDeferred = false,
            journalDeferred = false,
            hiddenMessageCount = 0,
        )

        assertEquals(setOf("background", "overlay"), reconciled.dismissedPermissionIssueIds)
    }

    @Test
    fun `检查完成后只忘掉已经修好的项`() {
        val dismissed = ChatStatusNoticeDismissal(
            dismissedPermissionIssueIds = setOf("background", "overlay", "location"),
        )

        val reconciled = dismissed.reconcile(
            currentPermissionIssueIds = setOf("background"),
            permissionIssuesKnown = true,
            messagesDeferred = false,
            journalDeferred = false,
            hiddenMessageCount = 0,
        )

        assertEquals(setOf("background"), reconciled.dismissedPermissionIssueIds)
    }

    @Test
    fun `修好后再关掉同一项会重新提醒`() {
        val dismissed = ChatStatusNoticeDismissal(dismissedPermissionIssueIds = setOf("background"))

        val reconciled = dismissed.reconcile(
            currentPermissionIssueIds = emptySet(),
            permissionIssuesKnown = true,
            messagesDeferred = false,
            journalDeferred = false,
            hiddenMessageCount = 0,
        )

        assertTrue(reconciled.dismissedPermissionIssueIds.isEmpty())
        assertFalse(
            reconciled.hides(ChatStatusNoticeKind.PermissionIssues, setOf("background"), ChatViewMode.ALL),
        )
    }

    @Test
    fun `关掉载入中提示后不再出现`() {
        val dismissed = ChatStatusNoticeDismissal()
            .dismiss(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL)

        assertTrue(dismissed.hides(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL))
    }

    @Test
    fun `载入完成后忘掉载入提示的忽略记录`() {
        val dismissed = ChatStatusNoticeDismissal()
            .dismiss(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL)

        val reconciled = dismissed.reconcile(
            currentPermissionIssueIds = emptySet(),
            permissionIssuesKnown = true,
            messagesDeferred = false,
            journalDeferred = false,
            hiddenMessageCount = 0,
        )

        assertFalse(reconciled.hides(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL))
    }

    @Test
    fun `视图过滤按模式分别记忆`() {
        val dismissed = ChatStatusNoticeDismissal()
            .dismiss(ChatStatusNoticeKind.ViewModeFiltered, emptySet(), ChatViewMode.DIALOG)

        assertTrue(dismissed.hides(ChatStatusNoticeKind.ViewModeFiltered, emptySet(), ChatViewMode.DIALOG))
        assertFalse(dismissed.hides(ChatStatusNoticeKind.ViewModeFiltered, emptySet(), ChatViewMode.TRACE))
    }

    @Test
    fun `进程退出提示的关闭交给状态层`() {
        val dismissed = ChatStatusNoticeDismissal()

        assertEquals(dismissed, dismissed.dismiss(ChatStatusNoticeKind.SystemExit, emptySet(), ChatViewMode.ALL))
        assertFalse(dismissed.hides(ChatStatusNoticeKind.SystemExit, emptySet(), ChatViewMode.ALL))
    }

    // ---- 撤销：只回滚那一次关掉的那一条 ----

    @Test
    fun `撤销后同一条提示重新出现`() {
        val ids = setOf("background")
        val before = ChatStatusNoticeDismissal()
        val after = before.dismiss(ChatStatusNoticeKind.PermissionIssues, ids, ChatViewMode.ALL)

        val undone = after.undoDismissal(before = before, after = after)

        assertFalse(undone.hides(ChatStatusNoticeKind.PermissionIssues, ids, ChatViewMode.ALL))
    }

    @Test
    fun `撤销不会抹掉期间关掉的其它提示`() {
        val before = ChatStatusNoticeDismissal()
        val first = before.dismiss(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL)
        val second = first.dismiss(ChatStatusNoticeKind.HistoryDeferred, emptySet(), ChatViewMode.ALL)

        // 用户点的是第一次那条的「撤销」：只该恢复它，后来关掉的历史提示要留着。
        val undone = second.undoDismissal(before = before, after = first)

        assertFalse(undone.hides(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL))
        assertTrue(undone.hides(ChatStatusNoticeKind.HistoryDeferred, emptySet(), ChatViewMode.ALL))
    }

    @Test
    fun `撤销权限提示只减掉那一次新关的 id`() {
        val before = ChatStatusNoticeDismissal(dismissedPermissionIssueIds = setOf("location"))
        val after = before.dismiss(ChatStatusNoticeKind.PermissionIssues, setOf("background"), ChatViewMode.ALL)

        val undone = after.undoDismissal(before = before, after = after)

        assertEquals(setOf("location"), undone.dismissedPermissionIssueIds)
    }

    @Test
    fun `撤销视图过滤只恢复那一种视图`() {
        val before = ChatStatusNoticeDismissal()
        val after = before.dismiss(ChatStatusNoticeKind.ViewModeFiltered, emptySet(), ChatViewMode.DIALOG)

        val undone = after.undoDismissal(before = before, after = after)

        assertFalse(undone.hides(ChatStatusNoticeKind.ViewModeFiltered, emptySet(), ChatViewMode.DIALOG))
    }

    @Test
    fun `撤销已经失效的记录时状态不变`() {
        val before = ChatStatusNoticeDismissal()
        val after = before.dismiss(ChatStatusNoticeKind.MessagesLoading, emptySet(), ChatViewMode.ALL)
        val reconciled = after.reconcile(
            currentPermissionIssueIds = emptySet(),
            permissionIssuesKnown = true,
            messagesDeferred = false,
            journalDeferred = false,
            hiddenMessageCount = 0,
        )

        assertEquals(reconciled, reconciled.undoDismissal(before = before, after = after))
    }

    // ---- 体积上限：提示条不能自己变成半个屏幕 ----

    @Test
    fun `两条以内全部铺开`() {
        val notices = listOf(ChatStatusNoticeKind.PermissionIssues, ChatStatusNoticeKind.HistoryDeferred)

        val layout = chatNoticeLayout(notices, expanded = false)

        assertEquals(notices, layout.visible)
        assertEquals(0, layout.hiddenCount)
    }

    @Test
    fun `超过两条时收起多余项`() {
        val notices = listOf(
            ChatStatusNoticeKind.MessagesLoading,
            ChatStatusNoticeKind.PermissionIssues,
            ChatStatusNoticeKind.SystemExit,
            ChatStatusNoticeKind.HistoryDeferred,
            ChatStatusNoticeKind.ViewModeFiltered,
        )

        val layout = chatNoticeLayout(notices, expanded = false)

        assertEquals(notices.take(CHAT_NOTICE_COLLAPSED_ROWS), layout.visible)
        assertEquals(notices.size - CHAT_NOTICE_COLLAPSED_ROWS, layout.hiddenCount)
    }

    @Test
    fun `展开后显示全部`() {
        val notices = listOf(
            ChatStatusNoticeKind.MessagesLoading,
            ChatStatusNoticeKind.PermissionIssues,
            ChatStatusNoticeKind.SystemExit,
            ChatStatusNoticeKind.HistoryDeferred,
            ChatStatusNoticeKind.ViewModeFiltered,
        )

        val layout = chatNoticeLayout(notices, expanded = true)

        assertEquals(notices, layout.visible)
        assertEquals(0, layout.hiddenCount)
    }
}
