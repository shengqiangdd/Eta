package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.db.ChunkedTextDao
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑 / 重新生成时「这段 journal 能不能整段还原」的判定。
 *
 * 为什么要有这条线：启动时的软预算（[AgentConversationStore.JOURNAL_INLINE_LIMIT]）只保证
 * 「不主动铺进内存」；用户点编辑时仍会真的拼字符串。本机最大的 journal 是 31,087,226 字符，
 * 拼接需要约 62 MB 连续堆 —— 真机上申请 33,558,536 与 67,117,064 字节都失败过（闪退）。
 * 所以这里守的是「宁可明确告知无法编辑，也不去撞 OOM」。
 */
class JournalRevisionLimitTest {
    @Test
    fun zeroAndSmallLengthsAreAllowed() {
        assertTrue(journalRevisionAllowed(0))
        assertTrue(journalRevisionAllowed(1))
        assertTrue(journalRevisionAllowed(3_002_379))
    }

    @Test
    fun exactLimitIsStillAllowed() {
        // 上限本身必须放行：否则「刚好等于上限」的历史会因为一个 off-by-one 被永久锁住。
        assertTrue(journalRevisionAllowed(JOURNAL_REVISION_LIMIT))
        assertTrue(journalRevisionAllowed(JOURNAL_REVISION_LIMIT, JOURNAL_REVISION_LIMIT))
    }

    @Test
    fun oneOverLimitIsRejected() {
        assertFalse(journalRevisionAllowed(JOURNAL_REVISION_LIMIT + 1))
    }

    @Test
    fun realDeviceLargestJournalIsRejected() {
        // 真机实际值：@eta:chunks:v1:1898:31087226（conv-2fbc91d6，1898 个 16 KB 分块）。
        assertFalse("3108 万字符不能尝试整段还原", journalRevisionAllowed(31_087_226))
    }

    @Test
    fun unknownAndNegativeLengthsAreRejected() {
        // 解析不出来的引用按「极大」处理；负数只可能来自异常数据，同样不能放行。
        assertFalse(journalRevisionAllowed(ChunkedTextDao.UNKNOWN_LENGTH.toLong()))
        assertFalse(journalRevisionAllowed(-1))
    }

    @Test
    fun customLimitIsRespected() {
        assertTrue(journalRevisionAllowed(100, limit = 100))
        assertFalse(journalRevisionAllowed(101, limit = 100))
        assertTrue(journalRevisionAllowed(31_087_226, limit = 40_000_000))
    }
}
