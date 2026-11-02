package io.github.mangi.eta.agent.trace

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRunTraceStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val runId = "run-3f6d9a44-1c2b-4c3d-9e8f-0a1b2c3d4e5f"

    private fun store(): AgentRunTraceStore {
        AgentRunTraceStore.init(temporary.root, AgentRunTraceStore.Retention(maxRuns = 5, maxTotalBytes = 1L shl 20, maxRunBytes = 1L shl 20))
        return AgentRunTraceStore
    }

    private fun beginTrace(id: String = runId) {
        store().begin(
            runId = id,
            model = "gpt-x",
            provider = "openai",
            purpose = "chat",
            contextWindow = 128_000,
        )
    }

    @Test
    fun `records a run with rounds tools retries and usage`() {
        beginTrace()
        val store = store()
        store.record(runId, AgentEvent.RunStarted(0, 0, 12, false))
        store.record(runId, AgentEvent.RoundStarted(1, 8))
        store.record(runId, AgentEvent.ProviderRequestStarted(1))
        store.record(runId, AgentEvent.ProviderResponseStarted(1, 200))
        store.record(runId, AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.THINKING, 0, contentChars = 120))
        store.record(runId, AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = 40))
        store.record(runId, AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "rate_limit"))
        store.record(runId, AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = 1000, inputTokens = 1000, outputTokens = 200, reasoningTokens = 30, cachedTokens = 100)))
        store.recordTool(runId, 1, "grep_files", "{\"query\":\"x\"}", "{\"ok\":true}", 42, ok = true, imageCount = 0)
        store.finish(runId, ok = true, contentChars = 40)

        val trace = store.read(runId)
        assertNotNull(trace)
        trace!!
        assertEquals("gpt-x", trace.model)
        assertEquals("openai", trace.provider)
        assertEquals(1, trace.rounds)
        assertEquals(1, trace.toolCalls)
        assertEquals(0, trace.toolFailures)
        assertEquals(1, trace.retries)
        assertEquals(1200, trace.totalTokens)
        assertEquals(true, trace.ok)
        val tool = trace.entries.first { it.kind == AgentRunTraceStore.KIND_TOOL }
        assertEquals("grep_files", tool.name)
        assertEquals(42L, tool.elapsedMs)
        assertEquals("{\"query\":\"x\"}", tool.arguments)
        assertEquals(11, tool.resultChars)
    }

    @Test
    fun `usage lines keep the provider prompt total`() {
        beginTrace()
        val store = store()
        store.record(
            runId,
            AgentEvent.UsageReceived(
                1,
                AgentTokenUsage(
                    contextTokens = 1_000,
                    inputTokens = 200,
                    outputTokens = 200,
                    cachedTokens = 800,
                    promptTokens = 1_000,
                ),
            ),
        )
        store.finish(runId, ok = true)

        val trace = store.read(runId)!!
        assertEquals(1_200, trace.totalTokens)
        assertEquals(1_000, trace.entries.first { it.kind == AgentRunTraceStore.KIND_USAGE }.promptTokens)
        // 列表页走的是另一条路径（直接扫 JSONL），两边必须给同一个数字。
        assertEquals(1_200, store.list(10).single().totalTokens)
    }

    @Test
    fun `legacy usage lines without prompt fall back to input plus output`() {
        // 加 prompt 之前的追踪没有这个键：总量按旧口径 input + output，
        // 不能把 cache 加进去 —— 旧记录里 cached 是单列的，不属于 input 的子集。
        File(temporary.root, "run-legacy.jsonl").writeText(
            """
            {"k":"meta","id":"run-legacy","at":1000,"model":"m","provider":"p","purpose":"chat"}
            {"k":"usage","at":1500,"r":1,"ctx":1000,"in":100,"out":50,"cache":300}
            {"k":"done","at":2000,"ok":true}
            """.trimIndent() + "\n",
        )
        store()
        val trace = AgentRunTraceStore.read("run-legacy")!!
        assertEquals(150, trace.totalTokens)
        assertEquals(150, AgentRunTraceStore.list(10).single().totalTokens)
    }

    @Test
    fun `total tokens agree across trace summary and trajectory`() {
        beginTrace()
        val store = store()
        store.record(runId, AgentEvent.RoundStarted(1, 2))
        store.record(
            runId,
            AgentEvent.UsageReceived(
                1,
                AgentTokenUsage(inputTokens = 200, outputTokens = 100, cachedTokens = 800, promptTokens = 1_000),
            ),
        )
        store.record(runId, AgentEvent.RoundStarted(2, 3))
        store.record(
            runId,
            AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 100, outputTokens = 50, cachedTokens = 0)),
        )
        store.finish(runId, ok = true)

        val trace = store.read(runId)!!
        val summary = store.list(10).single()
        val trajectory = AgentRunTrajectory.build(trace)
        // 详情、列表、轨迹三条路径各算一遍：同一份记录必须给同一个数。
        assertEquals(1_250, trace.totalTokens)
        assertEquals(trace.totalTokens, summary.totalTokens)
        assertEquals(trace.totalTokens, trajectory.turns.sumOf { it.tokens })
    }

    @Test
    fun `records one first token per request even when blocks keep starting`() {
        beginTrace()
        val store = store()
        store.record(runId, AgentEvent.ProviderRequestStarted(1))
        // 一次请求里思考、正文、工具调用各会来一个「块开始」，但首字只有一个。
        store.record(runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0))
        store.record(runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TEXT, 1))
        store.record(runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 2, name = "grep"))
        // 重试是另一次请求：它的首字要单独记，否则速度按请求配对时会漏掉这一段。
        store.record(runId, AgentEvent.ProviderRequestStarted(2))
        store.record(runId, AgentEvent.AssistantBlockStart(2, AgentEvent.AssistantBlockKind.TEXT, 0))
        store.finish(runId, ok = true)

        val firsts = store.read(runId)!!.entries.filter { it.kind == AgentRunTraceStore.KIND_FIRST }
        assertEquals(2, firsts.size)
        assertEquals(listOf(1, 2), firsts.map { it.round })
    }

    @Test
    fun `compaction keeps char counts and does not fake zero tokens`() {
        beginTrace()
        val store = store()
        store.record(runId, AgentEvent.ContextCompaction(
            operationId = "op",
            phase = "completed",
            charsBefore = 56_185,
            charsAfter = 10_698,
            detail = "摘要 10660 字符；上下文 56185 → 10698 字符",
        ))
        store.finish(runId, ok = true)

        val entry = checkNotNull(store.read(runId)).entries.first { it.kind == AgentRunTraceStore.KIND_COMPACT }
        assertEquals(56_185, entry.charsBefore)
        assertEquals(10_698, entry.charsAfter)
        // 没有远端 usage 时不要写 0：0 → 0 会让「这次压缩」看起来什么都没做（真机就是这样）。
        assertNull(entry.before)
        assertNull(entry.after)
    }

    @Test
    fun `sensitive tool keeps metadata but drops arguments and result`() {
        beginTrace()
        store().recordTool(
            runId = runId,
            round = 1,
            name = "memory_get",
            arguments = "{\"query\":\"secret\"}",
            result = "secret content",
            elapsedMs = 5,
            ok = true,
            sensitive = true,
        )
        val tool = store().read(runId)!!.entries.first { it.kind == AgentRunTraceStore.KIND_TOOL }
        assertNull(tool.arguments)
        assertNull(tool.result)
        assertEquals("memory_get", tool.name)
    }

    @Test
    fun `rejects unsafe run ids instead of writing outside the directory`() {
        val store = store()
        store.begin(runId = "../../escape", model = "m", provider = "p")
        store.finish("../../escape", ok = true)
        val files = temporary.root.listFiles().orEmpty()
        assertEquals(0, files.size)
    }

    @Test
    fun `lists newest runs with aggregate fields`() {
        val store = store()
        beginTrace(runId)
        store.record(runId, AgentEvent.RoundStarted(1, 2))
        store.recordTool(runId, 1, "terminal", "{}", "{}", 3, ok = false)
        store.finish(runId, ok = false, error = "boom")

        val second = "run-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        store.begin(second, "m2", "p2")
        store.finish(second, ok = true)

        val list = store.list(10)
        assertEquals(2, list.size)
        val target = list.first { it.runId == runId }
        assertEquals(1, target.rounds)
        assertEquals(1, target.toolFailures)
        assertEquals("boom", target.error)
    }

    @Test
    fun `truncates oversized tool arguments but keeps original length`() {
        beginTrace()
        val huge = "a".repeat(10_000)
        store().recordTool(runId, 1, "terminal", huge, "ok", 1, ok = true)
        val tool = store().read(runId)!!.entries.first { it.kind == AgentRunTraceStore.KIND_TOOL }
        assertEquals(4_000, tool.arguments?.length)
    }

    @Test
    fun `delete removes only the target run`() {
        val store = store()
        beginTrace()
        store.finish(runId, ok = true)
        val other = "run-bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
        store.begin(other, "m", "p")
        store.finish(other, ok = true)

        store.delete(runId)
        assertNull(store.read(runId))
        assertNotNull(store.read(other))
        assertFalse(File(temporary.root, "$runId.jsonl").exists())
    }

    @Test
    fun `retention keeps the most recent runs`() {
        val store = AgentRunTraceStore
        AgentRunTraceStore.init(temporary.root, AgentRunTraceStore.Retention(maxRuns = 2, maxTotalBytes = 1L shl 20, maxRunBytes = 1L shl 20))
        val ids = (1..4).map { "run-00000000-0000-0000-0000-00000000000$it" }
        ids.forEach { id ->
            store.begin(id, "m", "p")
            store.finish(id, ok = true)
            Thread.sleep(5)
        }
        val list = store.list(10)
        assertEquals(2, list.size)
        assertTrue(list.none { it.runId == ids.first() })
    }
}
