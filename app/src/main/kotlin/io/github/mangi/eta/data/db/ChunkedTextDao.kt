package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.mangi.eta.core.AndroidAgentLogger

/** 每行有界，文档总长不限；避免完整历史占用单个 CursorWindow 行。 */
@Entity(tableName = "agent_text_chunks", primaryKeys = ["owner_table", "owner_id", "field", "chunk_index"])
internal data class AgentTextChunkEntity(
    @ColumnInfo(name = "owner_table") val ownerTable: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val field: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
)

/** 由业务 DAO 在同一 Room 事务内写入主记录与分块，不暴露存储引用给领域层。 */
internal interface ChunkedTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextChunk(chunk: AgentTextChunkEntity)

    @Query("DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field")
    suspend fun deleteTextChunks(table: String, owner: String, field: String)

    @Query(
        "DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field " +
            "AND chunk_index >= :fromIndex"
    )
    suspend fun deleteTextChunksFrom(table: String, owner: String, field: String, fromIndex: Int)

    @Query("SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field ORDER BY chunk_index LIMIT :limit OFFSET :offset")
    suspend fun textChunks(table: String, owner: String, field: String, limit: Int, offset: Int): List<AgentTextChunkEntity>

    @Query(
        "SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field " +
            "AND chunk_index >= :fromIndex ORDER BY chunk_index LIMIT :limit"
    )
    suspend fun textChunksFrom(
        table: String,
        owner: String,
        field: String,
        fromIndex: Int,
        limit: Int,
    ): List<AgentTextChunkEntity>

    /**
     * 写库前先逐块比对旧内容，只在「同一段文字加了尾巴」时才只重写尾部。
     *
     * 原来是「删光全部分块再逐块重插」：真机上单个会话的 journal 有 3100 万字符、
     * 2026-10-21 实测 4466 个分块，每次保存都整段重写一次，等于每条消息重写几十 MB。
     * 比对会读一遍旧内容（page cache 命中为主），但省掉的是成百上千次
     * 删除 + 插入 + 主键索引更新，写放大显著下降。
     *
     * 安全性：是否复用**完全由实际内容比对决定**，不依赖调用方的承诺；
     * 内容一致时连库都不写（直接返回原引用）。
     */
    suspend fun storeText(
        table: String,
        owner: String,
        field: String,
        text: String,
        stored: String? = null,
    ): String {
        val startedNs = System.nanoTime()
        val lengths = ArrayList<Int>()
        var offset = 0
        var matched = true
        var readFrom = 0
        var chunkCursor = 0
        var mismatchChunkIndex = -1
        var mismatchPrefixEnd = -1
        var mismatchTail = ""
        while (true) {
            val page = textChunksFrom(table, owner, field, readFrom, ChunkedTextLayout.COMPARE_PAGE)
            if (page.isEmpty()) break
            var stop = false
            for (chunk in page) {
                val common = ChunkedTextLayout.commonPrefixLength(text, offset, chunk.content)
                if (common < chunk.content.length) {
                    matched = false
                    mismatchChunkIndex = chunkCursor
                    mismatchPrefixEnd = offset + common
                    mismatchTail = chunk.content.substring(common)
                    stop = true
                    break
                }
                lengths += chunk.content.length
                offset += chunk.content.length
                chunkCursor++
            }
            if (stop) break
            readFrom += page.size
            if (page.size < ChunkedTextLayout.COMPARE_PAGE) break
        }

        // 旧内容在「前缀之后只剩一个 `]`」时，允许从这块开始重写（JSON 数组追加的常态）。
        // 是否真是最后一块看引用里记录的块数：库里的行可能被外部改动，不能只信分块顺序。
        var dropClosingBracket = false
        if (mismatchChunkIndex >= 0) {
            val reference = stored?.takeIf { it.startsWith(REFERENCE_PREFIX) }
            val knownChunks = reference?.let { storedChunkCount(it) } ?: 0
            val knownLength = reference?.let { referencedLength(it) } ?: 0
            dropClosingBracket = knownChunks > 0 &&
                knownLength > 0 &&
                mismatchChunkIndex == knownChunks - 1 &&
                knownLength - mismatchPrefixEnd == 1 &&
                mismatchTail == "]"
        }

        val plan = ChunkedTextLayout.plan(matched, offset, lengths, text.length, dropClosingBracket)
        if (plan is ChunkedTextLayout.Plan.Unchanged) {
            logStore(table, field, text.length, 0, 0, startedNs, "unchanged")
            return ChunkedTextLayout.reference(REFERENCE_PREFIX, plan.chunkCount, plan.length)
        }

        var chunkIndex = 0
        var startOffset = 0
        if (plan is ChunkedTextLayout.Plan.TailWrite) {
            chunkIndex = plan.rewrite.chunkIndex
            startOffset = plan.rewrite.startOffset
            deleteTextChunksFrom(table, owner, field, chunkIndex)
        } else {
            deleteTextChunks(table, owner, field)
            if (text.length <= ChunkedTextLayout.CHUNK_CHARS && !text.startsWith(REFERENCE_PREFIX)) {
                logStore(table, field, text.length, 0, 0, startedNs, "inline")
                return text
            }
        }

        var written = 0
        var cursor = startOffset
        while (cursor < text.length) {
            // 不把 UTF-16 代理对拆到两个 SQLite TEXT 中。
            val end = ChunkedTextLayout.chunkEnd(text, cursor)
            insertTextChunk(AgentTextChunkEntity(table, owner, field, chunkIndex++, text.substring(cursor, end)))
            written++
            cursor = end
        }
        logStore(
            table, field, text.length, written, startOffset, startedNs,
            if (plan is ChunkedTextLayout.Plan.TailWrite) "tail" else "full",
        )
        return ChunkedTextLayout.reference(REFERENCE_PREFIX, chunkIndex, text.length)
    }

    /** 存储引用的分块数；内联文本算 1 块，空文本 0 块。 */
    fun storedChunkCount(stored: String): Int {
        if (stored.isEmpty()) return 0
        if (!stored.startsWith(REFERENCE_PREFIX)) return 1
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        return parts.getOrNull(0)?.toIntOrNull() ?: 0
    }

    /**
     * 按需读取存储文本的分块，**不**把它们拼成一个 String。
     *
     * [restoreText] 会把整段拼起来：真机上单个会话的 journal 有 3100 万字符
     * （6450 条消息、1898 个 16 KB 分块，约 62 MB UTF-16），一次性还原就是 OOM 的直接原因。
     * 分页读历史只需要其中几十 KB，所以这里把「读多少」的决定权交给调用方。
     */
    suspend fun readStoredChunks(
        table: String,
        owner: String,
        field: String,
        stored: String,
        from: Int,
        limit: Int,
    ): List<String> {
        if (limit <= 0 || stored.isEmpty()) return emptyList()
        if (!stored.startsWith(REFERENCE_PREFIX)) {
            return if (from == 0) listOf(stored) else emptyList()
        }
        val count = storedChunkCount(stored)
        if (count <= 0 || from >= count) return emptyList()
        return textChunks(table, owner, field, minOf(limit, count - from), from).map { it.content }
    }

    suspend fun restoreText(table: String, owner: String, field: String, stored: String): String {
        if (!stored.startsWith(REFERENCE_PREFIX)) return stored
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        check(parts.size == 2) { "Invalid history payload reference" }
        val count = parts[0].toInt()
        val length = parts[1].toInt()
        check(count > 0 && length > 0) { "Invalid history payload size" }
        // 预分配避免「扩容 → 整段复制」的峰值：真机上 3108 万字符的 journal 走无参构造会先申请
        // 16.7M、再申请 33.5M 字符（约 67 MB），两次都失败过。按已知长度一次留够；
        // 同时给不可信的超大 length 设上限，避免伪造引用直接把进程撑爆。
        val result = StringBuilder(minOf(length, MAX_PREALLOC_CHARS))
        var offset = 0
        while (offset < count) {
            val page = textChunks(table, owner, field, minOf(32, count - offset), offset)
            check(page.isNotEmpty()) { "History payload chunk missing" }
            page.forEach { chunk ->
                check(chunk.chunkIndex == offset++) { "History payload chunk out of order" }
                result.append(chunk.content)
            }
        }
        check(result.length == length) { "History payload length mismatch" }
        return result.toString()
    }

    companion object {
        const val REFERENCE_PREFIX = "@eta:chunks:v1:"

        /** 无法解析的引用一律当作「极大」，宁可延迟加载也不要一次性铺进内存。 */
        const val UNKNOWN_LENGTH = Int.MAX_VALUE

        /** [restoreText] 预分配的上限：再长就按需扩容，不让一个伪造的 length 直接巨额分配。 */
        private const val MAX_PREALLOC_CHARS = 8_000_000

        /** 低于这个长度不记日志，避免每写一条消息刷一行。 */
        private const val LOG_THRESHOLD_CHARS = 262_144

        private fun logStore(
            table: String,
            field: String,
            length: Int,
            written: Int,
            startOffset: Int,
            startedNs: Long,
            kind: String,
        ) {
            if (length < LOG_THRESHOLD_CHARS) return
            // 打日志失败绝不能影响数据写入：单测里没有 Android Log 实现，Log.i 会抛 "Stub!"；
            // 这里吞掉异常，也让「用真机库副本跑真实 storeText」的测试成为可能。
            runCatching {
                AndroidAgentLogger.info(
                    "chunktext $table.$field kind=$kind chars=$length written=$written " +
                        "from=$startOffset ms=${(System.nanoTime() - startedNs) / 1_000_000}",
                )
            }
        }

        /**
         * 读出外部化文本的**总字符数**，不触碰任何分块内容。
         *
         * 为什么需要它：存储引用本身就写成 `@eta:chunks:v1:<count>:<length>`，
         * 长度是已知的。调用方据此决定「要不要现在还原」——
         * 真机上一个大会话的 journal 有 3100 万字符（约 62 MB UTF-16），
         * 启动时无条件还原就是 OOM 的直接原因（见 AgentConversationStore.loadSnapshot）。
         *
         * 非引用（普通短文本）返回其自身长度；null/空返回 0。
         */
        fun referencedLength(stored: String?): Int {
            if (stored.isNullOrEmpty()) return 0
            if (!stored.startsWith(REFERENCE_PREFIX)) return stored.length
            val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
            return parts.getOrNull(1)?.toIntOrNull() ?: UNKNOWN_LENGTH
        }
    }
}
