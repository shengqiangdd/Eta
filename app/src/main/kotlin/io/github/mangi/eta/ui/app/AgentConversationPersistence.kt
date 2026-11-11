package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ConversationBranchRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只在事务提交后推进保存基线，不持有输入草稿、待发送图片等临时 UI 状态。 */
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // 未对齐的会话不进入基线，首次保存按新会话整段写入，重排 sort_index。
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()
    // 已提交但尚未进入 Main 状态的分支必须合入旧快照，直到保存队列观察到发布后的状态。
    private var unpublishedBranches = emptyMap<String, Content>()

    suspend fun save(context: Context, snapshot: AgentConversationStore.Snapshot) = mutex.withLock {
        val projected = project(snapshot)
        val current = unpublishedBranches + projected
        AgentConversationStore.saveChanges(context, snapshot.selectedConversationId, current, saved)
        saved = current
        unpublishedBranches = unpublishedBranches - projected.keys
    }

    /**
     * 在事务里把源会话的前缀复制成独立分支，并把分支登记进保存基线。
     *
     * 为什么不在这里读回完整 [AgentChatHomeUiState]：分支的消息是**复制前缀并重排
     * sort_index** 得到的，可能与源会话几千条同量级；在保存队列的锁里整份载入会直接
     * 卡住 UI 的持久化。这里只登记元信息并把三份内容都标成 deferred，切过去之后由
     * `selectConversation` 的按需加载走本地的窗口分页（`messagesWindowStart`），
     * 既不改分页语义，也不会让分支一创建就吃掉常驻内存。
     *
     * 关键约束：`saved` 基线必须**立刻**包含这个分支。保存队列稍后会用 Main 线程的
     * 快照覆盖基线；如果基线里没有它，那份旧快照会被判定为「这个会话被删了」。
     */
    suspend fun branch(
        context: Context,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ): BranchOutcome = mutex.withLock {
        val database = EtaDatabase.get(context)
        val metadata = database.withTransaction {
            ConversationBranchRepository.create(database, sourceId, targetMessageId, branchId, title, now)
            checkNotNull(database.conversationDao().conversationMetadataRow(branchId)) {
                "conversation_branch_metadata_missing"
            }
        }
        // 库里存的是 wire 值；无法识别时退回 thinkingEnabled 推导，与读取会话的路径一致。
        val effort = ReasoningEffort.fromWireValue(metadata.reasoningEffort)
            ?: ReasoningEffort.fromLegacy(metadata.thinkingEnabled)
        // 基线立刻登记：保存队列稍后会用 Main 线程快照覆盖它，那份旧快照里没有这个分支。
        // 内容先按空表 placeholder 登记，真正切过去后由 adopt() 用库里的实际内容替换。
        saved = saved + (branchId to Content(
            state = AgentChatHomeUiState(
                messages = emptyList(),
                messagesDeferred = true,
                historyDeferred = true,
                journalDeferred = true,
                input = "",
                isStreaming = false,
                thinkingEnabled = metadata.thinkingEnabled,
                reasoningEffort = effort,
                modelId = metadata.modelId,
            ),
            title = title,
            updatedAt = now,
        ))
        BranchOutcome(
            modelId = metadata.modelId,
            thinkingEnabled = metadata.thinkingEnabled,
            reasoningEffort = effort,
        )
    }

    /**
     * 上游命名：创建分支后直接登记为已发布内容；保留以兼容上游调用点，并支持置顶位。
     * 本 fork 的分支创建走 [branch] 的 placeholder 登记，这个入口用于其他登记场景。
     */
    suspend fun adoptConversation(
        id: String,
        state: AgentChatHomeUiState,
        title: String,
        updatedAt: Long,
        isPinned: Boolean = false,
    ) = mutex.withLock {
        saved = saved + (id to Content(state, title, updatedAt, isPinned))
    }

    /** 分支创建后的元信息；UI 状态由 [AgentAppState] 在 Main 线程构造，保持单一来源。 */
    internal data class BranchOutcome(
        val modelId: String?,
        val thinkingEnabled: Boolean,
        val reasoningEffort: ReasoningEffort,
    )

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
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L, id in snapshot.pinnedIds)
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
        constructor(
            state: AgentChatHomeUiState,
            title: String,
            updatedAt: Long,
            isPinned: Boolean = false,
        ) : this(
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
            title == other.title && updatedAt == other.updatedAt && isPinned == other.isPinned &&
                reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId &&
                isPinned == other.isPinned
    }
}
