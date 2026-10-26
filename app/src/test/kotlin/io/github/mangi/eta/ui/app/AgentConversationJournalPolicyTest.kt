package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * journal 延迟加载里**不碰 SQLite** 的那部分决策。
 *
 * 为什么单独一个类：Robolectric 的 native runtime 目前没有 Linux/aarch64 构建
 * （`The Robolectric native runtime is not supported on Linux (aarch64)`），
 * 所以 Store/Persistence 那批用例只能在 x86_64 上跑。这些纯逻辑必须在这里也能验，
 * 否则本机就等于「没有任何测试覆盖」，出了问题只能靠肉眼。
 */
class AgentConversationJournalPolicyTest {
    @Test
    fun referencedLengthReadsStoredLengthWithoutTouchingChunks() {
        assertEquals(0, ChunkedTextDao.referencedLength(null))
        assertEquals(0, ChunkedTextDao.referencedLength(""))
        assertEquals(3, ChunkedTextDao.referencedLength("abc"))
        assertEquals(1000, ChunkedTextDao.referencedLength("@eta:chunks:v1:2:1000"))
    }

    @Test
    fun referencedLengthTreatsUnparsableReferenceAsHuge() {
        // 解析不出来宁可当作「极大」：猜小会让不该整段还原的 journal 被铺满内存。
        assertEquals(ChunkedTextDao.UNKNOWN_LENGTH, ChunkedTextDao.referencedLength("@eta:chunks:v1:2"))
        assertEquals(ChunkedTextDao.UNKNOWN_LENGTH, ChunkedTextDao.referencedLength("@eta:chunks:v1:x:y"))
    }

    @Test
    fun revisionBoundaryRefusesDeferredConversation() {
        val state = stateOf(deferred = true)
        assertNull(
            "deferred 会话算不出边界：journal 是空表，退回 history 会把压缩过的投影当成完整历史",
            AgentConversationRevisionReducer.boundary(state, "u1"),
        )
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "u1"))
    }

    @Test
    fun runtimeHistoryKeepsJournalEmptyWhenDeferred() {
        val outcome = AgentRuntimeHistoryReducer.apply(
            stateOf(deferred = true), "run-1", listOf(AgentModelClient.ConversationMessage("assistant", "新回答")),
        )
        assertTrue("不能拿 history 当 journal 再叠加新消息", outcome.state.journal.isEmpty())
        assertTrue(outcome.state.journalDeferred)
        assertEquals(2, outcome.state.history.size)
    }

    @Test
    fun persistedContentNeverCarriesDeferredJournal() {
        val content = AgentConversationPersistence.Content(stateOf(deferred = true), "会话", 1L)
        assertTrue(content.journalDeferred)
        assertTrue("deferred 时 Content.journal 必须是空表", content.journal.isEmpty())
    }

    @Test
    fun persistedContentStillFallsBackForLegacyRowsWithoutJournal() {
        // 老数据可能真的没有 journal：这条回退必须保留，否则升级后历史消息会整段消失。
        val legacy = stateOf(deferred = false, withJournal = false)
        val content = AgentConversationPersistence.Content(legacy, "会话", 1L)
        assertFalse(content.journalDeferred)
        assertEquals(legacy.history, content.journal)
    }

    @Test
    fun persistedContentNeverCarriesDeferredHistory() {
        val state = stateOf(deferred = false).copy(history = emptyList(), historyDeferred = true)
        val content = AgentConversationPersistence.Content(state, "会话", 1L)
        assertTrue(content.historyDeferred)
        assertTrue("deferred 时 Content.history 必须是空表", content.history.isEmpty())
    }

    @Test
    fun historyWritableOnlyWhenBothSidesLoaded() {
        val loaded = AgentConversationPersistence.Content(stateOf(deferred = false), "会话", 1L)
        val deferred = AgentConversationPersistence.Content(
            stateOf(deferred = false).copy(history = emptyList(), historyDeferred = true), "会话", 1L,
        )
        assertTrue(AgentConversationStore.historyWritableFor(loaded, loaded))
        assertFalse("本次没载入就不能写", AgentConversationStore.historyWritableFor(deferred, loaded))
        assertFalse("上次没载入也不能写", AgentConversationStore.historyWritableFor(loaded, deferred))
        assertTrue("没有基线时按可写处理", AgentConversationStore.historyWritableFor(loaded, null))
    }

    @Test
    fun journalWritableOnlyWhenBothSidesLoaded() {
        val loaded = AgentConversationPersistence.Content(stateOf(deferred = false), "会话", 1L)
        val deferred = AgentConversationPersistence.Content(stateOf(deferred = true), "会话", 1L)
        assertTrue(AgentConversationStore.journalWritableFor(loaded, loaded))
        assertFalse("本次没载入就不能写", AgentConversationStore.journalWritableFor(deferred, loaded))
        assertFalse("上次没载入也不能写", AgentConversationStore.journalWritableFor(loaded, deferred))
        assertTrue("没有基线（全新会话）时按可写处理，由 journalShouldBeEncoded 再判一次", AgentConversationStore.journalWritableFor(loaded, null))
    }

    @Test
    fun journalEncodingRequiresWritableEvenWhenBaselineIsMissing() {
        // 真机上会丢数据的那条路径：整包保存时基线为空，deferred 会话的空表被当成新内容写进去。
        assertFalse(AgentConversationStore.journalShouldBeEncoded(false, checkpointMissing = true, baselineMissing = true, journalChanged = true))
        assertTrue(AgentConversationStore.journalShouldBeEncoded(true, checkpointMissing = true, baselineMissing = false, journalChanged = false))
        assertTrue(AgentConversationStore.journalShouldBeEncoded(true, checkpointMissing = false, baselineMissing = false, journalChanged = true))
        assertFalse(AgentConversationStore.journalShouldBeEncoded(true, checkpointMissing = false, baselineMissing = false, journalChanged = false))
    }

    private fun stateOf(
        deferred: Boolean,
        withJournal: Boolean = true,
    ): AgentChatHomeUiState {
        val message = AgentModelClient.ConversationMessage("user", "问题", messageId = "u1")
        return AgentChatHomeUiState(
            messages = listOf(UserMessageUi("u1", "问题")),
            history = listOf(message),
            journal = if (deferred || !withJournal) emptyList() else listOf(message),
            journalDeferred = deferred,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
    }
}
