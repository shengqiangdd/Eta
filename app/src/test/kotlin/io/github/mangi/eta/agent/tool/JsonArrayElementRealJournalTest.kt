package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.data.db.ChunkedTextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 用**真机导出的真实 journal** 跑一遍扫描器，对比「整份解析」与「有界流式」真正占住的堆。
 *
 * 这是本机专用夹具：仓库里没有那份 3100 万字符的数据，文件不存在时整个类跳过（不假装跑过）。
 * 生成方式：
 *   sqlite3 eta-db-copy.db "select content from agent_text_chunks where
 *     owner_table='conversation_context_checkpoints' and owner_id='<会话>' and field='journal'
 *     order by chunk_index" > journal.txt
 * 期望条数 6450 由 Python `json.loads` 独立数出来，不依赖被测代码。
 */
class JsonArrayElementRealJournalTest {

    private val journalFile =
        File(System.getProperty("eta.realJournal") ?: "/workspace/real-journal/journal.txt")

    /** 只统计「还活着」的对象：调用前后各强制回收一轮，测的是**持有**而不是瞬时分配。 */
    private fun heapUsed(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(120)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun report(label: String, before: Long, after: Long, ms: Long, note: String) {
        println("REAL_JOURNAL $label held=${(after - before) / 1024 / 1024}MB ms=$ms $note")
    }

    @Test fun scansTheRealThirtyOneMillionCharJournal() {
        assumeTrue("真实 journal 夹具不存在，跳过", journalFile.isFile)
        val text = journalFile.readText()
        assumeTrue("夹具太小，说明不是真实数据", text.length > 5_000_000)

        // 按真机存储的方式切块：单块 16 384 字符，不拆代理对。
        val chunks = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            val end = ChunkedTextLayout.chunkEnd(text, cursor)
            chunks += text.substring(cursor, end)
            cursor = end
        }
        println("REAL_JOURNAL chunks=${chunks.size} utf16Chars=${text.length}")

        var reads = 0
        var largestRead = 0
        val scanner = JsonArrayElementScanner { from, limit ->
            reads++
            largestRead = maxOf(largestRead, limit)
            if (from >= chunks.size) emptyList() else chunks.subList(from, minOf(chunks.size, from + limit))
        }

        // 1) 分页读第一条，预算 8000 字符
        val beforeScan = heapUsed()
        val startedNs = System.nanoTime()
        val firstPage = runBlocking { scanner.scan(0, 8_000, null) }
        val scanMs = (System.nanoTime() - startedNs) / 1_000_000
        val afterScan = heapUsed()
        report("scanner_page", beforeScan, afterScan, scanMs, "captured=${firstPage.captured.size} reads=$reads")

        assertEquals("条数必须与 Python 独立解析一致", 6450, firstPage.totalMessages)
        assertTrue(firstPage.captured.isNotEmpty())
        assertTrue(firstPage.captured.first().second.contains("\"role\""))
        // 捕获量必须贴着预算，而不是贴着历史大小（单条消息本身可以很大）。
        val capturedChars = firstPage.captured.sumOf { it.second.length }
        assertTrue("捕获字符数 $capturedChars 远超预算", capturedChars < 8_000 + 200_000)
        // 一次只要 8 块，绝不「把整份给我」。
        assertTrue("单次取块上限 $largestRead 过大", largestRead <= 8)

        // 2) 从深处开始分页（模拟模型翻到旧消息）
        val deep = runBlocking { scanner.scan(6_000, 8_000, null) }
        assertEquals(6450, deep.totalMessages)
        assertEquals(6_000, deep.captured.first().first)

        // 3) 对照：老实现（整份还原 + 整体解析）在同一份数据上**持有**多少堆
        val beforeDecode = heapUsed()
        val decodeStartedNs = System.nanoTime()
        val decoded = runCatching { AgentConversationCodec.decodeTranscript(text) }
        val decodeMs = (System.nanoTime() - decodeStartedNs) / 1_000_000
        val afterDecode = heapUsed()
        report(
            "decode_all",
            beforeDecode,
            afterDecode,
            decodeMs,
            decoded.fold({ "messages=${it.size}" }, { "FAILED=${it.javaClass.simpleName}" }),
        )
        assertTrue("老实现应当持有远超一次分页读取的内存", afterDecode - beforeDecode > afterScan - beforeScan)
        decoded.getOrNull()?.size
    }
}
