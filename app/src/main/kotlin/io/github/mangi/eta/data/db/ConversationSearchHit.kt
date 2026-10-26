package io.github.mangi.eta.data.db

/** 内容搜索命中：会话 id 与该会话里命中关键词的消息条数。 */
internal data class ConversationSearchHit(
    val conversationId: String,
    val matchCount: Int,
)
