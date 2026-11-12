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
            perConversationLimit = 4_000_000,
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
            perConversationLimit = 4_000_000,
            totalLimit = 2_000_000L,
        )
        assertTrue("当前会话必须可编辑：用户正看着它", plan.isInline("old"))
        assertEquals(JournalInlineDecision.OVER_BUDGET, plan.decisions["new"])
    }

    @Test
    fun oneHugeConversationDoesNotBlockSmallerOnes() {
        // 跳过一个装不下的，继续试更小的：否则一个 3.9M 会让后面所有 200K 都进不来。
        // huge 比整个预算还大（1.2M > 1M）：它装不下，但不能因此不再看后面的会话。
        //
        // 显式抬高 perConversationLimit：本测试只关心「合计预算遇到装不下的怎么办」，
        // 若用默认值（1M），huge 会先在单会话那关被判 TOO_LARGE，走不到 OVER_BUDGET 分支。
        val plan = planJournalInlining(
            candidates = listOf(
                candidate("huge", 1_200_000, 30),
                candidate("tinyA", 200_000, 20),
                candidate("tinyB", 200_000, 10),
            ),
            selectedId = null,
            perConversationLimit = 4_000_000,
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
     * 真机分布（2026-10-21 库副本，8 个会话）。
     *
     * 这个测试原本守的是「新增合计预算不能改变现状」。2026-11-12 启动内联预算
     * 从 4M 降到 1M 后，现状**有意改变**了：只有 194 字符那个会内联。
     *
     * 所以它现在守两件事：
     * 1. 决策结果与预算值一致（没有意外内联给内存）
     * 2. 被 deferred 的当前会话（3.0M）仍在切换加载上限内——启动不拼不等于看不到
     */
    @Test
    fun realDeviceDistributionFollowsInlineBudget() {
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
        // 1M 预算下，只有 194 字符那个会话会内联；其余全部 deferred。
        assertEquals(7, plan.count(JournalInlineDecision.TOO_LARGE))
        assertEquals(0, plan.count(JournalInlineDecision.OVER_BUDGET))
        assertEquals(1, plan.count(JournalInlineDecision.INLINE))
        assertEquals(194L, plan.inlineChars)

        // 关键取舍：**启动不内联 ≠ 永远看不了**。
        // 当前会话 3.0M 现在 deferred，但它小于切换时的加载上限（4M），
        // 所以用户点进去仍能完整加载。这条防的是「调低启动预算把大会话变成
        // 永久不可读」这种回退。
        assertFalse(plan.isInline("conv-c41f8de3"))
        assertTrue(3_002_379 <= AgentConversationStore.JOURNAL_LAZY_LOAD_LIMIT)
        // 超 4M 的几个会话新旧方案都加载不了，行为未变。
        assertTrue(31_087_226 > AgentConversationStore.JOURNAL_LAZY_LOAD_LIMIT)
        assertFalse(plan.isInline("conv-13f212c1"))
    }

    /**
     * 合计预算必须真的生效：多个都低于单会话上限的会话，加起来仍能超过 12M。
     *
     * 2026-11-12：单会话预算从 4M 降到 1M 后，构造也得跟着改。
     * 原来用 3.9M × 5，现在它们全部超过单会话上限，根本走不到合计预算那一支，
     * 测不到本意（会先报 TOO_LARGE 而不是 OVER_BUDGET）。
     * 改用 900K × 15：每个都远低于 1M 单会话上限，合 13.5M > 12M，必须让位。
     */
    @Test
    fun totalBudgetKicksInForManySmallConversations() {
        val plan = planJournalInlining(
            candidates = (1..15).map { candidate("s$it", 900_000, 100L - it) },
            selectedId = "s1",
        )
        // 12M / 900K = 13.3，所以前 13 个内联（11.7M），第 14 个起超预算。
        assertEquals(13, plan.count(JournalInlineDecision.INLINE))
        assertEquals(2, plan.count(JournalInlineDecision.OVER_BUDGET))
        assertEquals(0, plan.count(JournalInlineDecision.TOO_LARGE))
        assertEquals(11_700_000L, plan.inlineChars)
        assertTrue("当前会话必须留在内存里", plan.isInline("s1"))
        assertFalse(plan.isInline("s15"))
    }

    /**
     * 两个预算的关系不能搞反：否则大会话会永久不可读。
     *
     * `AgentConversationStoreTest.deferredJournalCanBeLoadedOnDemand` 守的是同一件事
     * （省内存不能变成「永久不能编辑」），但那个测试是 Robolectric，本机 aarch64 跑不了，
     * 门禁里看不到。这里补一个纯 JVM 的版本守住最核心的关系：
     *
     *   启动内联预算 < 切换加载上限
     *
     * 一旦有人把两者改成相等或搞反，落在中间区间的会话就会「启动时不拼、
     * 点进去也拼不回来」——用户眼里就是历史凭空消失。
     */
    @Test
    fun lazyLoadLimitIsWiderThanInlineLimit() {
        assertTrue(
            "切换上限必须严格大于启动预算，否则中间区的会话会两头都不加载",
            AgentConversationStore.JOURNAL_LAZY_LOAD_LIMIT >
                AgentConversationStore.JOURNAL_INLINE_LIMIT,
        )
    }
}
