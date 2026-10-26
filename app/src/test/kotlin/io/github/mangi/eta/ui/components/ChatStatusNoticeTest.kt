package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.runtime.AppExitCause
import org.junit.Assert.assertEquals
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
}
