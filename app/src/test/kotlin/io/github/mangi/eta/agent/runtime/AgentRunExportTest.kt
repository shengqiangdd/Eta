package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunExportTest {

    private fun trace(
        ok: Boolean? = true,
        error: String? = null,
        entries: List<AgentRunTraceStore.Entry>,
        truncated: Boolean = false,
    ) = AgentRunTraceStore.Trace(
        runId = "run-abc",
        model = "test-model",
        provider = "test-provider",
        purpose = "chat",
        startedAt = 1_000L,
        finishedAt = 2_500L,
        ok = ok,
        error = error,
        entries = entries,
        truncated = truncated,
    )

    private fun round(number: Int, at: Long) = AgentRunTraceStore.Entry(
        kind = AgentRunTraceStore.KIND_ROUND,
        at = at,
        round = number,
        messageCount = 3,
    )

    private fun tool(
        name: String,
        at: Long,
        ok: Boolean = true,
        elapsedMs: Long = 800L,
        result: String = "{}",
        arguments: String = "{}",
    ) = AgentRunTraceStore.Entry(
        kind = AgentRunTraceStore.KIND_TOOL,
        at = at,
        name = name,
        ok = ok,
        elapsedMs = elapsedMs,
        arguments = arguments,
        result = result,
    )

    @Test
    fun headerSummarizesOutcomeAndCounts() {
        val markdown = AgentRunExport.markdown(
            trace(
                entries = listOf(
                    round(1, 1_000L),
                    tool("terminal", 1_100L),
                    tool("read_file", 1_400L, ok = false, result = """{"ok":false,"error":"ENOENT"}"""),
                ),
            ),
        )
        assertTrue(markdown.startsWith("# Eta 运行记录"))
        assertTrue(markdown.contains("- 运行 ID：`run-abc`"))
        assertTrue(markdown.contains("`test-model`（test-provider）"))
        assertTrue(markdown.contains("- 类型：对话"))
        assertTrue(markdown.contains("- 结果：成功"))
        assertTrue(markdown.contains("2 次工具（失败 1）"))
        assertTrue(markdown.contains("### 第 1 轮"))
        assertTrue(markdown.contains("工具 `terminal` · 800ms · 成功"))
        assertTrue(markdown.contains("工具 `read_file` · 800ms · 失败"))
    }

    @Test
    fun usageLineShowsThePromptTotalWhenReported() {
        val markdown = AgentRunExport.markdown(
            trace(
                entries = listOf(
                    round(1, 1_000L),
                    AgentRunTraceStore.Entry(
                        kind = AgentRunTraceStore.KIND_USAGE,
                        at = 1_200L,
                        round = 1,
                        contextTokens = 1_000,
                        inputTokens = 200,
                        outputTokens = 100,
                        cachedTokens = 800,
                        promptTokens = 1_000,
                    ),
                ),
            ),
        )
        // 有服务商总输入时写「总输入」，与运行头部的 totalTokens = prompt + output 对得上。
        assertTrue(markdown.contains("· 总输入 1000 · 输出 100 · 缓存 800"))
        assertFalse(markdown.contains("· 输入 200"))
    }

    @Test
    fun usageLineKeepsRawInputForLegacyTraces() {
        // 旧追踪没有 prompt：不能把原样 input 冒充成「总输入」，继续写「输入」。
        val markdown = AgentRunExport.markdown(
            trace(
                entries = listOf(
                    round(1, 1_000L),
                    AgentRunTraceStore.Entry(
                        kind = AgentRunTraceStore.KIND_USAGE,
                        at = 1_200L,
                        round = 1,
                        contextTokens = 1_000,
                        inputTokens = 200,
                        outputTokens = 100,
                        cachedTokens = 800,
                    ),
                ),
            ),
        )
        assertTrue(markdown.contains("· 输入 200 · 输出 100 · 缓存 800"))
    }

    @Test
    fun failureAndTruncationAreVisible() {
        val markdown = AgentRunExport.markdown(
            trace(
                ok = false,
                error = "MODEL_TIMEOUT",
                entries = listOf(
                    round(1, 1_000L),
                    AgentRunTraceStore.Entry(
                        kind = AgentRunTraceStore.KIND_DONE,
                        at = 2_000L,
                        ok = false,
                        error = "MODEL_TIMEOUT",
                    ),
                ),
                truncated = true,
            ),
        )
        assertTrue(markdown.contains("- 结果：失败：MODEL_TIMEOUT"))
        assertTrue(markdown.contains("超过单文件上限，后段缺失"))
        assertTrue(markdown.contains("运行结束 · MODEL_TIMEOUT"))
    }

    @Test
    fun fileChangesSectionShowsDiffAndBoundary() {
        val markdown = AgentRunExport.markdown(
            trace(entries = listOf(round(1, 1_000L))),
            changes = listOf(
                RunFileChange(
                    path = "/sdcard/a.kt",
                    tool = "edit_file",
                    added = 3,
                    removed = 1,
                    count = 2,
                    at = 1_500L,
                    diff = "  12| context\n  13|-old\n  13|+new",
                ),
            ),
        )
        assertTrue(markdown.contains("## 文件变更"))
        assertTrue(markdown.contains("共 1 个文件，+3/-1 行"))
        assertTrue(markdown.contains("（+3/-1，2 次）"))
        assertTrue(markdown.contains("  13|-old"))
        assertTrue(markdown.contains("shell 重定向与其他应用写的文件不在其中"))
    }

    @Test
    fun toolDetailsAreFlattenedToOneLine() {
        val markdown = AgentRunExport.markdown(
            trace(
                entries = listOf(
                    round(1, 1_000L),
                    tool(
                        "terminal",
                        1_200L,
                        arguments = "{\n  \"command\": \"ls -l\"\n}",
                        result = "line1\nline2\nline3",
                    ),
                ),
            ),
        )
        val line = markdown.lineSequence().first { it.contains("工具 `terminal`") }
        assertFalse("工具行不应带换行：$line", line.contains("\n"))
        assertTrue(line.contains("command"))
        assertTrue(line.contains("line1 line2 line3"))
    }

    @Test
    fun longRunStopsAtCharacterBudget() {
        val entries = ArrayList<AgentRunTraceStore.Entry>()
        entries.add(round(1, 1_000L))
        repeat(2_000) { index ->
            entries.add(tool("t$index", 1_100L + index * 10L, result = "x".repeat(200)))
        }
        val markdown = AgentRunExport.markdown(trace(entries = entries))
        assertTrue(markdown.length <= AgentRunExport.MAX_CHARS + 200)
        assertTrue(markdown.contains("后续内容已省略"))
    }

    @Test
    fun emptyTraceStillProducesReadableReport() {
        val markdown = AgentRunExport.markdown(trace(ok = null, entries = emptyList()))
        assertTrue(markdown.contains("- 结果：未记录（运行可能被中断）"))
        assertTrue(markdown.contains("没有留下可读的记录"))
    }

    @Test
    fun fileNameIsSafeAndTimestamped() {
        val name = AgentRunExport.fileName("../weird/id", at = 0L)
        assertTrue(name.startsWith("eta-run-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }
}
