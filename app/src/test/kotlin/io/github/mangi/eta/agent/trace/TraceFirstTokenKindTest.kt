package io.github.mangi.eta.agent.trace

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/**
 * 首字只认「用户能看到字」的块。
 *
 * 真机统计：451 条 `first` 记录后面紧跟 usage 再紧跟 tool —— 那些轮次一个字都没输出，
 * 纯工具参数生成。把它们算作首字有两个后果：
 * 1. TTFT 偏小（工具参数开始生成，不是用户看到第一个字）；
 * 2. `speedOf` 的分子仍取该请求的 output token，于是这段耗时被当成解码时间，速度虚高。
 */
class TraceFirstTokenKindTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun freshDir(): File {
        val d = temp.newFolder("traces")
        AgentRunTraceStore.init(d)
        return d
    }

    @After
    fun cleanup() {
        AgentRunTraceStore.resetForTests()
    }

    private fun block(kind: AgentEvent.AssistantBlockKind, round: Int = 1) =
        AgentEvent.AssistantBlockStart(round = round, kind = kind, index = 0)

    @Test
    fun `思考块开始即首字`() {
        freshDir()
        AgentRunTraceStore.begin("run-t", "m", "p", conversationId = "c")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))
        Thread.sleep(10)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.THINKING))

        val trace = AgentRunTraceStore.read("run-t")
        assertNotNull("思考块是可见内容，应当产生首字记录", trace?.lastFirstTokenMs)
    }

    @Test
    fun `正文块开始也记首字`() {
        freshDir()
        AgentRunTraceStore.begin("run-t", "m", "p", conversationId = "c")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))
        Thread.sleep(10)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.TEXT))

        assertNotNull(AgentRunTraceStore.read("run-t")?.lastFirstTokenMs)
    }

    @Test
    fun `工具调用块不算首字`() {
        freshDir()
        AgentRunTraceStore.begin("run-t", "m", "p", conversationId = "c")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))
        Thread.sleep(10)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.TOOL_CALL))
        AgentRunTraceStore.record(
            "run-t",
            AgentEvent.UsageReceived(round = 1, usage = AgentTokenUsage(outputTokens = 127, inputTokens = 9)),
        )

        assertNull(
            "只调工具、一个字都没输出，不该有首字延迟 —— 否则 TTFT 偏小且速度虚高",
            AgentRunTraceStore.read("run-t")?.lastFirstTokenMs,
        )
    }

    @Test
    fun `工具块之后再出现正文块，仍以正文块为首字`() {
        freshDir()
        AgentRunTraceStore.begin("run-t", "m", "p", conversationId = "c")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))

        Thread.sleep(10)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.TOOL_CALL))
        Thread.sleep(30)
        val firstTextAt = System.currentTimeMillis()
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.TEXT))

        val ttft = AgentRunTraceStore.read("run-t")?.lastFirstTokenMs
        assertNotNull("工具块跳过后，正文块应当补上首字", ttft)
        // 不是从 request 起算的 10ms，而是接近「工具块→正文块」的 30ms
        assertEquals("首字应记在正文块，不是工具块", true, ttft!! >= 25)
        assertEquals(true, firstTextAt > 0)
    }

    @Test
    fun `同一轮多个块只记第一次`() {
        freshDir()
        AgentRunTraceStore.begin("run-t", "m", "p", conversationId = "c")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))
        Thread.sleep(10)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.THINKING))
        Thread.sleep(50)
        AgentRunTraceStore.record("run-t", block(AgentEvent.AssistantBlockKind.TEXT))
        Thread.sleep(50)

        val ttft = AgentRunTraceStore.read("run-t")?.lastFirstTokenMs
        assertNotNull(ttft)
        assertEquals("第二个块不该覆盖首字时刻", true, ttft!! < 40)
    }
}
