package io.github.mangi.eta.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AgentRunTraceStore.Trace.lastFirstTokenMs]：从追踪条目里取「最近一次请求的首字延迟」。
 *
 * 这个值取代了消息表上那条采集路径 —— 后者在多轮 + 工具调用时实测全是 0。
 * 所以这里的配对规则必须钉死，尤其是「不能跨请求配对」这一条。
 */
class TraceFirstTokenTest {

    private fun entry(
        kind: String,
        at: Long,
        round: Int? = null,
    ) = AgentRunTraceStore.Entry(kind = kind, at = at, round = round)

    private fun trace(vararg entries: AgentRunTraceStore.Entry) = AgentRunTraceStore.Trace(
        runId = "run-x",
        model = "m",
        provider = "p",
        purpose = "chat",
        startedAt = entries.firstOrNull()?.at ?: 0L,
        finishedAt = entries.lastOrNull()?.at,
        ok = true,
        error = null,
        entries = entries.toList(),
        truncated = false,
    )

    @Test
    fun `单轮 request 到 first 的差就是首字延迟`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 1_450, round = 1),
        )
        assertEquals(450L, t.lastFirstTokenMs)
    }

    @Test
    fun `取最近一轮，不是第一轮`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 0, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 100, round = 1),
            entry(AgentRunTraceStore.KIND_TOOL, 200, round = 1),
            entry(AgentRunTraceStore.KIND_REQUEST, 5_000, round = 2),
            entry(AgentRunTraceStore.KIND_FIRST, 5_800, round = 2),
        )
        assertEquals("应当取第 2 轮（最近一次请求）", 800L, t.lastFirstTokenMs)
    }

    @Test
    fun `不会把上一轮的 first 配给下一轮的 request`() {
        // 第 2 轮没有 first（请求失败/中断）：不能退回去用第 1 轮的，
        // 那会算出一个横跨两轮的、看起来正常但其实无意义的数。
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 0, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 300, round = 1),
            entry(AgentRunTraceStore.KIND_REQUEST, 9_000, round = 2),
        )
        assertEquals("第 2 轮没有首字，应当退回第 1 轮的真实值", 300L, t.lastFirstTokenMs)
    }

    @Test
    fun `完全没有 first 时返回 null 而不是 0`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 0, round = 1),
            entry(AgentRunTraceStore.KIND_TOOL, 100, round = 1),
        )
        assertNull("没有首字就是没有，0 会被界面读成「瞬间响应」", t.lastFirstTokenMs)
    }

    @Test
    fun `轮次号缺失时按位置顺序配对`() {
        // 旧追踪可能没有 round 字段：只靠「本次 request 之后的第一条 first」配对。
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000),
            entry(AgentRunTraceStore.KIND_FIRST, 1_250),
        )
        assertEquals(250L, t.lastFirstTokenMs)
    }

    @Test
    fun `first 早于 request 的异常数据不返回负值`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 900, round = 1),
        )
        assertNull("时间倒流的记录不可信，宁可不显示", t.lastFirstTokenMs)
    }

    @Test
    fun `多次请求之间有 first 残留时取配对正确的那一组`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 0, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 200, round = 1),
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 2),
            entry(AgentRunTraceStore.KIND_FIRST, 1_600, round = 2),
            entry(AgentRunTraceStore.KIND_REQUEST, 3_000, round = 3),
            entry(AgentRunTraceStore.KIND_FIRST, 3_500, round = 3),
        )
        assertEquals(500L, t.lastFirstTokenMs)
    }
}
