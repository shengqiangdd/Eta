package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守「压缩时保留多少近期原文」。
 *
 * 起因（真机数据）：切分点原先取最大可切分点，一次压缩把 499,495 字符压到 4,923，
 * 只剩摘要 + 最新请求。模型下一轮不知道刚看过什么，又去重读文件 —— 省下的 token 又花回去。
 * 保留量本身是纯计算，所以在这里钉死边界，不靠真机跑一次去猜。
 */
class AgentContextSplitTest {

    /** 200k 窗口下，80,000 字符上限（约 20k token）只占 10%，窗口不会成为限制项。 */
    private val largeWindow = 200_000

    private fun message(chars: Int, role: String = "assistant"): JSONObject =
        JSONObject().put("role", role).put("content", "x".repeat(chars))

    @Test
    fun tailBudgetIsZeroForTinyHistories() {
        // 2 万字符以下压缩本来就没多少可省，留一截反而白跑一次摘要。
        assertEquals(0L, AgentContextBudget.retainTailChars(0, largeWindow))
        assertEquals(0L, AgentContextBudget.retainTailChars(20_000, largeWindow))
        assertEquals(0L, AgentContextBudget.retainTailChars(19_999, largeWindow))
    }

    @Test
    fun tailBudgetFollowsOneFifth() {
        assertEquals(20_000L, AgentContextBudget.retainTailChars(100_000, largeWindow))
        assertEquals(12_000L, AgentContextBudget.retainTailChars(60_000, largeWindow))
    }

    @Test
    fun tailBudgetIsCapped() {
        assertEquals(AgentContextBudget.RETAIN_TAIL_MAX_CHARS, AgentContextBudget.retainTailChars(500_000, largeWindow))
        assertEquals(AgentContextBudget.RETAIN_TAIL_MAX_CHARS, AgentContextBudget.retainTailChars(5_000_000, largeWindow))
    }

    @Test
    fun tailBudgetIsCappedBySmallWindow() {
        // 32k 窗口：80,000 字符（约 20k token）会占 62%，压缩完仍然满，下一轮立刻再压一次。
        // 按窗口收到 15%，即 32,000 × 4 × 0.15 = 19,200 字符（约 4,800 token）。
        assertEquals(19_200L, AgentContextBudget.retainTailChars(500_000, contextWindowTokens = 32_000))
    }

    @Test
    fun tailBudgetIgnoresUnknownWindow() {
        // 拿不到窗口时不能把保留量算成 0 —— 那会悄悄退化成「压到底」的旧行为。
        assertEquals(
            AgentContextBudget.RETAIN_TAIL_MAX_CHARS,
            AgentContextBudget.retainTailChars(500_000, contextWindowTokens = 0),
        )
    }

    @Test
    fun tailBudgetNeverEatsTheWholeGain() {
        // 预算必须给摘要留出空间，否则「保留尾部」会把压缩本身变得没有意义。
        val chars = 24_000L
        val budget = AgentContextBudget.retainTailChars(chars, largeWindow)
        assertTrue("保留量必须小于历史减去最小收益", budget <= chars - AgentContextBudget.MIN_COMPACT_GAIN_CHARS)
    }

    @Test
    fun splitKeepsAtLeastTheRequestedTail() {
        val history = (1..10).map { message(1_000) }
        val end = CompactionSplit.choose(history, retainChars = 3_000)
        assertEquals(7, end)
        assertTrue(CompactionSplit.tailChars(history, end!!) >= 3_000)
    }

    @Test
    fun splitWithoutBudgetStillCompactsAsMuchAsPossible() {
        val history = (1..10).map { message(1_000) }
        assertEquals(10, CompactionSplit.choose(history, retainChars = 0))
    }

    @Test
    fun splitStopsWhenTailAlreadyCoversTheBudget() {
        val history = (1..10).map { message(1_000) }
        // 保留预算 500：最后一条（1,000 字符）单独就够，切点应停在 9，
        // 不该继续退到 8 —— 那是多丢一条原文、少压一条。预算越小退得越少，反之亦然。
        assertEquals(9, CompactionSplit.choose(history, retainChars = 500))
        assertEquals(8, CompactionSplit.choose(history, retainChars = 1_500))
        // retainChars = 0 是「不设下限」的哨兵值，等价旧行为：能压多少压多少。
        assertEquals(10, CompactionSplit.choose(history, retainChars = 0))
    }

    @Test
    fun splitOnlyLandsOnSafeBoundaries() {
        // 最后一条是 user：不能切在它后面（否则模型会丢掉「用户最后说了什么」）。
        val history = (1..9).map { message(1_000) } + message(1_000, role = "user")
        val end = CompactionSplit.choose(history, retainChars = 0)
        assertEquals(9, end)
    }

    @Test
    fun splitReturnsNullWhenNothingCanBeSplit() {
        assertNull(CompactionSplit.choose(listOf(message(100, role = "user")), retainChars = 0))
        assertNull(CompactionSplit.choose(emptyList(), retainChars = 1_000))
    }

    @Test
    fun splitOnLargeHistoryDoesNotDegradeToSeconds() {
        // 真机历史可达数千条：choose 每退一步都要重扫一遍历史（canSplit 会逐条解析 tool_calls），
        // 一旦退化成平方复杂度，压缩路径会先卡几秒才开始发请求。
        // 这里给一个宽松上限，只守「不要退化成秒级」，不追求具体数字。
        val history = (1..3_000).map { message(200) } // 600k 字符，接近真机一次压缩的规模
        val budget = AgentContextBudget.retainTailChars(600_000, contextWindowTokens = 200_000)
        val started = System.nanoTime()
        val end = CompactionSplit.choose(history, budget)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val split = checkNotNull(end)
        assertTrue("切分点应落在历史内部，实际 $split", split in 1 until history.size)
        assertTrue(
            "3,000 条历史选切分点耗时 ${elapsedMs}ms，已退化到秒级",
            elapsedMs < 1_000,
        )
        // 保留量必须真的兑现：尾部不少于预算（除非已退到不能更小）。
        assertTrue(CompactionSplit.tailChars(history, split) >= budget)
    }
}
