package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String = "[]",
    @ColumnInfo(name = "roleplay_json", defaultValue = "''") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json", defaultValue = "''") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 会话最近使用的本地 Model.id；null 表示跟随默认模型。 */
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    /** 是否置顶（列表排序优先，且单独分组）。 */
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
)

internal data class ConversationMetadata(
    val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String,
    @ColumnInfo(name = "roleplay_json") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    /**
     * 消息条数（列表查询用子查询算出来，不是实体列）。
     *
     * 为什么需要：会话列表原本只有标题和最后一条预览，看不出「这个会话有多重」。
     * 用户确实会同时留着 8,000 条和 80 条的会话，前者打开慢、历史按需载入，
     * 列表里给个数字比点进去才发现要直观。
     */
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
)

@Serializable
@Entity(
    tableName = "conversation_context_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "journal_json", defaultValue = "''") val journalJson: String = "",
)

@Serializable
@Entity(tableName = "conversation_state")
internal data class ConversationStateEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "selected_conversation_id") val selectedConversationId: String,
) {
    companion object {
        const val SINGLETON_ID = "main"
    }
}

@Serializable
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "sort_index"], unique = true),
    ],
)
internal data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    val type: String,
    val content: String,
    @ColumnInfo(name = "images_json") val imagesJson: String = "[]",
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Boolean = false,
    @ColumnInfo(name = "render_markdown") val renderMarkdown: Boolean? = null,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "elapsed_seconds") val elapsedSeconds: Int? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_status") val toolStatus: String? = null,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String? = null,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "tools_json") val toolsJson: String = "[]",
    /**
     * 工具开始执行的墙上时间（毫秒）；0 表示旧数据没有记录。
     *
     * 用途：切换会话 / 重启后重新渲染时，运行中的工具卡片要能算出「已经跑了多久」，
     * 而不是从 0 重新计数（那会被当成「又重启了一次」）。
     */
    @ColumnInfo(name = "started_at", defaultValue = "0") val startedAtMs: Long = 0L,
    /**
     * 长时工具（ACP 委派）最后一条执行中进度。
     *
     * 不落盘时，切走会话 / 进程重建后卡片只剩标题 —— 用户看到的就是
     * 「委派卡住、什么也看不到」。落下来至少能看到代理最后在做什么。
     */
    @ColumnInfo(name = "progress_line") val progressLine: String? = null,
)
