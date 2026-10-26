package io.github.mangi.eta.data.db

/**
 * 分块文本的**纯逻辑**：切块边界、前缀比对、增量写决策。
 *
 * 为什么单独拆出来：真机上单个会话的 journal 有 3100 万字符（1898 个分块），
 * 原先每次保存都是「先删光全部分块、再逐块重插」，一条消息就可能重写几十 MB。
 * 这里把「能不能只写尾部」的判断做成无副作用函数，才能用普通 JVM 单测守住，
 * 不必起 Room / 真机（核心数据路径的改动，必须有测试兜住）。
 *
 * 所有下标都是 Kotlin String 的 UTF-16 下标，与 SQLite 的 LENGTH() 不是一个口径
 * （SQLite 对 TEXT 按码点计数，代理对算 1），所以不能用 SQL 的 LENGTH 累计偏移。
 */
internal object ChunkedTextLayout {

    /** 单块上限（UTF-16 码元）。与历史数据一致，改动会导致新旧块无法拼接。 */
    const val CHUNK_CHARS = 16_384

    /** 比对时一次读多少块：32 块约 50 万字符，内存有界。 */
    const val COMPARE_PAGE = 32

    /** 从 [start] 开始的一块应有的结束下标（不含），保留原文的「不拆代理对」规则。 */
    fun chunkEnd(text: CharSequence, start: Int, limit: Int = text.length): Int {
        if (start >= limit) return limit
        var end = minOf(start + CHUNK_CHARS, limit)
        if (end < limit && text[end - 1].isHighSurrogate()) end--
        return end
    }

    /** [text] 在 [offset] 起是否与已存分块 [chunk] 完全一致。 */
    fun matches(text: CharSequence, offset: Int, chunk: String): Boolean =
        commonPrefixLength(text, offset, chunk) == chunk.length

    /**
     * [text] 从 [offset] 起与 [chunk] 逐字符相同的前缀长度。
     *
     * 增量写需要知道「旧内容到第几个字符为止还能复用」。整块比对只能回答「不一样」，
     * 而 JSON 数组追加时**最后一块里只有结尾的 `]` 不同**（旧文本的 `]` 要后移），
     * 不知道块内位置就只能退化成整段重写。
     */
    fun commonPrefixLength(text: CharSequence, offset: Int, chunk: String): Int {
        if (offset < 0 || offset > text.length) return 0
        val limit = minOf(chunk.length, text.length - offset)
        var index = 0
        while (index < limit && text[offset + index] == chunk[index]) index++
        return index
    }

    /** 已确认前缀相同后，从哪一块开始重写。 */
    data class Rewrite(val chunkIndex: Int, val startOffset: Int)

    sealed interface Plan {
        /** 库里内容与新文本完全一致：不写库，返回原引用即可。 */
        data class Unchanged(val chunkCount: Int, val length: Int) : Plan

        /** 新文本只是旧内容加了尾巴：保留前 [Rewrite.chunkIndex] 块，从那里重写。 */
        data class TailWrite(val rewrite: Rewrite) : Plan

        /** 需要整段重写（内容变了、变短了，或者本来就没有分块）。 */
        data object FullWrite : Plan
    }

    /**
     * @param prefixMatched 逐块比对是否一路相同（中途不同则为 false）
     * @param prefixLength 已比对通过的字符数（即旧内容的长度）
     * @param chunkLengths 已比对通过的分块各自的长度（UTF-16 码元）
     * @param textLength 新文本长度
     */
    fun plan(
        prefixMatched: Boolean,
        prefixLength: Int,
        chunkLengths: List<Int>,
        textLength: Int,
        dropClosingBracket: Boolean = false,
    ): Plan {
        if (chunkLengths.isEmpty()) return Plan.FullWrite
        if (chunkLengths.sum() != prefixLength) return Plan.FullWrite
        if (textLength < prefixLength) return Plan.FullWrite
        if (prefixMatched) {
            if (textLength == prefixLength) return Plan.Unchanged(chunkLengths.size, prefixLength)
            val last = chunkLengths.last()
            if (last <= 0) return Plan.FullWrite
            return Plan.TailWrite(Rewrite(chunkLengths.size - 1, prefixLength - last))
        }
        // 旧内容在 [prefixLength] 之后只剩一个 JSON 数组封口 `]`（新内容在同样位置是 `}` 或 `,`）：
        // 这块不能复用，必须整块重写，但它前面的块仍然有效。
        // 由调用方按存储引用里的块数与长度确认「确实只剩最后这一块且只多一个字符」。
        if (!dropClosingBracket) return Plan.FullWrite
        if (textLength <= prefixLength) return Plan.FullWrite
        return Plan.TailWrite(Rewrite(chunkLengths.size, prefixLength))
    }

    /** 把引用拼回「前缀 + 块数 + 长度」形式（与 storeText 的返回值一致）。 */
    fun reference(prefix: String, chunkCount: Int, length: Int): String = "$prefix$chunkCount:$length"
}
