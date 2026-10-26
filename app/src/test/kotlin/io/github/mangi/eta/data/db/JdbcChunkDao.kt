package io.github.mangi.eta.data.db

import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.runBlocking

/**
 * 测试用的 [ChunkedTextDao] 实现：用 JDBC 直连 SQLite（内存库或磁盘上的真机库副本）。
 *
 * 存在的意义：`storeText` / `restoreText` 是接口里的默认实现，只要实现这几个抽象方法，
 * 就能在普通 JVM 上跑**真实代码路径**，不必起 Robolectric（本机 aarch64 不支持它的 native 运行时）。
 */
internal class JdbcChunkDao(
    url: String = "jdbc:sqlite::memory:",
    private val table: String = "conversation_context_checkpoints",
    private val owner: String = "conv-test",
    private val field: String = "journal",
    createTable: Boolean = true,
) : ChunkedTextDao {

    private val connection = DriverManager.getConnection(url)
    var inserts = 0
    var deletes = 0
    var tailDeletes = 0

    init {
        if (createTable) {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agent_text_chunks(" +
                        "owner_table TEXT NOT NULL, owner_id TEXT NOT NULL, field TEXT NOT NULL, " +
                        "chunk_index INTEGER NOT NULL, content TEXT NOT NULL, " +
                        "PRIMARY KEY(owner_table, owner_id, field, chunk_index))",
                )
            }
        }
    }

    fun close() = connection.close()

    override suspend fun insertTextChunk(chunk: AgentTextChunkEntity) {
        inserts++
        connection.prepareStatement(
            "INSERT OR REPLACE INTO agent_text_chunks(owner_table,owner_id,field,chunk_index,content) " +
                "VALUES(?,?,?,?,?)",
        ).use { statement ->
            statement.setString(1, chunk.ownerTable)
            statement.setString(2, chunk.ownerId)
            statement.setString(3, chunk.field)
            statement.setInt(4, chunk.chunkIndex)
            statement.setString(5, chunk.content)
            statement.executeUpdate()
        }
    }

    override suspend fun deleteTextChunks(table: String, owner: String, field: String) {
        deletes++
        connection.prepareStatement(
            "DELETE FROM agent_text_chunks WHERE owner_table=? AND owner_id=? AND field=?",
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, owner)
            statement.setString(3, field)
            statement.executeUpdate()
        }
    }

    override suspend fun deleteTextChunksFrom(table: String, owner: String, field: String, fromIndex: Int) {
        tailDeletes++
        connection.prepareStatement(
            "DELETE FROM agent_text_chunks WHERE owner_table=? AND owner_id=? AND field=? AND chunk_index>=?",
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, owner)
            statement.setString(3, field)
            statement.setInt(4, fromIndex)
            statement.executeUpdate()
        }
    }

    override suspend fun textChunks(
        table: String,
        owner: String,
        field: String,
        limit: Int,
        offset: Int,
    ): List<AgentTextChunkEntity> = query(
        "SELECT * FROM agent_text_chunks WHERE owner_table=? AND owner_id=? AND field=? " +
            "ORDER BY chunk_index LIMIT ? OFFSET ?",
    ) { statement ->
        statement.setString(1, table)
        statement.setString(2, owner)
        statement.setString(3, field)
        statement.setInt(4, limit)
        statement.setInt(5, offset)
    }

    override suspend fun textChunksFrom(
        table: String,
        owner: String,
        field: String,
        fromIndex: Int,
        limit: Int,
    ): List<AgentTextChunkEntity> = query(
        "SELECT * FROM agent_text_chunks WHERE owner_table=? AND owner_id=? AND field=? " +
            "AND chunk_index>=? ORDER BY chunk_index LIMIT ?",
    ) { statement ->
        statement.setString(1, table)
        statement.setString(2, owner)
        statement.setString(3, field)
        statement.setInt(4, fromIndex)
        statement.setInt(5, limit)
    }

    private fun query(
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): List<AgentTextChunkEntity> {
        val rows = ArrayList<AgentTextChunkEntity>()
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { result: ResultSet ->
                while (result.next()) {
                    rows += AgentTextChunkEntity(
                        ownerTable = result.getString("owner_table"),
                        ownerId = result.getString("owner_id"),
                        field = result.getString("field"),
                        chunkIndex = result.getInt("chunk_index"),
                        content = result.getString("content"),
                    )
                }
            }
        }
        return rows
    }

    /** 直接按块拼接，绕过 restoreText 的自检，用来发现「还原能过但内容被写坏」。 */
    fun storedText(): String = runBlocking { textChunks(table, owner, field, 1_000_000, 0) }
        .joinToString("") { it.content }

    fun chunkCount(): Int = runBlocking { textChunks(table, owner, field, 1_000_000, 0) }.size

    fun store(text: String, stored: String? = null): String =
        runBlocking { storeText(table, owner, field, text, stored) }

    fun restore(stored: String): String = runBlocking { restoreText(table, owner, field, stored) }

    fun resetCounters() {
        inserts = 0
        deletes = 0
        tailDeletes = 0
    }
}
