package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.Process
import androidx.room.withTransaction
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.terminal.SessionWorkspaceStore
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.ui.components.ChatNoticeDismissalPrefs
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * sort_index 是否为严格递增的行序。
     *
     * 不能要求「从窗口起始行号开始」：历史被裁剪过的会话，库里只剩尾部，首行 sort_index 可能是
     * 12328 而不是 0。以前用 `row.sortIndex != windowStart + index` 判定，等于给这类会话永久打上
     * 「未对齐」标记 —— 它不进保存基线，每次保存都被当成新会话按行偏移重写，删除范围又把行偏移
     * 当 sort_index 用，直接把窗口之前的行删掉。用户看到的就是「切走再回来 / 重启后消息少一大截」。
     */
    internal fun areSortIndexesIncreasing(indexes: List<Int>): Boolean =
        indexes.withIndex().all { (index, value) -> index == 0 || indexes[index - 1] < value }

    /**
     * 重写消息后缀时的真实 sort_index 起点。
     *
     * 优先用「按行偏移回查到的 sort_index」（精确，不依赖 sort_index 与行号相等）；
     * 该行不存在（窗口比库还长）时退化为最大 sort_index + 1，最后才是空表的 0。
     */
    internal fun resolveMessageRewindStart(sortIndexAtOffset: Int?, maxSortIndex: Int?): Int =
        sortIndexAtOffset ?: ((maxSortIndex ?: -1) + 1)

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        /**
         * 加载后列表下标与库中 sort_index 不一致的会话。流式占位等未入库消息会在排序中留下空洞，
         * 重启后列表被压紧；增量保存按下标截断后缀，这些会话必须先整段重写一次。
         */
        val unalignedConversationIds: Set<String> = emptySet(),
        /**
         * 每个会话的消息条数（来自 SQL 子查询，不是内存里的 messages 长度）。
         *
         * 为什么不用 `state.messages.size`：`messagesDeferred` 的会话内存里是空表，
         * 而会话列表恰恰需要在"没载入"时也能显示这个会话有多重。
         */
        val messageCounts: Map<String, Int> = emptyMap(),
        /** 置顶的会话 id 集合：与 conversationsById[].isPinned 冗余，但列表排序时更快。 */
        val pinnedIds: Set<String> = emptySet(),
    ) {
        companion object {
            /**
             * 空快照：启动时先拿它构建状态，让首帧立刻画出来，
             * 真正的加载放到后台线程，加载完再整体替换（见 AgentAppState 的 init）。
             */
            fun empty(): Snapshot = Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }
    }

    private val saveMutex = Mutex()

    fun load(context: Context): Snapshot =
        runBlocking(Dispatchers.IO) {
            loadSnapshot(context.applicationContext)
        }

    /** 单个会话的消息（切换会话时按需载入，见 AgentChatUiState.messagesDeferred）。 */
    internal data class LoadedMessages(
        val messages: List<AgentChatMessageUi>,
        /** 列表下标与库中 sort_index 不一致：该会话需要整段重写一次。 */
        val unaligned: Boolean,
        /** 这次载入从库里的哪个 sort_index 开始；0 表示完整列表。 */
        val windowStart: Int = 0,
    )

    suspend fun loadMessages(
        context: Context,
        conversationId: String,
        extraRunIds: Set<String> = emptySet(),
    ): LoadedMessages =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            loadMessageRows(
                dao = dao,
                conversationId = conversationId,
                start = 0,
                count = Int.MAX_VALUE,
                label = "full",
                activeRunIds = { queryActiveRunIds(context, extraRunIds) },
            )
        }

    /**
     * 载入最近 [limit] 条消息；大会话的 UI 窗口用它，避免一次把全部消息放进内存。
     * 返回的 [LoadedMessages.windowStart] 是窗口在数据库里的起始下标。
     */
    suspend fun loadMessagesWindow(
        context: Context,
        conversationId: String,
        limit: Int = MESSAGE_WINDOW_SIZE,
    ): LoadedMessages =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val total = dao.messageCount(conversationId)
            val start = maxOf(0, total - limit.coerceAtLeast(1))
            loadMessageRows(dao, conversationId, start = start, count = total - start, label = "window")
        }

    /** 载入 [before] 之前的 [limit] 条消息；用于「载入更早消息」。 */
    suspend fun loadMessagesBefore(
        context: Context,
        conversationId: String,
        before: Int,
        limit: Int = MESSAGE_WINDOW_SIZE,
    ): LoadedMessages =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val end = before.coerceAtLeast(0)
            val start = maxOf(0, end - limit.coerceAtLeast(1))
            loadMessageRows(dao, conversationId, start = start, count = end - start, label = "earlier")
        }

    private suspend fun loadMessageRows(
        dao: io.github.mangi.eta.data.db.ConversationDao,
        conversationId: String,
        start: Int,
        count: Int,
        label: String,
        activeRunIds: () -> Set<String> = { emptySet() },
    ): LoadedMessages {
        val startedNs = System.nanoTime()
        val rows = buildList {
            var offset = start
            val end = if (count == Int.MAX_VALUE) Int.MAX_VALUE else start + count
            while (offset < end) {
                val pageLimit = if (end == Int.MAX_VALUE) MESSAGE_LOAD_PAGE_SIZE else minOf(MESSAGE_LOAD_PAGE_SIZE, end - offset)
                val page = dao.messagesPage(conversationId, pageLimit, offset)
                addAll(page)
                if (page.size < pageLimit) break
                offset += page.size
            }
        }
        // 只有库里确实存在「标着 Running」的工具行时才去问运行时谁还在跑，普通载入不多付一次 IPC。
        val activeRuns = if (rows.any { it.hasPendingRunningToolStatus() }) activeRunIds() else emptySet()
        val restored = rows.mapNotNull { it.toMessageOrNull(activeRuns) }
        AndroidAgentLogger.info(
            "msgload $label ${conversationId.take(13)} rows=${rows.size} from=$start " +
                "runs=${activeRuns.size} ms=${(System.nanoTime() - startedNs) / 1_000_000}"
        )
        return LoadedMessages(
            messages = restored,
            unaligned = restored.size != rows.size ||
                !areSortIndexesIncreasing(rows.map { it.sortIndex }),
            windowStart = start,
        )
    }

    /**
     * 单独把某个会话的完整 journal 读进内存。
     *
     * 为什么需要它：启动时为省内存，超过 [JOURNAL_INLINE_LIMIT] 的会话会跳过 journal 还原
     * （见 [AgentChatUiState.journalDeferred]），代价是早期消息不能编辑 / 重新生成。
     * 用户真的要点这些入口时，再把这一段读回来 —— 只在他要用的时候付出这笔内存，
     * 而不是每次启动都为所有大会话买单。
     *
     * **超过 [JOURNAL_REVISION_LIMIT] 时 [JournalLoadResult.journal] 为 null，不尝试还原。**
     * 拼接一段 3108 万字符的 journal 需要约 62 MB 连续堆；真机上这一步实测申请
     * 33,558,536 与 67,117,064 字节都失败（conv-2fbc91d6 的 OOM 记录），直接闪退。
     * 把「无法编辑」变成可预期结果，比让它去撞 OOM 好。
     */
    suspend fun loadJournalResult(
        context: Context,
        conversationId: String,
        limit: Long = JOURNAL_REVISION_LIMIT,
    ): JournalLoadResult =
        withContext(Dispatchers.IO) {
            val startedNs = System.nanoTime()
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            // 只要 journal：contextCheckpoint() 会顺带还原 history，编辑路径用不上。
            val row = dao.contextCheckpointRow(conversationId)
                ?: return@withContext JournalLoadResult(
                    journal = null,
                    referencedChars = 0,
                    tooLarge = false,
                )
            val chars = ChunkedTextDao.referencedLength(row.journalJson).toLong()
            if (!journalRevisionAllowed(chars, limit)) {
                AndroidAgentLogger.warn(
                    "journal load rejected ${conversationId.take(13)} chars=$chars limit=$limit"
                )
                return@withContext JournalLoadResult(
                    journal = null,
                    referencedChars = chars,
                    tooLarge = true,
                )
            }
            val restored = dao.restoreCheckpointJournalOnly(row)
            val journal = AgentConversationCodec.decodeTranscript(restored.journalJson)
            AndroidAgentLogger.info(
                "journal load ${conversationId.take(13)} rows=${journal.size} chars=$chars " +
                    "ms=${(System.nanoTime() - startedNs) / 1_000_000}"
            )
            JournalLoadResult(journal = journal, referencedChars = chars, tooLarge = false)
        }

    /**
     * [loadJournalResult] 的薄包装：超限或不存在时返回空表。
     *
     * 需要区分「没有历史」「超过本机上限」「已经载入」时直接调用 [loadJournalResult]，
     * 否则界面只能一律提示「载入失败」，用户不知道该重试还是该新建会话。
     */
    suspend fun loadJournal(
        context: Context,
        conversationId: String,
    ): List<AgentModelClient.ConversationMessage> =
        loadJournalResult(context, conversationId).journal.orEmpty()

    /**
     * 按需把某个会话的 history（模型上下文投影）读回来。
     *
     * 启动时只还原当前会话；非当前会话的 history 留成 deferred，切过去/发送前再读。
     * 只还原 history，不碰 journal —— journal 的按需读取走 [loadJournal]。
     */
    suspend fun loadHistory(
        context: Context,
        conversationId: String,
    ): List<AgentModelClient.ConversationMessage> =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val row = dao.contextCheckpointRow(conversationId) ?: return@withContext emptyList()
            val restored = dao.restoreCheckpointWithoutJournal(row)
            AgentConversationCodec.decodeTranscript(restored.historyJson)
        }

    /**
     * 按需补 journal：只有体积在内存预算内的会话才读回来；超过上限返回 null，继续保持 deferred。
     *
     * 为什么需要：启动时非当前会话的 journal 全部 deferred（省内存），切回来时若不补，
     * 新一轮发送会把空的 journal 保持下去，完整脱敏历史就缺了这几轮。小/中会话在切换时
     * 补回内存，既恢复原有行为，又只为一个会话付内存。
     */
    suspend fun loadJournalIfWithinLimit(
        context: Context,
        conversationId: String,
        limit: Int = JOURNAL_INLINE_LIMIT,
    ): List<AgentModelClient.ConversationMessage>? =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val row = dao.contextCheckpointRow(conversationId) ?: return@withContext emptyList()
            if (ChunkedTextDao.referencedLength(row.journalJson) > limit) return@withContext null
            val restored = dao.restoreCheckpointJournalOnly(row)
            AgentConversationCodec.decodeTranscript(restored.journalJson)
        }

    /**
     * 全库内容搜索（含历史尚未载入内存的会话）。
     *
     * 只走 SQL：单个会话的正文可能上千万字符，为了搜索把它们读进内存会把主线程拖死。
     * 返回 会话 id -> 命中条数；查询里已包含 agent_text_chunks（超长正文分块存放）。
     */
    suspend fun searchConversationContent(context: Context, query: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val like = "%" + escapeLikePattern(query) + "%"
            EtaDatabase.get(context.applicationContext).conversationDao()
                .searchConversationHits(like)
                .associate { hit -> hit.conversationId to hit.matchCount }
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ) = AgentConversationPersistence().save(
        context, Snapshot(selectedConversationId, conversationsById, titles, updatedAt),
    )

    internal suspend fun saveChanges(
        context: Context,
        selectedConversationId: String?,
        current: Map<String, AgentConversationPersistence.Content>,
        previous: Map<String, AgentConversationPersistence.Content>,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                var phase = "read_metadata"
                try {
                    val database = EtaDatabase.get(appContext)
                    val dao = database.conversationDao()
                    val deletedConversationIds = mutableListOf<String>()
                    database.withTransaction {
                        val stored = dao.conversationMetadataRows().associateBy { it.id }
                        phase = "delete_conversations"
                        deletedConversationIds += stored.keys - current.keys
                        deletedConversationIds.forEach { dao.deleteConversationCascade(it) }
                        for ((id, state) in current) {
                            val old = previous[id].takeIf { id in stored }
                            if (old == null || !state.sameMetadata(old)) {
                                phase = "encode_metadata"
                                val row = ConversationEntity(
                                    id = id, title = state.title,
                                    thinkingEnabled = state.reasoningEffort.enablesReasoning,
                                    reasoningEffort = state.reasoningEffort.wireValue,
                                    appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                                    roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty(),
                                    revisionsJson = if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages),
                                    createdAt = stored[id]?.createdAt ?: state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                    updatedAt = state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                    modelId = state.modelId,
                                    pinned = state.isPinned,
                                )
                                phase = "write_metadata"
                                dao.insertConversations(listOf(row))
                            }
                            // history/journal 没载入内存时既不比较也不写：此刻它们是空表，
                            // 写进去会把库里的完整上下文/脱敏历史覆盖成 []（见对应 deferred 标记）。
                            val historyWritable = historyWritableFor(state, old)
                            val historyChanged = historyWritable && (old == null || state.history != old.history)
                            val journalWritable = journalWritableFor(state, old)
                            val journalChanged = journalWritable && (old == null || state.journal != old.journal)
                            if (old == null || historyChanged || journalChanged) {
                                val missing = dao.contextCheckpointRow(id) == null
                                phase = "encode_history"
                                val history = if (historyWritable && (missing || old == null || historyChanged)) {
                                    AgentConversationCodec.encodeConversationCheckpoint(state.history)
                                } else null
                                phase = "encode_journal"
                                val journal = if (journalShouldBeEncoded(journalWritable, missing, old == null, journalChanged)) {
                                    AgentConversationCodec.encodeTranscriptForStorage(state.journal)
                                } else null
                                phase = "write_checkpoint"
                                dao.updateContextCheckpoint(id, history, journal)
                            }
                            // 消息没载入内存时整段跳过：此刻 messages 是空表，
                            // 拿它做前缀比较会把库里整段历史删掉（同 journalDeferred 的处理原则）。
                            if (!state.messagesDeferred) {
                                // 消息可能是「最近 N 条」的窗口（messagesWindowStart > 0）：
                                // 只对两条内存列表重叠的尾部做 diff，绝不删除窗口之前的历史。
                                val oldMessages = old?.messages.orEmpty()
                                val oldStart = old?.messagesWindowStart ?: 0
                                val newStart = state.messagesWindowStart
                                val overlapStart = maxOf(newStart, oldStart)
                                val oldTail = if (overlapStart <= oldStart) {
                                    oldMessages
                                } else {
                                    oldMessages.drop(minOf(oldMessages.size, overlapStart - oldStart))
                                }
                                val newTail = if (overlapStart <= newStart) {
                                    state.messages
                                } else {
                                    state.messages.drop(minOf(state.messages.size, overlapStart - newStart))
                                }
                                var prefix = 0
                                while (prefix < minOf(oldTail.size, newTail.size)) {
                                    val before = oldTail[prefix]
                                    val after = newTail[prefix]
                                    val dbIndex = overlapStart + prefix
                                    if (before != after && before.toEntityOrNull(id, dbIndex) != after.toEntityOrNull(id, dbIndex)) break
                                    prefix++
                                }
                                if (old == null || prefix != oldTail.size || prefix != newTail.size) {
                                    // sort_index 不是行号：历史被裁剪过的会话，库里只剩尾部（首行可能从 12328 起）。
                                    // 以前直接拿 `overlapStart + prefix` 当 sort_index 删除，SQL 又是
                                    // `sort_index >= :from`，于是把窗口之前的整段历史一起删了 ——
                                    // 表现为「切走再回来 / 重启后消息越来越少」。这里按行偏移回查真实 sort_index。
                                    val rowOffset = overlapStart + prefix
                                    val dbFrom = resolveMessageRewindStart(
                                        dao.messageSortIndexAt(id, rowOffset),
                                        dao.messageMaxSortIndex(id),
                                    )
                                    phase = "delete_message_suffix"
                                    dao.deleteMessagesFrom(id, dbFrom)
                                    // 一次编码完、一次插入：以前是逐条 insert（每条一次事务内的
                                    // 分块文本写入），大会话重写时会把主线程之外的时间拉得很长。
                                    phase = "encode_message"
                                    val rows = newTail.drop(prefix).mapIndexedNotNull { offset, message ->
                                        message.toEntityOrNull(id, dbFrom + offset)
                                    }
                                    phase = "write_message"
                                    dao.insertMessages(rows)
                                }
                            }
                        }
                        phase = "write_selection"
                        val selected = selectedConversationId?.takeIf { it in current }
                            ?: current.maxByOrNull { it.value.updatedAt }?.key
                        if (selected == null) dao.deleteState()
                        else dao.insertState(ConversationStateEntity(selectedConversationId = selected))
                    }
                    // 文件系统清理放在事务外：删目录可能很慢，不能占着数据库事务。
                    deletedConversationIds.forEach { conversationId ->
                        ChatNoticeDismissalPrefs.clearScope(appContext, conversationId)
                        SessionWorkspaceStore.clear(appContext, conversationId)
                        TerminalPrivateStorage.deleteSessionWorkspace(appContext.filesDir, conversationId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: phase=$phase type=${failure.safeLogType()}\n${failure.safeStackTrace()}",
                    )
                    throw failure
                }
            }
        }
    }

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        val unaligned = mutableSetOf<String>()

        // 只把「当前会话」的消息读进内存：其余会话保留元数据 + 最后一条消息做预览，
        // 启动时不再为看不见的会话付出几十 MB 堆和成百上千次分页查询（见 messagesDeferred）。
        val selected = dao.state()?.selectedConversationId
            ?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.first().id

        // 先用「存储引用里写着的长度」决定还原哪些会话，再逐会话还原：
        // 还原要一次性拼出整段字符串，是启动时最大的一笔内存开销（见 JOURNAL_INLINE_TOTAL_LIMIT）。
        val journalStartNs = System.nanoTime()
        // 进程级 CPU 时间（utime+stime，包含所有线程）。
        // 不能用线程级时钟（Debug.threadCpuTimeNanos）：这段里要跨 Room 的协程调度器，
        // 续体可能跑在另一条线程上，算出来的差值会变成负数（实测 cpu=-4）。
        val cpuStartMs = Process.getElapsedCpuTime()

        // 启动耗时的分段账：哪一段最贵，日志里要能直接看到，否则只能靠猜。
        // `journal boot ... ms=` 是整段（含下面所有分段 + 消息读取），分段之和应接近它。
        //
        // 为什么还要「同一段再跑一次」（仅 debug 构建）：本机同一份数据、同一条 SQL，
        // preview 这一段有时 142ms、有时 512ms，差 3.5 倍。只测一次永远分不清是
        // 「页面缓存/磁盘冷」还是「这段时间线程被抢占/GC」；冷热两次之差能把两者分开。
        val bootSegmentsMs = linkedMapOf<String, Long>()

        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val startNs = System.nanoTime()
            val value = block()
            bootSegmentsMs[name] = (bootSegmentsMs[name] ?: 0) + (System.nanoTime() - startNs) / 1_000_000
            return value
        }

        // 只有「当前会话」会被内联，非当前会话一律 deferred（见下面的 journalDeferred）。
        // 所以长度只读它一个：这里以前会把所有会话的 journal 字段读出来（本机 19 行、
        // 合计 9.6M 字符），只为算一个「计划内联 7 个」但从未生效的决定（inline 恒为 0）。
        val selectedJournalLength = timed("selJour") {
            dao.journalLengthRow(selected)?.let { ChunkedTextDao.referencedLength(it.journalJson) } ?: 0
        }
        val journalLengths = mapOf(selected to selectedJournalLength)
        val journalPlan = planJournalInlining(
            candidates = listOfNotNull(
                conversations.firstOrNull { it.id == selected }
                    ?.let { JournalCandidate(it.id, selectedJournalLength, it.updatedAt) },
            ),
            selectedId = selected,
        )

        // 预览只需要每个会话的最后一条消息。以前在循环里逐会话查（本机 19 次、约 220 ms），
        // 现在合成一次批量查询：启动时少 18 次查询往返。
        val previewTargets = conversations.map { it.id }.filter { it != selected }
        suspend fun loadPreviewMessages(): Map<String, AgentChatMessageUi> =
            if (previewTargets.isEmpty()) {
                emptyMap()
            } else {
                dao.lastMessagesFor(previewTargets)
                    .mapNotNull { row -> row.toMessageOrNull()?.let { message -> row.conversationId to message } }
                    .toMap()
            }

        val previewMessages = timed("preview") { loadPreviewMessages() }
        if (BuildConfig.DEBUG) {
            // 冷/热对照：紧接着把同一条查询再跑一次（结果丢掉）。
            // 冷热之差 ≈ 磁盘/页面缓存；两次都慢 ≈ SQL 本身或线程被抢占。
            // 只在 debug 构建里做：release 不该为诊断多付一次启动查询。
            timed("preview2") { loadPreviewMessages() }
        }

        conversations.forEach { conversation ->
            val isSelected = conversation.id == selected
            val selStartNs = System.nanoTime()
            // 大会话只把最近 MESSAGE_WINDOW_SIZE 条放进 UI；更早的仍在库里，滚动到顶再按需载入。
            // history 为空的旧数据不做窗口，否则 toLegacyHistory() 只能看到尾部。
            // 只给「当前会话」读 checkpoint：`SELECT *` 会把 journal/history 两段整串搬进内存，
            // 而非当前会话的这两段根本不会被使用（journal 按 isSelected 取、history 走 deferred）。
            // 真机实测（19 个会话、journal 合计 9.6M 字符）：省掉的是启动时最贵的一笔读取。
            val checkpointRow = timed("ckptQuery") {
                if (isSelected) dao.contextCheckpointRow(conversation.id) else null
            }
            val hasStoredHistory = checkpointRow?.historyJson
                ?.let { ChunkedTextDao.referencedLength(it) > 2 } == true
            val totalMessages = conversation.messageCount
            val windowStart = if (isSelected && totalMessages > MESSAGE_WINDOW_THRESHOLD && hasStoredHistory) {
                maxOf(0, totalMessages - MESSAGE_WINDOW_SIZE)
            } else {
                0
            }
            val (messages, restored) = timed("msgs") {
                val rows: List<ConversationMessageEntity> = if (isSelected) {
                    buildList {
                        var offset = windowStart
                        while (true) {
                            val page = dao.messagesPage(conversation.id, MESSAGE_LOAD_PAGE_SIZE, offset)
                            addAll(page)
                            if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                            offset += page.size
                        }
                    }
                } else {
                    emptyList()
                }
                val activeRuns = if (rows.any { it.hasPendingRunningToolStatus() }) {
                    queryActiveRunIds(context, emptySet())
                } else {
                    emptySet()
                }
                rows to rows.mapNotNull { it.toMessageOrNull(activeRuns) }
            }
            if (isSelected) {
                AndroidAgentLogger.infoPersisted(
                    "msgload boot ${conversation.id.take(13)} rows=${messages.size} from=$windowStart " +
                        "total=$totalMessages ms=${(System.nanoTime() - selStartNs) / 1_000_000}"
                )
            }
            if (isSelected &&
                (restored.size != messages.size ||
                    !areSortIndexesIncreasing(messages.map { it.sortIndex }))
            ) {
                unaligned += conversation.id
            }
            // 非当前会话的 history/journal 都不还原：它们不参与模型请求，切过去时再按需读。
            // 当前会话的 journal 仍按上面的单会话上限 + 合计预算决定。
            val historyDeferred = !isSelected
            val journalDeferred = !isSelected || !journalPlan.isInline(conversation.id)
            val checkpoint = timed("ckptRestore") {
                when {
                    checkpointRow == null -> null
                    // 这里 checkpointRow 非空就一定 isSelected（不选中的会话上面直接置空）。
                    journalDeferred -> dao.restoreCheckpointWithoutJournal(checkpointRow)
                    else -> dao.restoreCheckpoint(checkpointRow)
                }
            }
            val roleplayPair = timed("roleplay") {
                val binding = conversation.roleplayJson.takeIf(String::isNotBlank)
                    ?.let { json.decodeFromString<RoleplayBinding>(it) }
                val revisions = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState()
                binding to revisions
            }
            val roleplay = roleplayPair.first
            val roleplayMessages = roleplayPair.second
            val previewMessage = if (isSelected) null else previewMessages[conversation.id]
            // 两次解码以前被并进「其它」里，看不出谁在吃时间：现在各自记账。
            val decodedJournal = timed("journalDecode") {
                if (isSelected) AgentConversationCodec.decodeTranscript(checkpoint?.journalJson) else emptyList()
            }
            val decodedHistory = timed("historyDecode") {
                if (historyDeferred) {
                    emptyList()
                } else {
                    AgentConversationCodec.decodeTranscript(checkpoint?.historyJson).ifEmpty {
                        messages.toLegacyHistory()
                    }
                }
            }
            val appliedRunIds = timed("runs") { conversation.appliedRuntimeRunIdsJson.toStringList() }
            states[conversation.id] = AgentChatHomeUiState(
                roleplay = roleplay,
                roleplayMessages = roleplayMessages,
                journal = decodedJournal,
                journalDeferred = journalDeferred,
                messages = restored,
                messagesDeferred = !isSelected,
                messagesWindowStart = if (isSelected) windowStart else 0,
                previewMessage = previewMessage,
                history = decodedHistory,
                historyDeferred = historyDeferred,
                appliedRuntimeRunIds = appliedRunIds,
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
                modelId = conversation.modelId,
                isPinned = conversation.pinned,
            ).let(RoleplayConversationReducer::decorate)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        // 启动时这笔开销此前没有任何日志：界面卡一下、内存高一点，都归不了因。
        // `ms=` 是墙钟总时长，`cpu=` 是本线程真正在跑的 CPU 时间；两者差得远说明时间花在
        // 等待（磁盘/锁/调度）而不是计算上。`seg=` 按段汇总，各项之和应接近 `ms=`。
        // `preview2` 只在 debug 构建里有：把同一批页面读第二遍，冷热之差就是 IO 的代价。
        AndroidAgentLogger.infoPersisted(
            "journal boot sessions=${conversations.size} inline=${states.count { !it.value.journalDeferred }} " +
                "planInline=${journalPlan.count(JournalInlineDecision.INLINE)} chars=${journalPlan.inlineChars} " +
                "tooLarge=${journalPlan.count(JournalInlineDecision.TOO_LARGE)} " +
                "overBudget=${journalPlan.count(JournalInlineDecision.OVER_BUDGET)} " +
                "ms=${(System.nanoTime() - journalStartNs) / 1_000_000} " +
                "cpu=${Process.getElapsedCpuTime() - cpuStartMs} " +
                "seg=" + bootSegmentsMs.entries.joinToString(",") { "${it.key}:${it.value}" },
        )
        AndroidAgentLogger.infoPersisted(
            "history boot sessions=${conversations.size} " +
                "deferred=${states.count { it.value.historyDeferred }} " +
                "loaded=${states.count { !it.value.historyDeferred }} " +
                "roles=${states.count { it.value.roleplay != null }}",
        )
        if (journalPlan.count(JournalInlineDecision.OVER_BUDGET) > 0) {
            AndroidAgentLogger.info(
                "journal boot over-budget: " + conversations
                    .filter { journalPlan.decisions[it.id] == JournalInlineDecision.OVER_BUDGET }
                    .joinToString(", ") { "${it.id.take(13)}:${journalLengths[it.id] ?: 0}" },
            )
        }

        return Snapshot(
            selectedConversationId = selected,
            messageCounts = conversations.associate { it.id to it.messageCount },
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            unalignedConversationIds = unaligned,
        )
    }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    internal fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = images.toJsonArrayString(),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                        promptTokens = usage?.promptTokens,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                contextTokens = contextTokens,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
                // 首字与解码耗时必须跟着落库：只存在内存里的话，
                // 切一次会话就丢了，卡片上的速度会“凭空消失”。
                firstTokenMs = firstTokenMs,
                decodeMs = decodeMs,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                imageCount = imageCount,
                // 0 在库里表示「旧数据没记录过」，不能写入一个假的当前时间。
                startedAtMs = startedAtMs ?: 0L,
                progressLine = progressLine,
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            else -> null
        }

    internal fun ConversationMessageEntity.toMessageOrNull(
        activeRunIds: Set<String> = emptySet(),
    ): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> UserMessageUi(
                id = id,
                content = content,
                images = imagesJson.toStringList(),
                isEdited = isEdited,
            )

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    promptTokens = promptTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                    contextTokens = contextTokens,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                firstTokenMs = firstTokenMs,
                decodeMs = decodeMs,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = ToolStatusRestore.resolve(toolStatus, id, activeRunIds),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
                startedAtMs = startedAtMs.takeIf { it > 0L },
                progressLine = progressLine,
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            else -> null
        }

    /** 库里有没有「标着 Running」的工具行：只有存在时才值得去问运行时谁还在跑。 */
    private fun ConversationMessageEntity.hasPendingRunningToolStatus(): Boolean =
        type == TYPE_TOOL && toolStatus == ToolActivityStatusUi.Running.name

    /**
     * 仍然活跃的 run：运行时回报的主 run，加上界面自己发起的那一个（[extraRunIds]）。
     *
     * 只在确有 Running 行时才被调用 —— 普通会话加载不会因为这一层多付一次 IPC。
     */
    private fun queryActiveRunIds(context: Context, extraRunIds: Set<String>): Set<String> {
        val fromRuntime = runCatching {
            when (
                val query = AgentRuntimeClient(context.applicationContext, AndroidAgentLogger).queryActiveRun()
            ) {
                is AgentRuntimeClient.ActiveRunQuery.Known -> setOfNotNull(query.runId)
                AgentRuntimeClient.ActiveRunQuery.Unavailable -> emptySet()
            }
        }.getOrDefault(emptySet())
        return fromRuntime + extraRunIds.filter(String::isNotBlank)
    }

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128

    /**
     * journal 超过这个字符数就不在启动时还原，改成「需要时再取」。
     *
     * 实测（PKX110 / Android 16，2026-10-20）：用户四个会话的 journal 合计 4,830 万字符
     * （其中最大的一个 3,109 万字符 ≈ 62 MB UTF-16），而 Eta 进程 Dalvik 堆只用了
     * Alloc 145,497 KB / Size 170,073 KB —— 也就是说**启动时把完整历史拼回内存**
     * 就是「会话一长就闪退」的直接原因。journal 在界面上完全不显示，
     * 只有编辑/重试历史消息时才用得到，所以先不载入是安全的。
     *
     * 阈值取 400 万字符（约 8 MB UTF-16），依据是本机真实库的分布：
     * 四个会话的 journal 分别是 225 万 / 279 万 / 1,217 万 / 3,109 万字符，
     * 而 history（模型上下文）合计只有 95 万 —— 也就是说内存几乎全被 journal 占了。
     * 400 万这条线保住了「中等会话仍可编辑历史消息」，同时把两个真正的大会话挡在内存外。
     * 注释里的数字来自 2026-10-20 的实测，换设备/长期使用后应重新核对。
     */
    /**
     * 这次状态里的 journal 能不能写回库。
     *
     * 两条都不满足就不写：本次没载入（空表），或上次就没载入（基线也是空表）。
     * 合起来看，才能保证「库里那份完整历史」不会被任何一次空表覆盖。
     */
    internal fun journalWritableFor(
        state: AgentConversationPersistence.Content,
        old: AgentConversationPersistence.Content?,
    ): Boolean = !state.journalDeferred && old?.journalDeferred != true

    /** 这次状态里的 history 能不能写回库；语义与 [journalWritableFor] 相同。 */
    internal fun historyWritableFor(
        state: AgentConversationPersistence.Content,
        old: AgentConversationPersistence.Content?,
    ): Boolean = !state.historyDeferred && old?.historyDeferred != true

    /**
     * journal 是否应该编码写库。
     *
     * 三个条件必须同时成立：可写、库里有可比的基线（或本来就是新会话）、内容真的变了。
     * **基线缺失不能绕过可写性** —— 这是真机上会丢数据的那条路径：整包保存时基线为空，
     * 一个 deferred 会话的空 journal 会被当作「新内容」写进去，库里的完整历史变成 `[]`。
     */
    internal fun journalShouldBeEncoded(
        journalWritable: Boolean,
        checkpointMissing: Boolean,
        baselineMissing: Boolean,
        journalChanged: Boolean,
    ): Boolean = journalWritable && (checkpointMissing || baselineMissing || journalChanged)

    internal const val JOURNAL_INLINE_LIMIT = 4_000_000
    /** 大会话在 UI 里只保留最近这么多条消息；更早的按需载入。 */
    internal const val MESSAGE_WINDOW_SIZE = 500
    /** 超过这个条数才启用消息窗口，避免小会话多一次分页。 */
    internal const val MESSAGE_WINDOW_THRESHOLD = 800
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}

/**
 * 把用户输入转成 LIKE 字面量：% _ \ 三个元字符前加反斜杠。
 *
 * 不转义的话，搜「100%」会变成「以 100 开头的任意串」，搜「_」会命中整个库 ——
 * 界面上看起来像「搜什么都能搜到」，实际上是把结果集放大到了全部。
 * 纯函数，单独拿出来是为了能在 JVM 单测里直接验证（Robolectric 的 SQLite 只有 x86_64）。
 */
internal fun escapeLikePattern(query: String): String = buildString(query.length + 8) {
    for (c in query) {
        if (c == '%' || c == '_' || c == '\\') append('\\')
        append(c)
    }
}

/**
 * 启动时把多少个会话的 journal 还原进内存的**合计**预算（UTF-16 字符）。
 *
 * 单会话上限（[AgentConversationStore.JOURNAL_INLINE_LIMIT]）只管住单个会话，管不住总和。
 * 2026-10-21 真机库副本实测：8 个会话里 4 个超过单会话上限（31.1M / 16.7M / 12.2M / 7.0M），
 * 剩下 3 个都不到 4M（3.00M + 2.98M + 2.79M），但**合计 8.78M**——已经超过单会话上限两倍多。
 * 当初「会话一长就闪退」的现场是 4 个会话合计 4,830 万字符装进 Dalvik 堆（Alloc 145 MB /
 * 上限 152 MB）；只靠单会话上限，用户再多几个 3.9M 的会话就会回到同一条路上。
 *
 * 所以按合计再设一道保护：超预算时把**较旧**的会话留成 deferred。
 * 12M 的依据：当前实测合计 8.78M 留有余量（现状不受影响），
 * 而最坏情况（8 个会话都贴着 4M = 31M 字符）会被压到 12M 以内。
 */
internal const val JOURNAL_INLINE_TOTAL_LIMIT = 12_000_000L

/**
 * [AgentConversationStore.loadJournalResult] 的结果。
 *
 * 三种情况必须分开：没有历史、超过本机上限（[tooLarge]）、已载入。
 * 混成一种，界面就只能提示「载入失败」，用户不知道该重试还是该新建会话。
 */
internal data class JournalLoadResult(
    val journal: List<AgentModelClient.ConversationMessage>?,
    val referencedChars: Long,
    val tooLarge: Boolean,
)

/**
 * 用户主动编辑 / 重新生成时，一次性还原 journal 的**硬上限**（字符）。
 *
 * 与 [AgentConversationStore.JOURNAL_INLINE_LIMIT]（启动时的软预算，超了就懒加载）不同：
 * 这里已经到「用户点了按钮、必须真正拼出整段字符串」的时刻，拼不出来就是闪退。
 *
 * 定在 8M 的依据（PKX110 / Android 16，2026-10-23 实测）：
 * - 本机最大的 journal 是 **31,087,226 字符**（`@eta:chunks:v1:1898:31087226`，1898 个 16 KB 分块）；
 * - 拼它需要约 62 MB 连续堆；真机上先后申请 **33,558,536** 与 **67,117,064** 字节都失败
 *   （前者是 2026-10-20 15:02 的启动崩溃，后者是同会话里多次记录下来的运行失败），
 *   进程堆上限 268,435,456 字节；
 * - 8M 字符约 16 MB UTF-16，加上 JSON 解码后的对象开销仍在安全区；本机 6 个大会话里
 *   31.1M / 16.7M / 12.2M 会被挡在外面，3.0M / 2.9M / 1.7M 仍可正常编辑。
 *
 * 换设备或长期使用后应重新核对：判据是「解码后的堆峰值是否仍留有一倍余量」，不是这个数字本身。
 */
internal const val JOURNAL_REVISION_LIMIT = 8_000_000L

/** [chars] 是否落在「可以尝试一次整段还原」的范围内。负值（异常数据）一律拒绝。 */
internal fun journalRevisionAllowed(chars: Long, limit: Long = JOURNAL_REVISION_LIMIT): Boolean =
    chars in 0..limit

/** 一个会话的 journal 在启动时值不值得还原进内存。 */
internal enum class JournalInlineDecision {
    /** 还原进内存：可查看、可编辑、可重新生成。 */
    INLINE,

    /** 超过单会话上限，不还原。 */
    TOO_LARGE,

    /** 合计预算里排不上，不还原。 */
    OVER_BUDGET,
}

/** 参与启动决策的单个会话：只要长度与新旧，不读内容。 */
internal data class JournalCandidate(val id: String, val length: Int, val updatedAt: Long)

internal data class JournalPlan(
    val decisions: Map<String, JournalInlineDecision>,
    val inlineChars: Long,
) {
    fun isInline(id: String): Boolean = decisions[id] == JournalInlineDecision.INLINE

    fun count(decision: JournalInlineDecision): Int = decisions.count { it.value == decision }
}

/**
 * 决定启动时还原哪些会话的 journal。
 *
 * 顺序：当前会话优先，其余按 updatedAt 从新到旧。超预算就**跳过这个会话继续试更小的**——
 * 一个 11M 的会话不该把后面所有 200K 的小会话一起挡在门外。
 *
 * 长度未知（存储引用解析不出来，[ChunkedTextDao.UNKNOWN_LENGTH]）按「超过上限」处理：
 * 猜小会把本该 deferred 的 journal 铺满内存，那是崩溃方向。
 */
internal fun planJournalInlining(
    candidates: List<JournalCandidate>,
    selectedId: String?,
    perConversationLimit: Int = AgentConversationStore.JOURNAL_INLINE_LIMIT,
    totalLimit: Long = JOURNAL_INLINE_TOTAL_LIMIT,
): JournalPlan {
    val order = candidates.sortedWith(
        compareByDescending<JournalCandidate> { it.id == selectedId }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id },
    )
    val decisions = LinkedHashMap<String, JournalInlineDecision>(candidates.size)
    var inlineChars = 0L
    order.forEach { candidate ->
        val decision = when {
            candidate.length > perConversationLimit -> JournalInlineDecision.TOO_LARGE
            inlineChars + candidate.length > totalLimit -> JournalInlineDecision.OVER_BUDGET
            else -> JournalInlineDecision.INLINE
        }
        if (decision == JournalInlineDecision.INLINE) inlineChars += candidate.length
        decisions[candidate.id] = decision
    }
    return JournalPlan(decisions, inlineChars)
}
