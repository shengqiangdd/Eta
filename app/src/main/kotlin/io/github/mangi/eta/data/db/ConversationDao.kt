package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 启动加载用的轻量投影：只要 journal 的存储引用（`@eta:chunks:v1:块数:长度`）。
 *
 * 为什么不用 [ConversationContextCheckpointEntity]：那会把 history 引用一起读出来，
 * 而 history 小到一定程度是**内联整段 JSON** 存的（真机上单个会话可达 537K 字符）。
 * 启动时为了决定「还原不还原 journal」而多读几百 KB，纯属浪费。
 */
internal data class JournalLengthRow(val conversationId: String, val journalJson: String?)

@Dao
internal interface ConversationDao : ChunkedTextDao {
    @Query(
        "SELECT c.id, c.title, c.thinking_enabled, c.reasoning_effort, " +
            "c.applied_runtime_run_ids_json, c.roleplay_json, c.revisions_json, " +
            "c.created_at, c.updated_at, c.model_id, c.pinned, " +
            "(SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = c.id) AS message_count " +
            "FROM conversations c ORDER BY c.updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT c.id, c.title, c.thinking_enabled, c.reasoning_effort, " +
            "c.applied_runtime_run_ids_json, c.roleplay_json, c.revisions_json, " +
            "c.created_at, c.updated_at, c.model_id, c.pinned, " +
            "(SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = c.id) AS message_count " +
            "FROM conversations c ORDER BY c.updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        restoreConversation(row)
    }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun conversationEntityRowsPage(limit: Int, offset: Int): List<ConversationEntity>

    suspend fun restoreConversation(row: ConversationEntity) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun allMessageRowsPage(limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    /**
     * 只取**一个**会话的 journal 引用。
     *
     * 启动时只有「当前会话」可能被内联（非当前会话一律 deferred），所以没必要把
     * 所有会话的 journal 字段都读出来 —— 那在本机是 19 行、合计 9.6M 字符。
     * （曾经的 `journalLengthRows()` 已删：它读全表大字段，而调用方只要一个会话。）
     */
    @Query("SELECT conversation_id AS conversationId, journal_json AS journalJson FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun journalLengthRow(conversationId: String): JournalLengthRow?

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC LIMIT :limit OFFSET :offset")
    suspend fun contextCheckpointRowsPage(limit: Int, offset: Int): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    /** 只取最后一条消息：会话列表预览用，避免为了显示一行字把整段历史读进来。 */
    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index DESC LIMIT 1")
    suspend fun lastMessageRow(conversationId: String): ConversationMessageEntity?

    @Transaction
    suspend fun lastMessage(conversationId: String): ConversationMessageEntity? =
        lastMessageRow(conversationId)?.let { restoreMessage(it) }

    /**
     * 一次取回多个会话的最后一条消息（会话列表预览用）。
     *
     * 逐会话调用 [lastMessage] 在启动时要付 19 次查询往返（本机实测约 220 ms）；
     * 这里一次查完。
     *
     * 写法必须是「派生表算 MAX 再 JOIN」，不能用相关子查询。两种写法返回的行完全一样
     * （真机 19 个会话逐行比对 0 差异），代价却差 3.6 倍：
     * - 相关子查询版：外层按 `conversation_id` 扫**该会话全部消息**，每条再跑一次
     *   `MAX(sort_index)`（查询计划里是 SEARCH inner_row USING COVERING INDEX，
     *   即每次都重扫一遍），复杂度 O(n²)。3,853 条的会话就是约 740 万次索引项访问，
     *   真机 242MB 库实测中位 34.9 ms。
     * - 派生表 JOIN 版：子查询按 `(conversation_id, sort_index)` 唯一索引扫一遍取
     *   每会话最大索引，外层只做 ≤19 次索引点查，同库实测中位 9.6 ms。
     *
     * 这段是启动期 `journal boot` 里 `preview=` 那一项（真机热启动 51~208 ms 波动），
     * 它随消息总数增长，**不是**磁盘冷读：`preview2`（同一查询再跑一遍）几乎一样慢。
     */
    @Query(
        "SELECT m.* FROM conversation_messages AS m " +
            "JOIN (SELECT conversation_id AS last_cid, MAX(sort_index) AS last_index " +
            "FROM conversation_messages WHERE conversation_id IN (:conversationIds) " +
            "GROUP BY conversation_id) AS last_row " +
            "ON m.conversation_id = last_row.last_cid AND m.sort_index = last_row.last_index"
    )
    suspend fun lastMessageRowsFor(conversationIds: List<String>): List<ConversationMessageEntity>

    @Transaction
    suspend fun lastMessagesFor(conversationIds: List<String>): List<ConversationMessageEntity> {
        if (conversationIds.isEmpty()) return emptyList()
        return lastMessageRowsFor(conversationIds).map { restoreMessage(it) }
    }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    /**
     * 全库内容搜索：返回每个会话的正文命中条数。
     *
     * [like] 由调用方转义（% _ \）并包好通配符。Room 会把 SQL 原样交给 SQLite，
     * 所以 ESCAPE '\' 里的反斜杠在 SQL 中就是单个字符。
     *
     * 匹配范围必须与内存侧 `AgentChatMessageUi.matches` 一致，否则同一句话
     * 「切到别的会话就搜不到」：
     * - 超长正文在 conversation_messages.content 里只是 `@eta:chunks:v1:…` 引用，
     *   真正内容落在 agent_text_chunks，必须一并匹配，否则长回答搜不到。
     * - 反过来，引用行本身（content 只有 `@eta:chunks:v1:<count>:<length>`）不含正文，
     *   不能因为引用里带 `eta` / `chunks` / `v1` 就当作命中：真机 1.9 万条消息里有 203 条长消息
     *   是这种引用，用旧写法搜 `v1` 得 586、搜 `chunks` 得 296，其中 175 条纯粹是引用串本身命中。
     *   这里用 GLOB 精确排除引用行，真正的正文仍由下面的 agent_text_chunks 匹配补上
     *   （实测：`v1` 586 → 449，`chunks` 296 → 121，`%` 与 `opifexa` 不变）。
     * - 工具消息的 tool_name / arguments_summary / result_summary / tools_json 也是正文：
     *   内存侧全部参与匹配，SQL 只搜 content 会漏。真机实测（2026-10-20，词「继续」）
     *   有 542 条 tool 消息只在 result_summary 里命中、content 里没有，
     *   分布在 10 个会话里 —— 也就是说这 10 个会话的命中数被系统性低估。
     *   补上之后同一查询命中从 1,379 涨到 1,926（+40%），耗时 78→85ms，仍在百毫秒级。
     * - system_notice 的展示文案按当前语言解析，不落库；detail 落在 result_summary 里，
     *   已由上面的 result_summary 分支覆盖。
     *
     * images_json 故意不搜：里面是 base64 data URL，与用户可见内容无关（内存侧也不搜它）。
     */
    @Query(
        "SELECT conversation_id AS conversationId, COUNT(*) AS matchCount " +
            "FROM conversation_messages " +
            "WHERE (content NOT GLOB '@eta:chunks:v1:[0-9]*:[0-9]*' AND content LIKE :like ESCAPE '\\') " +
            "OR EXISTS (SELECT 1 FROM agent_text_chunks c " +
            "WHERE c.owner_table = 'conversation_messages' AND c.owner_id = conversation_messages.id " +
            "AND c.field = 'content' AND c.content LIKE :like ESCAPE '\\') " +
            "OR tool_name LIKE :like ESCAPE '\\' " +
            "OR arguments_summary LIKE :like ESCAPE '\\' " +
            "OR result_summary LIKE :like ESCAPE '\\' " +
            "OR tools_json LIKE :like ESCAPE '\\' " +
            "GROUP BY conversation_id"
    )
    suspend fun searchConversationHits(like: String): List<ConversationSearchHit>

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    /**
     * 只还原 history，journal 留空。
     *
     * 用在「journal 太大、先不载入内存」的路径上：history 是模型上下文（真机上单会话最多几十万字符），
     * 必须完整；journal 是完整脱敏历史，只有编辑/重试历史消息时需要，可以按需再取。
     */
    suspend fun restoreCheckpointWithoutJournal(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = "",
    )

    /** 只还原 journal，history 留空；用于切回会话时按需补 journal。 */
    suspend fun restoreCheckpointJournalOnly(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = "",
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText(
                    "conversations", row.id, "runs", row.appliedRuntimeRunIdsJson,
                    stored = row.appliedRuntimeRunIdsJson,
                ),
                roleplayJson = storeText(
                    "conversations", row.id, "roleplay", row.roleplayJson,
                    stored = row.roleplayJson,
                ),
                revisionsJson = storeText(
                    "conversations", row.id, "revisions", row.revisionsJson,
                    stored = row.revisionsJson,
                ),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText(
                    "conversation_messages", row.id, "content", row.content,
                    stored = row.content,
                ),
                imagesJson = storeText(
                    "conversation_messages", row.id, "images", row.imagesJson,
                    stored = row.imagesJson,
                ),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText(
                    "conversation_context_checkpoints", row.conversationId, "history",
                    row.historyJson, stored = row.historyJson,
                ),
                journalJson = storeText(
                    "conversation_context_checkpoints", row.conversationId, "journal",
                    row.journalJson, stored = row.journalJson,
                ),
            ))
        }
    }

    @Transaction
    suspend fun updateContextCheckpoint(conversationId: String, historyJson: String?, journalJson: String?) {
        val previous = contextCheckpointRow(conversationId)
            ?: ConversationContextCheckpointEntity(conversationId, "[]")
        insertContextCheckpointRow(previous.copy(
            historyJson = historyJson?.let {
                storeText(
                    "conversation_context_checkpoints", conversationId, "history", it,
                    stored = previous.historyJson,
                )
            } ?: previous.historyJson,
            journalJson = journalJson?.let {
                storeText(
                    "conversation_context_checkpoints", conversationId, "journal", it,
                    stored = previous.journalJson,
                )
            } ?: previous.journalJson,
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesOfConversation(conversationId: String)

    @Query("DELETE FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun deleteCheckpointOfConversation(conversationId: String)

    /**
     * 删除会话，连同它的消息与上下文快照。
     *
     * 外键**是开着的**：Room 2.8.5 生成的 `EtaDatabase_Impl.onOpen` 会执行
     * `PRAGMA foreign_keys = ON`（2026-10-24 用 dexdump 从装机 APK 的 classes11.dex
     * 读到 `EtaDatabase_Impl$createOpenDelegate$_openDelegate$1.onOpen` 里的
     * `invoke-static ... SQLite.execSQL("PRAGMA foreign_keys = ON")`）。
     *
     * 别拿 sqlite3 打开库副本去查 `PRAGMA foreign_keys` 来判断：那是 sqlite3 自己的
     * 连接，默认 OFF，会得出“Room 不开外键、CASCADE 不生效”的相反结论
     * （2026-10-21 就这么误判过一次，注释一直错到 10-24）。
     *
     * 所以这里显式“先子后父”：级联本来也会删，但显式删除让语义不依赖 PRAGMA
     * 的开关状态；反过来先删父行则完全依赖级联是否生效。
     */
    @Transaction
    suspend fun deleteConversationCascade(conversationId: String) {
        deleteMessagesOfConversation(conversationId)
        deleteCheckpointOfConversation(conversationId)
        deleteConversation(conversationId)
    }

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index >= :fromIndex")
    suspend fun deleteMessagesFrom(conversationId: String, fromIndex: Int)

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
