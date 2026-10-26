package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    /**
     * 有历史内容尚未载入内存、因而没有参与内容搜索的会话数。
     *
     * 搜索只在内存里的消息上做（见 AgentChatUiState.messagesDeferred）；为 0 时结果完整，
     * 大于 0 时界面必须说明「还有几个会话没被搜到」，不能让它看起来像完整结果。
     */
    val unsearchedConversationCount: Int = 0,
    /**
     * 正在用 SQL 搜全库正文。
     *
     * 与 [unsearchedConversationCount] 不是一回事：这里是「还没出结果」，
     * 那里是「这一轮搜完了，但确实有会话没被覆盖」。混在一起说会误导成「搜索没做」。
     */
    val isSearchingHistory: Boolean = false,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    /** 运行中的 run 是否被用户暂停（会话列表里给出可见标记）。 */
    val isPaused: Boolean = false,
    val characterName: String? = null,
    val characterAvatarPath: String? = null,
    /**
     * 内容搜索命中的消息条数（数据库全文匹配）。
     *
     * 0 表示这个词没在消息正文里命中（标题或预览命中）。界面要靠它解释
     * 「标题看起来不相关，为什么这个会话在结果里」。
     */
    val contentMatchCount: Int = 0,
    /**
     * 该会话的消息条数（来自数据库统计，未载入消息的会话也有值）。
     *
     * 用于在列表里给「重会话」一个可见标记：同样几个会话，8,000 条和 80 条的
     * 打开成本完全不同，但列表原本只显示标题与预览，看不出差别。
     */
    val messageCount: Int = 0,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
