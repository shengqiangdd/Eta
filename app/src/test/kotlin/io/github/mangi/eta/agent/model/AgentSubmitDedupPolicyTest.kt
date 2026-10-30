package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连点去重的契约。
 *
 * 拦错了的后果不对称：拦多了 = 用户点发送没反应（还会以为卡住）；拦少了 = 同一段话
 * 排队两条。所以「第一次」「不同文本」「过了窗口」「时钟回拨」都必须放行。
 */
class AgentSubmitDedupPolicyTest {

    @Test
    fun firstSubmitIsNeverDuplicate() {
        assertFalse(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = null,
                previousAtMs = 0L,
                text = "你好",
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun sameTextInsideWindowIsDuplicate() {
        assertTrue(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = "你好",
                previousAtMs = 1_000L,
                text = "你好",
                nowMs = 1_000L,
            ),
        )
        assertTrue(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = "你好",
                previousAtMs = 1_000L,
                text = "你好",
                nowMs = 1_000L + AgentSubmitDedupPolicy.DEFAULT_WINDOW_MS,
            ),
        )
    }

    @Test
    fun sameTextAfterWindowIsAllowed() {
        assertFalse(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = "你好",
                previousAtMs = 1_000L,
                text = "你好",
                nowMs = 1_000L + AgentSubmitDedupPolicy.DEFAULT_WINDOW_MS + 1,
            ),
        )
    }

    @Test
    fun differentTextIsNeverDuplicate() {
        assertFalse(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = "你好",
                previousAtMs = 1_000L,
                text = "你好，再补一句",
                nowMs = 1_001L,
            ),
        )
    }

    @Test
    fun clockGoingBackwardsIsNotTreatedAsDoubleTap() {
        // 用户改系统时间 / NTP 校正：已保存的时刻比现在还晚，那不是「刚点过」。
        assertFalse(
            AgentSubmitDedupPolicy.isDuplicate(
                previousText = "你好",
                previousAtMs = 5_000L,
                text = "你好",
                nowMs = 1_000L,
            ),
        )
    }
}
