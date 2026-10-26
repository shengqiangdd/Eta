package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec

/**
 * 逐块扫描 journal 的 JSON 数组，回答「第 N 条消息是什么」，而不是「把整份历史给我」。
 *
 * 为什么需要它：真机上单个会话的 journal 有 3100 万字符（6450 条消息、1898 个 16 KB 分块）。
 * 原先 `conversation_history` 每次调用都把整份 journal 还原成 String 再整体 JSON 解析 ——
 * 光字符串就约 62 MB UTF-16，解析出来的 6450 个消息对象还要再来一份；
 * 而模型一次最多只能读 8000 字符。也就是说「为读 8 KB 付出 100 MB 级的代价」。
 *
 * 这里改成三件事：
 * 1. 按块拉取（[ChunkReader]），堆上同时只驻留 [PAGE_CHUNKS] 个分块；
 * 2. 按块遍历字符跑状态机（不是逐字符跨调用，真实数据上快数倍）；
 * 3. 只把需要展示的那几条解码成消息文本，不需要的元素连字符都不留。
 *
 * 所有下标都是 Kotlin String 的 UTF-16 下标，与 SQLite 的 `LENGTH()` 不是一个口径，
 * 所以偏移量全部按实际读到的字符累计，不向 SQL 要长度。
 */
internal class JsonArrayElementScanner(
    private val reader: ChunkReader,
) {
    fun interface ChunkReader {
        /** 读取 [from] 起的连续分块（最多 [limit] 块）；返回空列表表示越界（已读完）。 */
        suspend fun read(from: Int, limit: Int): List<String>
    }

    /**
     * @param totalMessages 整份数组的元素个数（走到底才准）
     * @param captured 捕获到的 (消息下标, 已序列化的消息 JSON 文本)，按出现顺序
     */
    data class Page(
        val totalMessages: Int,
        val captured: List<Pair<Int, String>>,
    )

    /**
     * @param fromIndex 只捕获下标 >= 它的元素（之前的一律只数不留）
     * @param budget 捕获文本的字符预算；达到后仍继续数数，只是不再保留原文
     * @param query 非空时只捕获命中的元素（忽略大小写，等价于 [String.contains] 的 ignoreCase）
     */
    suspend fun scan(fromIndex: Int, budget: Int, query: String?): Page {
        val captured = ArrayList<Pair<Int, String>>()
        val element = StringBuilder()
        val cursor = Cursor(reader)
        var total = 0
        var keptChars = 0
        var sawArray = false
        var inElement = false
        var scalar = false
        var depth = 0
        var inString = false
        var escaped = false
        // 是否需要留着原文：查询要全文比对，捕获也要；否则「只要序号」时一个字都不留。
        var accumulate = false

        fun closeElement() {
            val raw = element.toString()
            element.setLength(0)
            inElement = false
            scalar = false
            depth = 0
            inString = false
            escaped = false
            val messageIndex = total
            total++
            if (messageIndex < fromIndex) return
            if (query != null && !raw.contains(query, ignoreCase = true)) return
            if (keptChars >= budget) return
            val text = AgentConversationCodec.serializeForDisplay(raw) ?: return
            captured += messageIndex to text
            keptChars += text.length
        }

        chunks@ while (true) {
            val chunk = cursor.nextChunk() ?: break@chunks
            chars@ for (position in chunk.indices) {
                val ch = chunk[position]

                if (!sawArray) {
                    if (ch == '[') sawArray = true
                    continue@chars
                }
                if (!inElement) {
                    // 元素之间：逗号、空白、数组结束符都不属于任何元素。
                    if (ch == ']') break@chunks
                    if (ch == ',' || ch.isWhitespace()) continue@chars
                    inElement = true
                    scalar = ch != '{' && ch != '['
                    accumulate = total >= fromIndex && (query != null || keptChars < budget)
                    element.setLength(0)
                    depth = 0
                    inString = false
                    escaped = false
                }

                if (scalar) {
                    // 正常 journal 是对象数组；真出现标量也不要死循环，遇到分隔符就收尾。
                    if (ch == ',' || ch == ']' || ch.isWhitespace()) {
                        closeElement()
                        if (ch == ']') break@chunks
                        continue@chars
                    }
                    if (accumulate) element.append(ch)
                    continue@chars
                }

                if (inString) {
                    if (accumulate) element.append(ch)
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue@chars
                }
                if (accumulate) element.append(ch)
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth <= 0) closeElement()
                    }
                }
            }
        }
        // 截断的尾部（正常不会发生）也计入，保证条数与「整体解析」口径一致。
        if (inElement && element.isNotEmpty()) closeElement()
        return Page(total, captured)
    }

    /**
     * 按块前推的游标：一次只要 [PAGE_CHUNKS] 块，堆上有界。
     *
     * 注意这里是**按块返回、块内按字符遍历**：逐字符跨 suspend 调用在 3100 万字符上
     * 实测会慢一个数量级（本机 JVM 1.3 秒 vs 0.2 秒）。
     */
    private class Cursor(private val reader: ChunkReader) {
        private var chunks: List<String> = emptyList()
        private var chunkIndex = 0
        private var nextChunk = 0
        private var end = false

        suspend fun nextChunk(): String? {
            while (true) {
                if (chunkIndex < chunks.size) return chunks[chunkIndex++]
                if (end) return null
                val page = reader.read(nextChunk, PAGE_CHUNKS)
                if (page.isEmpty()) {
                    end = true
                    return null
                }
                nextChunk += page.size
                chunks = page
                chunkIndex = 0
            }
        }
    }

    private companion object {
        /** 8 块约 13 万字符；3100 万字符的 journal 只需约 240 次取块。 */
        const val PAGE_CHUNKS = 8
    }
}
