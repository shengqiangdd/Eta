package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.db.ChunkedTextDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动时「还原哪些会话的 journal」的分配逻辑。
 *
 * 为什么要合计预算：单会话上限（4M）只管单个会话。2026-10-21 真机库副本实测 8 个会话里
 * 4 个超过 4M（31.1M / 16.7M / 12.2M / 7.0M），剩下 3 个都不满 4M
 * （3,002,379 + 2,983,229 + 2,793,411 = 8,779,019 字符）——启动时**全部**还原，
 * 合计已经是单会话上限的两倍多。当初闪退的现场是 4 个会话合计 4,830 万字符。
 */
class JournalInlinePlanTest {
    private fun candidate(id: String, length: Int, updatedAt: Long) =
        JournalCandidate(id = id, length = length, updatedAt = updatedAt)

    @Test
    fun everythingUnderBothLimitsIsInlined() {
        val plan = planJournalInlining(
            candidates = listOf(candidate("a", 1_000, 3), candidate("b", 2_000, 2), candidate("c", 3_000, 1)),
            selectedId = "c",
        )
        assertEquals(3, plan.count(JournalInlineDecision.INLINE))
        assertEquals(0, plan.count(JournalInlineDecision.TOO_LARGE))
        assertEquals(0, plan.count(JournalInlineDecision.OVER_BUDGET))
        assertEquals(6_000L, plan.inlineChars)
    }

    @Test
    fun singleConversationOverPerLimitIsDeferred() {
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("big", AgentConversationStore.JOURNAL_INLINE_LIMIT + 1, 9),
                candidate("small", 10, 1),
            ),
            selectedId = "small",
        )
        assertEquals(JournalInlineDecision.TOO_LARGE, plan.decisions["big"])
        assertTrue(plan.isInline("small"))
        assertEquals(10L, plan.inlineChars)
    }

    @Test
    fun unparsableLengthIsTreatedAsTooLarge() {
        // UNKNOWN_LENGTH 是 Int.MAX_VALUE：猜小会把本该 deferred 的 journal 铺满内存。
        val plan = planJournalInlining(
            candidates = listOf(candidate("broken", ChunkedTextDao.UNKNOWN_LENGTH, 5)),
            selectedId = null,
        )
        assertEquals(JournalInlineDecision.TOO_LARGE, plan.decisions["broken"])
        assertEquals(0L, plan.inlineChars)
    }

    @Test
    fun totalBudgetKeepsNewestAndDefersOldest() {
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("new", 2_000_000, 30),
                candidate("mid", 2_000_000, 20),
                candidate("old", 2_000_000, 10),
            ),
            selectedId = null,
            totalLimit = 4_000_000L,
        )
        assertEquals(JournalInlineDecision.INLINE, plan.decisions["new"])
        assertEquals(JournalInlineDecision.INLINE, plan.decisions["mid"])
        assertEquals("最旧的应该让位", JournalInlineDecision.OVER_BUDGET, plan.decisions["old"])
        assertEquals(4_000_000L, plan.inlineChars)
    }

    @Test
    fun selectedConversationWinsEvenWhenItIsTheOldest() {
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("new", 2_000_000, 30),
                candidate("old", 2_000_000, 10),
            ),
            selectedId = "old",
            totalLimit = 2_000_000L,
        )
        assertTrue("当前会话必须可编辑：用户正看着它", plan.isInline("old"))
        assertEquals(JournalInlineDecision.OVER_BUDGET, plan.decisions["new"])
    }

    @Test
    fun oneHugeConversationDoesNotBlockSmallerOnes() {
        // 跳过一个装不下的，继续试更小的：否则一个 3.9M 会让后面所有 200K 都进不来。
        // huge 比整个预算还大（1.2M > 1M）：它装不下，但不能因此不再看后面的会话。
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("huge", 1_200_000, 30),
                candidate("tinyA", 200_000, 20),
                candidate("tinyB", 200_000, 10),
            ),
            selectedId = null,
            totalLimit = 1_000_000L,
        )
        assertEquals(JournalInlineDecision.OVER_BUDGET, plan.decisions["huge"])
        assertTrue(plan.isInline("tinyA"))
        assertTrue(plan.isInline("tinyB"))
        assertEquals(400_000L, plan.inlineChars)
    }

    @Test
    fun emptyConversationListProducesEmptyPlan() {
        val plan = planJournalInlining(emptyList(), selectedId = null)
        assertTrue(plan.decisions.isEmpty())
        assertEquals(0L, plan.inlineChars)
        assertEquals(0, plan.count(JournalInlineDecision.INLINE))
    }

    /**
     * 真机分布（2026-10-21 库副本，8 个会话）：现状必须**不受**新预算影响 ——
     * 该还原的还原，该 deferred 的照旧 deferred，一行都不能变。
     */
    @Test
    fun realDeviceDistributionKeepsCurrentBehaviour() {
        val updatedAt = mapOf(
            "conv-c41f8de3" to 100L, // 当前会话
            "conv-2fbc91d6" to 90L,
            "conv-817b67ba" to 80L,
            "conv-1ca305ee" to 70L,
            "conv-13f212c1" to 60L,
            "conv-126794bb" to 50L,
            "conv-299e2747" to 40L,
            "conv-4cf6d775" to 30L,
        )
        val lengths = mapOf(
            "conv-c41f8de3" to 3_002_379,
            "conv-2fbc91d6" to 31_087_226,
            "conv-817b67ba" to 16_655_997,
            "conv-1ca305ee" to 12_170_740,
            "conv-13f212c1" to 6_992_586,
            "conv-126794bb" to 2_983_229,
            "conv-299e2747" to 2_793_411,
            "conv-4cf6d775" to 194,
        )
        val plan = planJournalInlining(
            candidates = lengths.map { (id, length) -> candidate(id, length, updatedAt.getValue(id)) },
            selectedId = "conv-c41f8de3",
        )
        assertEquals(4, plan.count(JournalInlineDecision.TOO_LARGE))
        assertEquals(0, plan.count(JournalInlineDecision.OVER_BUDGET))
        assertEquals(4, plan.count(JournalInlineDecision.INLINE))
        assertEquals(8_779_213L, plan.inlineChars)
        assertTrue(plan.isInline("conv-c41f8de3"))
        assertFalse(plan.isInline("conv-13f212c1"))
    }

    /** 再添两个贴着单会话上限的会话：不设合计预算就会一起铺满内存，现在必须让位。 */
    @Test
    fun budgetKicksInWhenSeveralConversationsSitJustUnderThePerLimit() {
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("s1", 3_900_000, 80),
                candidate("s2", 3_900_000, 70),
                candidate("s3", 3_900_000, 60),
                candidate("s4", 3_900_000, 50),
                candidate("s5", 3_900_000, 40),
            ),
            selectedId = "s1",
        )
        assertEquals(3, plan.count(JournalInlineDecision.INLINE))
        assertEquals(2, plan.count(JournalInlineDecision.OVER_BUDGET))
        assertEquals(11_700_000L, plan.inlineChars)
        assertTrue("当前会话必须留在内存里", plan.isInline("s1"))
        assertFalse(plan.isInline("s5"))
    }
}
