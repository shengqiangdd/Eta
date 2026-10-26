package io.github.mangi.eta.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守住「按块读」的入口：内联文本与分块引用必须走同一条调用路径，
 * 且越界、空值都只返回空，不抛异常 —— 它现在是「读历史」的唯一入口。
 */
class ChunkedTextDaoAccessTest {

    private class MemoryChunks : ChunkedTextDao {
        private val rows = LinkedHashMap<Triple<String, String, String>, MutableList<String>>()

        private fun key(table: String, owner: String, field: String) = Triple(table, owner, field)

        override suspend fun insertTextChunk(chunk: AgentTextChunkEntity) {
            rows.getOrPut(key(chunk.ownerTable, chunk.ownerId, chunk.field)) { mutableListOf() }
                .also { list ->
                    while (list.size <= chunk.chunkIndex) list += ""
                    list[chunk.chunkIndex] = chunk.content
                }
        }

        override suspend fun deleteTextChunks(table: String, owner: String, field: String) {
            rows.remove(key(table, owner, field))
        }

        override suspend fun deleteTextChunksFrom(table: String, owner: String, field: String, fromIndex: Int) {
            rows[key(table, owner, field)]?.let { list ->
                while (list.size > fromIndex) list.removeAt(list.size - 1)
            }
        }

        override suspend fun textChunks(
            table: String,
            owner: String,
            field: String,
            limit: Int,
            offset: Int,
        ): List<AgentTextChunkEntity> = textChunksFrom(table, owner, field, offset, limit)

        override suspend fun textChunksFrom(
            table: String,
            owner: String,
            field: String,
            fromIndex: Int,
            limit: Int,
        ): List<AgentTextChunkEntity> {
            val list = rows[key(table, owner, field)] ?: return emptyList()
            return (fromIndex until minOf(list.size, fromIndex + limit))
                .map { AgentTextChunkEntity(table, owner, field, it, list[it]) }
        }
    }

    private val table = "conversation_context_checkpoints"
    private val owner = "conv-test"
    private val field = "journal"

    @Test fun inlineTextIsOneChunkAndOnlyReadOnce() = runBlocking {
        val dao = MemoryChunks()
        val inline = "[{\"role\":\"user\"}]"
        assertEquals(1, dao.storedChunkCount(inline))
        assertEquals(listOf(inline), dao.readStoredChunks(table, owner, field, inline, 0, 8))
        assertEquals(emptyList<String>(), dao.readStoredChunks(table, owner, field, inline, 1, 8))
        assertEquals(0, dao.storedChunkCount(""))
        assertEquals(emptyList<String>(), dao.readStoredChunks(table, owner, field, "", 0, 8))
    }

    @Test fun referenceReadsOnlyTheRequestedWindow() = runBlocking {
        val dao = MemoryChunks()
        listOf("a", "b", "c").forEachIndexed { index, text ->
            dao.insertTextChunk(AgentTextChunkEntity(table, owner, field, index, text))
        }
        val stored = ChunkedTextLayout.reference(ChunkedTextDao.REFERENCE_PREFIX, 3, 3)
        assertEquals(3, dao.storedChunkCount(stored))
        assertEquals(listOf("a", "b", "c"), dao.readStoredChunks(table, owner, field, stored, 0, 8))
        assertEquals(listOf("b", "c"), dao.readStoredChunks(table, owner, field, stored, 1, 8))
        assertEquals(listOf("b"), dao.readStoredChunks(table, owner, field, stored, 1, 1))
        assertEquals(emptyList<String>(), dao.readStoredChunks(table, owner, field, stored, 3, 8))
        assertEquals(emptyList<String>(), dao.readStoredChunks(table, owner, field, stored, 0, 0))
    }
}
