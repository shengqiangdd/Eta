package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * journal 超过阈值就不在启动时还原（见 AgentConversationStore.JOURNAL_INLINE_LIMIT）。
 *
 * 守四件事：大会话不把 journal 铺进内存、库里原文不被空表覆盖、小会话行为不变、
 * 依赖 journal 的改写操作在 deferred 时明确拒绝而不是拿 history 凑合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationJournalDeferralTest {
    private lateinit var context: Context

    // 直接用实现里的阈值构造数据，改阈值时不需要改测试。
    private val hugeAnswer = "答".repeat(AgentConversationStore.JOURNAL_INLINE_LIMIT + 100_000)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

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
    fun smallConversationStillLoadsJournalInline() = runBlocking {
        val journal = listOf(
            AgentModelClient.ConversationMessage("user", "你好", messageId = "u1"),
            AgentModelClient.ConversationMessage("assistant", "在的", messageId = "a1"),
        )
        persist("small", "小会话", stateOf(journal, listOf(UserMessageUi("u1", "你好"), AgentMessageUi("a1", "在的"))), 1L)

        val restored = AgentConversationStore.load(context).conversationsById.getValue("small")
        assertFalse(restored.journalDeferred)
        assertEquals(journal, restored.journal)
    }

    @Test
    fun largeConversationDefersJournalButKeepsMessagesAndHistory() = runBlocking {
        persist(LARGE, "大会话", stateOf(largeJournal(), largeMessages()), 1L)

        val restored = AgentConversationStore.load(context).conversationsById.getValue(LARGE)
        assertTrue("超过阈值的 journal 不应载入内存", restored.journalDeferred)
        assertTrue(restored.journal.isEmpty())
        assertEquals("消息本身必须完整", largeMessages(), restored.messages)
        assertEquals("模型上下文必须完整", 2, restored.history.size)
        assertEquals(hugeAnswer, restored.history.last().content)
    }

    @Test
    fun savingDeferredConversationDoesNotOverwriteStoredJournal() = runBlocking {
        persist(LARGE, "大会话", stateOf(largeJournal(), largeMessages()), 1L)
        val storedBefore = journalRow()
        assertTrue(storedBefore.startsWith(ChunkedTextDao.REFERENCE_PREFIX))

        val loaded = AgentConversationStore.load(context).conversationsById.getValue(LARGE)
        assertTrue(loaded.journalDeferred)

        // 模拟「在这个大会话里继续对话」：消息与模型上下文都变了，但 journal 没有载入。
        val continued = loaded.copy(
            messages = loaded.messages + UserMessageUi("u2", "继续"),
            history = loaded.history + AgentModelClient.ConversationMessage("user", "继续", messageId = "u2"),
        )
        persist(LARGE, "大会话", continued, 2L)

        assertEquals("空 journal 不能覆盖库里已有的完整历史", storedBefore, journalRow())
        val reloaded = AgentConversationStore.load(context).conversationsById.getValue(LARGE)
        assertTrue(reloaded.journalDeferred)
        assertEquals(3, reloaded.history.size)
    }

    @Test
    fun revisionBoundaryRefusesDeferredConversation() {
        val state = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("u1", "问题")),
            history = listOf(AgentModelClient.ConversationMessage("user", "问题", messageId = "u1")),
            journal = emptyList(),
            journalDeferred = true,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        assertNull("deferred 会话不能算出边界（否则会拿 history 冒充 journal）", AgentConversationRevisionReducer.boundary(state, "u1"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "u1"))
    }

    @Test
    fun runtimeHistoryKeepsJournalEmptyWhenDeferred() {
        val state = AgentChatHomeUiState(
            messages = emptyList(),
            history = listOf(AgentModelClient.ConversationMessage("user", "旧请求")),
            journal = emptyList(),
            journalDeferred = true,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val outcome = AgentRuntimeHistoryReducer.apply(
            state, "run-1", listOf(AgentModelClient.ConversationMessage("assistant", "新回答")),
        )
        assertTrue("deferred 时不能拿 history 当 journal 再叠加新消息", outcome.state.journal.isEmpty())
        assertTrue(outcome.state.journalDeferred)
        assertEquals(2, outcome.state.history.size)
    }

    private fun largeJournal() = listOf(
        AgentModelClient.ConversationMessage("user", "讲个长故事", messageId = "u1"),
        AgentModelClient.ConversationMessage("assistant", hugeAnswer, messageId = "a1"),
    )

    private fun largeMessages() = listOf(UserMessageUi("u1", "讲个长故事"), AgentMessageUi("a1", hugeAnswer))

    private fun stateOf(
        journal: List<AgentModelClient.ConversationMessage>,
        messages: List<io.github.mangi.eta.ui.model.AgentChatMessageUi>,
    ) = AgentChatHomeUiState(
        messages = messages,
        history = journal,
        journal = journal,
        input = "", isStreaming = false, thinkingEnabled = false,
    )

    private suspend fun persist(id: String, title: String, state: AgentChatHomeUiState, updatedAt: Long) {
        AgentConversationStore.save(
            context, id, mapOf(id to state), mapOf(id to title), mapOf(id to updatedAt),
        )
    }

    private suspend fun journalRow(): String =
        EtaDatabase.get(context).conversationDao().contextCheckpointRow(LARGE)!!.journalJson

    private companion object {
        const val LARGE = "large"
    }
}
