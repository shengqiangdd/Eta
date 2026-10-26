package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只在事务提交后推进保存基线，不持有输入草稿、待发送图片等临时 UI 状态。 */
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // 未对齐的会话不进入基线，首次保存按新会话整段写入，重排 sort_index。
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()

    suspend fun save(context: Context, snapshot: AgentConversationStore.Snapshot) = mutex.withLock {
        val current = project(snapshot)
        AgentConversationStore.saveChanges(context, snapshot.selectedConversationId, current, saved)
        saved = current
    }

    /**
     * 把「刚从库里读出来、与库一致」的内容直接登记为保存基线。
     *
     * 用在按需载入消息之后：否则基线里这份会话还是空表，下一次保存会把它当成
     * 「消息全被删了」，把几千条消息重写一遍。
     */
    suspend fun adopt(
        conversationId: String,
        state: AgentChatHomeUiState,
        title: String,
        updatedAt: Long,
    ) = mutex.withLock {
        saved = saved + (conversationId to Content(state, title, updatedAt))
    }

    /**
     * 释放某个会话的常驻内存：消息、history、journal 一起标成「未载入」。
     *
     * 只清 UI 状态不够：基线里那份 [Content] 还引用着同一批列表，内存根本降不下来。
     * 落盘侧会按 deferred 标记跳过对应字段，库里原内容保持不变。
     */
    suspend fun release(conversationId: String) = mutex.withLock {
        val content = saved[conversationId] ?: return@withLock
        if (content.messagesDeferred && content.historyDeferred && content.journalDeferred) return@withLock
        saved = saved + (conversationId to content.copy(
            messages = emptyList(),
            history = emptyList(),
            journal = emptyList(),
            messagesDeferred = true,
            messagesWindowStart = 0,
            historyDeferred = true,
            journalDeferred = true,
        ))
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L)
        }

    internal data class Content(
        val title: String,
        val updatedAt: Long,
        val reasoningEffort: ReasoningEffort,
        val appliedRuntimeRunIds: List<String>,
        val roleplay: RoleplayBinding?,
        val roleplayMessages: RoleplayMessageState,
        val modelId: String?,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        /** history 是否未载入内存（此时 [history] 为空且不可写回）。 */
        val historyDeferred: Boolean = false,
        val journal: List<AgentModelClient.ConversationMessage>,
        /** journal 是否因体积过大而未载入内存（此时 [journal] 为空且不可写回）。 */
        val journalDeferred: Boolean = false,
        /** 消息列表是否未载入内存（此时 [messages] 为空且整段不可写回）。 */
        val messagesDeferred: Boolean = false,
        /** 内存消息窗口的数据库起始下标；>0 表示前面还有历史未载入。 */
        val messagesWindowStart: Int = 0,
        /** 是否置顶；属于元数据，变化时必须触发写库（见 [sameMetadata]）。 */
        val isPinned: Boolean = false,
    ) {
        constructor(state: AgentChatHomeUiState, title: String, updatedAt: Long) : this(
            title, updatedAt, state.reasoningEffort, state.appliedRuntimeRunIds,
            state.roleplay, if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            state.modelId, state.messages, state.history,
            state.historyDeferred,
            // 未载入时保持空表：这里的 `ifEmpty { history }` 只用于「真的没有 journal」的旧数据，
            // 不能拿它兜底「暂时没载入」——两者语义不同，混用会把 history 写进 journal。
            if (state.journalDeferred) emptyList() else state.journal.ifEmpty { state.history },
            state.journalDeferred,
            state.messagesDeferred,
            state.messagesWindowStart,
            state.isPinned,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId &&
                isPinned == other.isPinned
    }
}
