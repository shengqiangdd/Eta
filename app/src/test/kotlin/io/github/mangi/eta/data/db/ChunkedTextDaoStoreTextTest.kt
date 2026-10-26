package io.github.mangi.eta.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块文本「只写尾部」的行为测试（纯 JVM + JDBC 内存库，不依赖 Robolectric）。
 *
 * 为什么需要它：`storeText` 原来每次保存都「删光全部分块再逐块重插」——
 * 2026-10-21 在真机库上实测到单个会话 journal 有 3100 万字符 / 1898 块，
 * 一条消息就可能重写几十 MB。改成增量写属于**核心数据路径**改动：
 * 写错就是丢会话历史，所以这里用真实的 SQLite 跑真实的
 * `storeText` / `restoreText` 默认实现（见 [JdbcChunkDao]），守三件事：
 *   1. 增量写之后还原出来的内容与整段重写完全一致（含代理对边界）；
 *   2. 「内容没变」必须一次都不写库；
 *   3. 中间修改 / 变短必须退回整段重写，不能残留旧块。
 *
 * 注意：文本长度都控制在日志阈值（262144 字符）以下 ——
 * 单测里没有 Android 的 Log 实现，超过阈值会走到 Log.i 抛 "Stub!"。
 */
class ChunkedTextDaoStoreTextTest {

    private val dao = JdbcChunkDao()

    @Test
    fun `短文本不进分块表`() {
        val stored = dao.store("hello 短文本")
        assertEquals("hello 短文本", stored)
        assertEquals(0, dao.inserts)
        assertEquals(0, dao.chunkCount())
        assertEquals("hello 短文本", dao.restore(stored))
    }

    @Test
    fun `首次写入长文本按块切分且能原样还原`() {
        val text = bigText(3 * ChunkedTextLayout.CHUNK_CHARS + 777)
        val stored = dao.store(text)
        assertTrue("应写入 >3 块，实际 ${dao.inserts}", dao.inserts > 3)
        assertEquals(text, dao.restore(stored))
        assertEquals(text, dao.storedText())
    }

    @Test
    fun `追加内容只重写尾部`() {
        val base = bigText(6 * ChunkedTextLayout.CHUNK_CHARS + 100)
        dao.store(base)
        assertTrue("首次应写满 6 块以上，实际 ${dao.inserts}", dao.inserts >= 6)
        dao.resetCounters()

        val grown = base + bigText(2 * ChunkedTextLayout.CHUNK_CHARS + 50)
        val stored = dao.store(grown)

        assertTrue("增量写应只碰尾部少量块，实际 inserts=${dao.inserts}", dao.inserts <= 3)
        assertEquals("不应整表删除", 0, dao.deletes)
        assertTrue("应走尾部删除", dao.tailDeletes >= 1)
        assertEquals(grown, dao.restore(stored))
        assertEquals(grown, dao.storedText())
    }

    @Test
    fun `内容完全相同时一次都不写`() {
        val text = bigText(4 * ChunkedTextLayout.CHUNK_CHARS + 9)
        val first = dao.store(text)
        dao.resetCounters()
        val second = dao.store(text)
        assertEquals(0, dao.inserts)
        assertEquals(0, dao.deletes)
        assertEquals(0, dao.tailDeletes)
        assertEquals(first, second)
        assertEquals(text, dao.restore(second))
    }

    @Test
    fun `中间修改退回整段重写`() {
        val base = bigText(5 * ChunkedTextLayout.CHUNK_CHARS)
        dao.store(base)
        dao.resetCounters()
        val pivot = ChunkedTextLayout.CHUNK_CHARS * 2 + 5
        val edited = base.substring(0, pivot) + "改" + base.substring(pivot + 1)
        val stored = dao.store(edited)
        assertTrue("应整表删除重建，实际 deletes=${dao.deletes}", dao.deletes >= 1)
        assertEquals(0, dao.tailDeletes)
        assertEquals(edited, dao.restore(stored))
        assertEquals(edited, dao.storedText())
    }

    @Test
    fun `变短退回整段重写且不残留旧块`() {
        val base = bigText(4 * ChunkedTextLayout.CHUNK_CHARS + 500)
        dao.store(base)
        dao.resetCounters()
        val shorter = base.substring(0, ChunkedTextLayout.CHUNK_CHARS + 10)
        val stored = dao.store(shorter)
        assertTrue("应变短重写，实际 deletes=${dao.deletes}", dao.deletes >= 1)
        assertEquals(shorter, dao.restore(stored))
        assertEquals(shorter, dao.storedText())
        assertEquals("旧块应被清掉，只剩 2 块", 2, dao.chunkCount())
    }

    @Test
    fun `代理对不会被拆到两个分块`() {
        val text = buildString {
            append("a".repeat(ChunkedTextLayout.CHUNK_CHARS - 1))
            append("😀")
            append("b".repeat(ChunkedTextLayout.CHUNK_CHARS))
            append("😀")
            append("c".repeat(300))
        }
        val stored = dao.store(text)
        val rows = runBlocking { dao.textChunks(TABLE, OWNER, FIELD, 100, 0) }
        assertTrue(rows.size >= 3)
        rows.forEach { chunk ->
            assertFalse("分块不应以高代理项结尾", chunk.content.last().isHighSurrogate())
            assertFalse("分块不应以低代理项开头", chunk.content.first().isLowSurrogate())
        }
        assertEquals(text, dao.restore(stored))
        assertEquals(text, dao.storedText())
    }

    @Test
    fun `代理对边界上的增量追加仍然正确`() {
        val head = buildString {
            append("a".repeat(ChunkedTextLayout.CHUNK_CHARS - 1))
            append("😀")
        }
        dao.store(head)
        dao.resetCounters()
        val grown = head + "b".repeat(ChunkedTextLayout.CHUNK_CHARS + 5)
        val stored = dao.store(grown)
        assertEquals("增量为纯追加，不应整表删除", 0, dao.deletes)
        assertEquals(grown, dao.restore(stored))
        assertEquals(grown, dao.storedText())
    }

    @Test
    fun `JSON 数组追加时把结尾封口当作可丢弃尾巴`() {
        // 真机上的 journal 是 JSON 数组：追加一条消息时，旧文本末尾的 `]`
        // 在新文本里要后移，所以「新文本以旧文本为前缀」永远不成立。
        // 这里守住：只要旧内容在前缀之后只剩这一个 `]`，就只重写它所在的那一块。
        val body = ",\"" + "x".repeat(ChunkedTextLayout.CHUNK_CHARS * 2) + "\""
        val base = "[{\"role\":\"user\",\"content\":\"第1条\"}$body]"
        val stored = dao.store(base)
        assertTrue("应至少 3 块，实际 ${dao.chunkCount()}", dao.chunkCount() >= 3)
        assertEquals(base, dao.restore(stored))
        dao.resetCounters()

        val grown = base.dropLast(1) + ",{\"role\":\"assistant\",\"content\":\"新回复\"}]"
        val newStored = dao.store(grown, stored)

        assertEquals("不应整表删除", 0, dao.deletes)
        assertEquals("应恰好一次尾部删除", 1, dao.tailDeletes)
        assertTrue("应只重写封口所在块，实际 inserts=${dao.inserts}", dao.inserts <= 2)
        assertEquals(grown, dao.restore(newStored))
        assertEquals(grown, dao.storedText())
    }

    @Test
    fun `没有存储引用时不去猜封口只整段重写`() {
        // 判断「最后一块是不是只剩一个 `]`」靠的是存储引用里的块数与总长度。
        // 调用方没给原引用时无从核实，必须保守地整段重写 —— 猜错的代价是丢历史。
        val body = ",\"" + "y".repeat(ChunkedTextLayout.CHUNK_CHARS * 2) + "\""
        val base = "[{\"role\":\"user\",\"content\":\"第1条\"}$body]"
        dao.store(base)
        dao.resetCounters()

        val grown = base.dropLast(1) + ",{\"role\":\"assistant\",\"content\":\"新回复\"}]"
        val stored = dao.store(grown)

        assertEquals("没有引用时应整表删除", 1, dao.deletes)
        assertEquals("不应尝试尾部写", 0, dao.tailDeletes)
        assertEquals(grown, dao.restore(stored))
        assertEquals(grown, dao.storedText())
    }

    @Test
    fun `多次连续追加始终与整段重写一致`() {
        var text = ""
        repeat(8) { round ->
            // 每次追加 1.8 万~2.6 万字符：跨块增长，且总量留在日志阈值以下
            text += "第${round + 1}段".repeat(6000 + round * 600)
            val stored = dao.store(text)
            assertEquals("第 ${round + 1} 次追加后还原不一致", text, dao.restore(stored))
            assertEquals("第 ${round + 1} 次追加后分块内容不一致", text, dao.storedText())
        }
    }

    @Test
    fun `引用里的块数与长度与实际分块一致`() {
        val text = bigText(2 * ChunkedTextLayout.CHUNK_CHARS + 10)
        val stored = dao.store(text)
        assertTrue(stored.startsWith(ChunkedTextDao.REFERENCE_PREFIX))
        val parts = stored.removePrefix(ChunkedTextDao.REFERENCE_PREFIX).split(':')
        val chunks = runBlocking { dao.textChunks(TABLE, OWNER, FIELD, 100, 0) }
        assertEquals(chunks.size, parts[0].toInt())
        assertEquals(text.length, parts[1].toInt())
        assertNotEquals(0, chunks.size)
    }

    private fun bigText(length: Int): String {
        val alphabet = "0123456789abcdef"
        return buildString(length) {
            for (index in 0 until length) append(alphabet[index % alphabet.length])
        }
    }

    private companion object {
        const val TABLE = "conversation_context_checkpoints"
        const val OWNER = "conv-test"
        const val FIELD = "journal"
    }
}
