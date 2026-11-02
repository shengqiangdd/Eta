package io.github.mangi.eta.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunTrajectoryTest {

    private fun entry(
        kind: String,
        at: Long,
        round: Int? = null,
        name: String? = null,
        ok: Boolean? = null,
        elapsedMs: Long? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        promptTokens: Int? = null,
        messageCount: Int? = null,
    ) = AgentRunTraceStore.Entry(
        kind = kind,
        at = at,
        round = round,
        messageCount = messageCount,
        name = name,
        ok = ok,
        elapsedMs = elapsedMs,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        promptTokens = promptTokens,
    )

    private fun trace(vararg entries: AgentRunTraceStore.Entry) = AgentRunTraceStore.Trace(
        runId = "run-1",
        model = "test-model",
        provider = "test",
        purpose = "chat",
        startedAt = 1_000,
        finishedAt = 10_000,
        ok = true,
        error = null,
        entries = entries.toList(),
        truncated = false,
    )

    @Test
    fun `splits turns by round records`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_START, 1_000),
                entry(AgentRunTraceStore.KIND_ROUND, 1_100, round = 1, messageCount = 5),
                entry(AgentRunTraceStore.KIND_REQUEST, 1_200),
                entry(AgentRunTraceStore.KIND_RESPONSE, 1_500),
                entry(AgentRunTraceStore.KIND_TOOL, 1_900, round = 1, name = "terminal", ok = true, elapsedMs = 800),
                entry(AgentRunTraceStore.KIND_ROUND, 2_100, round = 2, messageCount = 8),
                entry(AgentRunTraceStore.KIND_REQUEST, 2_200),
                entry(AgentRunTraceStore.KIND_TOOL, 2_600, round = 2, name = "read_file", ok = false, elapsedMs = 200),
                entry(AgentRunTraceStore.KIND_DONE, 3_000, ok = true),
            ),
        )
        assertEquals(2, trajectory.turns.size)
        assertEquals(1, trajectory.turns[0].toolCalls)
        assertEquals(0, trajectory.turns[0].toolFailures)
        assertEquals(1, trajectory.turns[1].toolCalls)
        assertEquals(1, trajectory.turns[1].toolFailures)
        assertEquals(5, trajectory.turns[0].messageCount)
        assertEquals(8, trajectory.turns[1].messageCount)
    }

    @Test
    fun `tool segment uses completion time minus elapsed`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_TOOL, 5_000, round = 1, name = "terminal", ok = true, elapsedMs = 800),
            ),
        )
        val tool = trajectory.segments.single { it.kind == AgentRunTraceStore.KIND_TOOL }
        // startedAt=1000，工具 4200..5000
        assertEquals(3_200, tool.startMs)
        assertEquals(4_000, tool.endMs)
        assertEquals(800, tool.durationMs)
    }

    @Test
    fun `counts tokens per turn`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_ROUND, 1_000, round = 1),
                entry(AgentRunTraceStore.KIND_USAGE, 1_500, round = 1, inputTokens = 100, outputTokens = 40),
                entry(AgentRunTraceStore.KIND_ROUND, 2_000, round = 2),
                entry(AgentRunTraceStore.KIND_USAGE, 2_500, round = 2, inputTokens = 200, outputTokens = 60),
            ),
        )
        assertEquals(140, trajectory.turns[0].tokens)
        assertEquals(260, trajectory.turns[1].tokens)
    }

    @Test
    fun `turn tokens include the reported prompt total`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_ROUND, 1_000, round = 1),
                entry(
                    AgentRunTraceStore.KIND_USAGE,
                    1_500,
                    round = 1,
                    inputTokens = 200,
                    outputTokens = 100,
                    promptTokens = 1_000,
                ),
            ),
        )
        assertEquals(1_100, trajectory.turns[0].tokens)
    }

    @Test
    fun `turn tokens fall back to input plus output without a prompt total`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_ROUND, 1_000, round = 1),
                entry(AgentRunTraceStore.KIND_USAGE, 1_500, round = 1, inputTokens = 100, outputTokens = 50),
            ),
        )
        assertEquals(150, trajectory.turns[0].tokens)
    }

    @Test
    fun `empty trace yields empty trajectory`() {
        val trajectory = AgentRunTrajectory.build(trace())
        assertTrue(trajectory.turns.isEmpty())
        assertEquals(0, trajectory.totalMs)
    }

    @Test
    fun `filtering by turn keeps only that turn segments`() {
        val trajectory = AgentRunTrajectory.build(
            trace(
                entry(AgentRunTraceStore.KIND_ROUND, 1_000, round = 1),
                entry(AgentRunTraceStore.KIND_TOOL, 1_400, round = 1, name = "a", ok = true, elapsedMs = 100),
                entry(AgentRunTraceStore.KIND_ROUND, 2_000, round = 2),
                entry(AgentRunTraceStore.KIND_TOOL, 2_500, round = 2, name = "b", ok = true, elapsedMs = 100),
            ),
        )
        val second = AgentRunTrajectory.segmentsOf(trajectory, 1)
        assertTrue(second.isNotEmpty())
        assertTrue(second.filter { it.kind == AgentRunTraceStore.KIND_TOOL }.all { it.label == "b" })
    }

    // ---- 输出速度（tok/s）----

    private fun speedTrace(): AgentRunTraceStore.Trace = trace(
        entry(AgentRunTraceStore.KIND_ROUND, 1_000, round = 1),
        entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
        // 首字 1200：解码窗口从这里起算，请求发出到首字的等待不算速度。
        entry(AgentRunTraceStore.KIND_FIRST, 1_200, round = 1),
        entry(
            AgentRunTraceStore.KIND_USAGE,
            3_000,
            round = 1,
            inputTokens = 900,
            outputTokens = 100,
        ),
        // 工具耗时 1s：它不属于模型生成，速度不能把它摊进去。
        entry(
            AgentRunTraceStore.KIND_TOOL,
            4_000,
            round = 1,
            name = "terminal",
            ok = true,
            elapsedMs = 1_000,
        ),
        entry(AgentRunTraceStore.KIND_ROUND, 6_000, round = 2),
        entry(AgentRunTraceStore.KIND_REQUEST, 6_000, round = 2),
        entry(AgentRunTraceStore.KIND_FIRST, 6_200, round = 2),
        entry(
            AgentRunTraceStore.KIND_USAGE,
            7_500,
            round = 2,
            inputTokens = 1_000,
            outputTokens = 50,
        ),
        entry(AgentRunTraceStore.KIND_DONE, 10_000, round = 2),
    )

    @Test
    fun `model time ends where the tool starts not where its record was written`() {
        val t = speedTrace()
        assertEquals(150, t.outputTokens)
        // 第 1 轮 1000→3000（工具**开始**执行）=2000；第 2 轮 6000→10000=4000。
        // 工具记录写在执行结束时（at=4000, ms=1000），把它直接当边界会把整段工具墙钟算进来。
        assertEquals(6_000, t.modelMs)
    }

    @Test
    fun `decode speed excludes the wait before the first byte`() {
        // 请求 1000 发出、1500 才等到响应头、3000 生成完：真正的解码只有 1500ms。
        // 拿"请求→结束"当分母，等于把网络与首字延迟摊进速度，数字会明显偏低。
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
            entry(AgentRunTraceStore.KIND_RESPONSE, 1_500, round = 1),
            entry(AgentRunTraceStore.KIND_USAGE, 3_000, round = 1, outputTokens = 100),
        )
        assertEquals(100 * 1000.0 / 1_500, t.outputTokensPerSecond!!, 1e-9)
    }

    @Test
    fun `usage reported after the tool still counts for that request`() {
        // 有些供应商把 usage 放在工具调用之后（同一请求内）。
        // 若按「生成结束」的边界一并截断，这 100 token 会被静静丢掉，速度就虚高了。
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
            entry(AgentRunTraceStore.KIND_FIRST, 1_200, round = 1),
            entry(AgentRunTraceStore.KIND_TEXT, 2_800, round = 1),
            entry(AgentRunTraceStore.KIND_TOOL, 3_300, round = 1, name = "terminal", ok = true, elapsedMs = 500),
            entry(AgentRunTraceStore.KIND_USAGE, 3_400, round = 1, outputTokens = 100),
            entry(AgentRunTraceStore.KIND_REQUEST, 4_000, round = 2),
            entry(AgentRunTraceStore.KIND_DONE, 5_000),
        )
        // 分母仍是 1200→2800（不含工具），但 token 必须算进来。
        assertEquals(100 * 1000.0 / 1_600, t.outputTokensPerSecond!!, 1e-9)
    }

    @Test
    fun `speed is null when the provider reports no usage`() {
        val t = trace(
            entry(AgentRunTraceStore.KIND_REQUEST, 1_000, round = 1),
            entry(AgentRunTraceStore.KIND_DONE, 5_000, round = 1),
        )
        assertEquals(0, t.outputTokens)
        assertNull(t.outputTokensPerSecond)
    }

    @Test
    fun `speed is null rather than zero when there is no elapsed data`() {
        // 只有用量、没有请求记录：算不出时间，就该显示「—」，不能用 0 冒充。
        val t = trace(
            entry(AgentRunTraceStore.KIND_USAGE, 1_000, round = 1, outputTokens = 42),
        )
        assertEquals(42, t.outputTokens)
        assertEquals(0, t.modelMs)
        assertNull(t.outputTokensPerSecond)
    }

    @Test
    fun `turn speed uses the same window as the run total`() {
        val t = speedTrace()
        val trajectory = AgentRunTrajectory.build(t)
        assertEquals(2, trajectory.turns.size)

        val first = trajectory.turns[0]
        assertEquals(100, first.outputTokens)
        assertEquals(2_000, first.modelMs)
        // 解码 1200→3000：既不含 1000→1200 的首字等待，也不含后面的工具耗时。
        assertEquals(100 * 1000.0 / 1_800, first.tokensPerSecond!!, 1e-9)

        val second = trajectory.turns[1]
        assertEquals(50, second.outputTokens)
        assertEquals(4_000, second.modelMs)
        assertEquals(50 * 1000.0 / 1_300, second.tokensPerSecond!!, 1e-9)

        // 单轮之和必须等于总览，否则两处会给出互相矛盾的速度。
        assertEquals(t.modelMs, trajectory.turns.sumOf { it.modelMs })
        assertEquals(t.outputTokens, trajectory.turns.sumOf { it.outputTokens })
        assertEquals(t.speed.decodeMs, trajectory.turns.sumOf { it.decodeMs })
        assertEquals(t.speed.tokens, trajectory.turns.sumOf { it.decodeTokens })
    }
}
