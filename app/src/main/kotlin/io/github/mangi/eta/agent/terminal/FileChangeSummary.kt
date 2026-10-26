package io.github.mangi.eta.agent.terminal

/**
 * 文件变更摘要：给 `write_file` / `edit_file` 的返回值补上「改了哪几行、增删多少行」。
 *
 * 参考 dsh 的 diff 卡片：工具结果不能只有「已写入 N 字节」，模型与用户都需要
 * 一个能核对的落点——第几行开始、删了几行、加了几行、上下文是什么。
 *
 * 这里是纯文本计算（不读文件、不写文件）：先裁掉公共前后缀，再对中间那段跑
 * Myers 行级 diff，得到**真实**的增删行数与变更块。
 *
 * 旧实现只裁前后缀、把中间整段当成「全删 + 全加」：文件里两处相隔很远的插入
 * 会被报成上百行增删（实测 200 行文件插 2 行 → `+141 / -139`），diff 也只能
 * 给出一大坨，模型无法据它核对「我到底改了什么」。
 */
internal object FileChangeSummary {

    data class Delta(
        val added: Int,
        val removed: Int,
        /** 变更起始行（1-based，第一个变更块）；没有变更时为 null。 */
        val line: Int?,
        val lineEnd: Int?,
        val lineCountBefore: Int,
        val lineCountAfter: Int,
        val diff: String,
        val truncated: Boolean,
        /** 变更块数量；>1 说明改动分散在多处，[line]/[lineEnd] 只描述第一块。 */
        val hunks: Int = 1,
    )

    fun between(before: String, after: String, maxDiffLines: Int = DEFAULT_MAX_DIFF_LINES): Delta {
        val beforeLines = splitLines(before)
        val afterLines = splitLines(after)
        var prefix = 0
        while (
            prefix < beforeLines.size && prefix < afterLines.size &&
            beforeLines[prefix] == afterLines[prefix]
        ) {
            prefix += 1
        }
        var suffix = 0
        while (
            suffix < beforeLines.size - prefix && suffix < afterLines.size - prefix &&
            beforeLines[beforeLines.size - 1 - suffix] == afterLines[afterLines.size - 1 - suffix]
        ) {
            suffix += 1
        }
        val afterEnd = afterLines.size - suffix
        val matches = myersMatches(beforeLines, prefix, beforeLines.size - suffix, afterLines, prefix, afterEnd)
        val blocks = changeBlocks(prefix, beforeLines.size - suffix, prefix, afterEnd, matches)
        val changed = blocks.isNotEmpty()
        // 没有变更时不要输出「三行上下文」——那会让人以为改了什么。
        val rendered = if (changed) renderDiff(beforeLines, afterLines, blocks, maxDiffLines) else "" to false
        val first = blocks.firstOrNull()
        return Delta(
            added = blocks.sumOf { it.afterCount },
            removed = blocks.sumOf { it.beforeCount },
            line = first?.let { it.beforeStart + 1 },
            lineEnd = first?.let { it.beforeStart + maxOf(it.beforeCount, 1) },
            lineCountBefore = beforeLines.size,
            lineCountAfter = afterLines.size,
            diff = rendered.first,
            truncated = rendered.second,
            hunks = blocks.size,
        )
    }

    /** 行数口径：空文本 0 行；结尾换行不额外算一行。 */
    fun lineCount(text: String): Int = splitLines(text).size

    /** 字符偏移所在行（1-based）。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        if (offset <= 0) return 1
        var line = 1
        val end = minOf(offset, text.length)
        var index = 0
        while (index < end) {
            if (text[index] == '\n') line += 1
            index += 1
        }
        return line
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').toMutableList()
        if (text.endsWith('\n')) lines.removeAt(lines.size - 1)
        return lines
    }

    /** 一处变更：从 [beforeStart] 起删 [beforeCount] 行，并在该处加 [afterCount] 行。 */
    private data class ChangeBlock(
        val beforeStart: Int,
        val beforeCount: Int,
        val afterStart: Int,
        val afterCount: Int,
    )

    private fun changeBlocks(
        beforeStart: Int,
        beforeEnd: Int,
        afterStart: Int,
        afterEnd: Int,
        matches: List<IntArray>,
    ): List<ChangeBlock> {
        val blocks = ArrayList<ChangeBlock>()
        var bi = beforeStart
        var ai = afterStart
        for (pair in matches) {
            val pb = pair[0]
            val pa = pair[1]
            if (pb < bi || pa < ai || pb >= beforeEnd || pa >= afterEnd) continue
            if (pb > bi || pa > ai) blocks.add(ChangeBlock(bi, pb - bi, ai, pa - ai))
            bi = pb + 1
            ai = pa + 1
        }
        if (bi < beforeEnd || ai < afterEnd) {
            blocks.add(ChangeBlock(bi, beforeEnd - bi, ai, afterEnd - ai))
        }
        return blocks
    }

    /**
     * Myers 行级 diff，返回「两边完全相同、可以配对」的行坐标。
     *
     * 编辑距离超过上限时返回空列表，由调用方把整段当作一次替换——宁可少说，
     * 也不把 O(n·m) 的 DP 塞进手机里。
     */
    private fun myersMatches(
        before: List<String>,
        beforeStart: Int,
        beforeEnd: Int,
        after: List<String>,
        afterStart: Int,
        afterEnd: Int,
    ): List<IntArray> {
        val n = beforeEnd - beforeStart
        val m = afterEnd - afterStart
        if (n <= 0 || m <= 0) return emptyList()
        val span = n + m
        // 区域越大，允许的编辑距离上限越小：工具返回值不值得跑上百万次比较。
        val limit = minOf(span, if (span <= LARGE_SPAN) MAX_EDITS else COARSE_EDITS)
        val offset = limit + 1
        val v = IntArray(2 * offset + 1)
        val trace = ArrayList<IntArray>(limit + 1)
        var found = -1
        for (d in 0..limit) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                val slot = k + offset
                var x = if (k == -d || (k != d && v[slot - 1] < v[slot + 1])) v[slot + 1] else v[slot - 1] + 1
                var y = x - k
                while (x < n && y < m && before[beforeStart + x] == after[afterStart + y]) {
                    x += 1
                    y += 1
                }
                v[slot] = x
                if (x >= n && y >= m) {
                    found = d
                    break
                }
                k += 2
            }
            if (found >= 0) break
        }
        if (found < 0) return emptyList()
        val matches = ArrayList<IntArray>(found + 1)
        var x = n
        var y = m
        for (d in found downTo 1) {
            val prev = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && prev[k - 1 + offset] < prev[k + 1 + offset])) k + 1 else k - 1
            val prevX = prev[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x -= 1
                y -= 1
                matches.add(intArrayOf(beforeStart + x, afterStart + y))
            }
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) {
            x -= 1
            y -= 1
            matches.add(intArrayOf(beforeStart + x, afterStart + y))
        }
        matches.sortWith { a, b -> if (a[0] != b[0]) a[0] - b[0] else a[1] - b[1] }
        return matches
    }

    private fun renderDiff(
        before: List<String>,
        after: List<String>,
        blocks: List<ChangeBlock>,
        maxLines: Int,
    ): Pair<String, Boolean> {
        val output = ArrayList<String>()
        var truncated = false
        fun append(line: String): Boolean {
            if (output.size >= maxLines) {
                truncated = true
                return false
            }
            output.add(line)
            return true
        }

        var renderedThrough = 0
        for ((blockIndex, block) in blocks.withIndex()) {
            val contextStart = (block.beforeStart - CONTEXT_LINES).coerceAtLeast(renderedThrough)
            // 两条 hunk 之间夹着没改动的行：显式标出来，否则看起来像连续的一段。
            if (blockIndex > 0 && contextStart > renderedThrough && !append(GAP_MARKER)) break
            // 每段都给头：删行用旧坐标、加行用新坐标，没有头时连读会像「行号倒退」。
            if (!append(hunkHeader(block))) break
            var stopped = false
            for (index in contextStart until block.beforeStart) {
                if (!append(numbered(index + 1, ' ', before[index]))) {
                    stopped = true
                    break
                }
            }
            if (stopped) break
            for (index in 0 until block.beforeCount) {
                val line = block.beforeStart + index
                if (!append(numbered(line + 1, '-', before[line]))) {
                    stopped = true
                    break
                }
            }
            if (stopped) break
            for (index in 0 until block.afterCount) {
                val line = block.afterStart + index
                if (!append(addedLine(after[line]))) {
                    stopped = true
                    break
                }
            }
            if (stopped) break
            renderedThrough = block.beforeStart + block.beforeCount
        }
        if (!truncated) {
            val tailEnd = minOf(renderedThrough + CONTEXT_LINES, before.size)
            for (index in renderedThrough until tailEnd) {
                if (!append(numbered(index + 1, ' ', before[index]))) break
            }
        }
        return output.joinToString("\n") to truncated
    }

    private fun numbered(line: Int, marker: Char, text: String): String =
        "${line.toString().padStart(4, ' ')}|$marker$text"

    /**
     * 加行不占行号列。
     *
     * 行号列统一用**旧文件**坐标（删行与上下文都是旧号，天然单调）；如果加行再写新号，
     * 就会出现 `184|+x` 后面紧跟 `183| 上下文` 这种“行号倒退”，读起来像工具算错了。
     * 加行落在新文件第几行由行首的 `@@ -旧,数 +新,数 @@` 给出。
     */
    private fun addedLine(text: String): String = "     |+$text"

    /** `@@ -旧起始,旧行数 +新起始,新行数 @@`：让读的人知道这段在两边各落在哪里。 */
    private fun hunkHeader(block: ChangeBlock): String =
        "     |@@ -${block.beforeStart + 1},${block.beforeCount} +${block.afterStart + 1},${block.afterCount} @@"

    private const val CONTEXT_LINES = 3
    private const val DEFAULT_MAX_DIFF_LINES = 48
    private const val GAP_MARKER = "     |~"
    /** 中间段最多允许的编辑距离；超出就按「整段替换」处理。 */
    private const val MAX_EDITS = 512
    /** 中间段本身很大时（行数之和），进一步收紧编辑距离上限，避免 O(span·D) 拖慢工具返回。 */
    private const val COARSE_EDITS = 96
    private const val LARGE_SPAN = 40_000
}
