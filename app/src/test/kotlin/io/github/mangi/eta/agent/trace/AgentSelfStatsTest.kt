package io.github.mangi.eta.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSelfStatsTest {

    private fun tool(
        name: String,
        ok: Boolean = true,
        result: String? = null,
        at: Long = 1_000,
    ) = AgentRunTraceStore.Entry(
        kind = AgentRunTraceStore.KIND_TOOL,
        at = at,
        name = name,
        ok = ok,
        result = result,
    )

    private fun round(at: Long = 1_000) = AgentRunTraceStore.Entry(
        kind = AgentRunTraceStore.KIND_ROUND,
        at = at,
    )

    private fun trace(
        runId: String = "run",
        ok: Boolean? = true,
        vararg entries: AgentRunTraceStore.Entry,
    ) = AgentRunTraceStore.Trace(
        runId = runId,
        model = "m",
        provider = "p",
        purpose = "chat",
        startedAt = 1_000,
        finishedAt = 10_000,
        ok = ok,
        error = null,
        entries = entries.toList(),
        truncated = false,
    )

    // ---- failureCodeOf ----

    @Test
    fun `failureCode 只取 code 字段的字面量`() {
        assertEquals(
            "READ_REQUIRED",
            AgentSelfStats.failureCodeOf("""{"ok":false,"tool":"edit_file","code":"READ_REQUIRED","message":"先读"}"""),
        )
        // 紧凑写法（无空格）也必须能读出来。
        assertEquals("FILE_CHANGED", AgentSelfStats.failureCodeOf("""{"ok":false,"code":"FILE_CHANGED"}"""))
    }

    @Test
    fun `failureCode 不会把 message 里的词当成 code`() {
        // message 里出现 code 字样、但没有 code 字段时，必须返回 null。
        assertNull(AgentSelfStats.failureCodeOf("""{"ok":false,"message":"missing code"}}"""))
        // code 出现在 message 之后也不行：只认 "code" 这个键。
        assertNull(AgentSelfStats.failureCodeOf("""{"ok":false,"message":"see \"code\" later"}"""))
    }

    @Test
    fun `failureCode 对空与非法输入返回 null`() {
        assertNull(AgentSelfStats.failureCodeOf(null))
        assertNull(AgentSelfStats.failureCodeOf(""))
        assertNull(AgentSelfStats.failureCodeOf("   "))
        assertNull(AgentSelfStats.failureCodeOf("not json"))
        assertNull(AgentSelfStats.failureCodeOf("""{"code":}"""))
        assertNull(AgentSelfStats.failureCodeOf("""{"code":""}"""))
    }

    // ---- 聚合 ----

    @Test
    fun `空输入不算有数据`() {
        val stats = AgentSelfStats.aggregate(emptyList())
        assertFalse(stats.hasData)
        assertEquals(0, stats.runs)
        assertEquals(0f, stats.toolFailureRate, 0.0001f)
    }

    @Test
    fun `只有纯对话时 hasData 为真但工具指标为零`() {
        val stats = AgentSelfStats.aggregate(listOf(trace(entries = arrayOf(round(), round()))))
        assertTrue(stats.hasData)
        assertEquals(1, stats.runs)
        assertEquals(0, stats.runsWithTools)
        assertEquals(0, stats.toolCalls)
        assertEquals(0f, stats.toolFailureRate, 0.0001f)
    }

    @Test
    fun `按工具拆分调用数与失败数`() {
        val stats = AgentSelfStats.aggregate(
            listOf(
                trace(
                    entries = arrayOf(
                        tool("edit_file", ok = false, result = """{"ok":false,"code":"READ_REQUIRED"}"""),
                        tool("edit_file", ok = false, result = """{"ok":false,"code":"READ_REQUIRED"}"""),
                        tool("edit_file", ok = false, result = """{"ok":false,"code":"MATCH_NOT_FOUND"}"""),
                        tool("edit_file"),
                        tool("terminal"),
                    ),
                ),
            ),
        )
        val edit = stats.tools.first { it.name == "edit_file" }
        assertEquals(4, edit.calls)
        assertEquals(3, edit.failures)
        assertEquals(0.75f, edit.failureRate, 0.0001f)
        // 失败码按次数降序：READ_REQUIRED 出现两次，排第一。
        assertEquals(listOf("READ_REQUIRED", "MATCH_NOT_FOUND"), edit.failureCodes.keys.toList())
        assertEquals(2, edit.failureCodes["READ_REQUIRED"])
    }

    @Test
    fun `失败码缺失时仍计失败但不进分组`() {
        val stats = AgentSelfStats.aggregate(
            listOf(trace(entries = arrayOf(tool("edit_file", ok = false, result = "")))),
        )
        val edit = stats.tools.single()
        assertEquals(1, edit.failures)
        assertTrue(edit.failureCodes.isEmpty())
    }

    @Test
    fun `工具有序：失败多的在前，同失败数按调用多在前`() {
        val stats = AgentSelfStats.aggregate(
            listOf(
                trace(
                    entries = arrayOf(
                        tool("a", ok = false),
                        tool("b", ok = false),
                        tool("b", ok = false),
                        tool("c"),
                        tool("c"),
                        tool("c"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("b", "a", "c"), stats.tools.map { it.name })
    }

    @Test
    fun `topTools 截断不会丢掉总体计数`() {
        val entries = (1..10).map { tool("t$it") }.toTypedArray()
        val stats = AgentSelfStats.aggregate(listOf(trace(entries = entries)), topTools = 3)
        assertEquals(3, stats.tools.size)
        assertEquals(10, stats.toolCalls)
    }

    @Test
    fun `只读到部分明细时空转结论是不完整的`() {
        // 空转只能从全文看出来（头部只有轮数与工具数，没有「几轮 + 零工具」的组合判定）。
        // 所以当有 run 没展开时，一定要能把这件事说出来，而不是给出一个看似完整的结论。
        val stats = AgentSelfStats.aggregate(
            traces = emptyList(),
            summaries = listOf(
                summary("unread", toolCalls = 0, toolFailures = 0),
                summary("ok", toolCalls = 5, toolFailures = 0),
            ),
        )
        // 没有展开 → 一条空转都发现不了，这正是需要向用户说明的情况。
        assertTrue(stats.idleRuns.isEmpty())
        // 但「零工具调用」的 run 在头部是可见的，所以分母不至于失真。
        assertEquals(1, stats.runsWithTools)
        assertEquals(2, stats.runs)
    }

    @Test
    fun `空转只统计多轮且零工具调用的运行`() {
        val stats = AgentSelfStats.aggregate(
            listOf(
                // 2 轮 0 工具 → 空转
                trace(runId = "idle-a", entries = arrayOf(round(), round())),
                // 1 轮 0 工具 → 正常纯问答，不算空转
                trace(runId = "chat", entries = arrayOf(round())),
                // 2 轮 1 工具 → 有动作，不算空转
                trace(runId = "acted", entries = arrayOf(round(), round(), tool("terminal"))),
            ),
        )
        assertEquals(listOf("idle-a"), stats.idleRuns.map { it.runId })
        assertEquals(2, stats.idleRuns.single().rounds)
    }

    @Test
    fun `空转按轮数降序排列`() {
        val stats = AgentSelfStats.aggregate(
            listOf(
                trace(runId = "short", entries = Array(2) { round() }),
                trace(runId = "long", entries = Array(5) { round() }),
            ),
        )
        assertEquals(listOf("long", "short"), stats.idleRuns.map { it.runId })
    }

    @Test
    fun `重试 压缩 失败运行分别计数`() {
        val retry = AgentRunTraceStore.Entry(kind = AgentRunTraceStore.KIND_RETRY, at = 1_000, code = "HTTP_503")
        val compact = AgentRunTraceStore.Entry(kind = AgentRunTraceStore.KIND_COMPACT, at = 2_000)
        val stats = AgentSelfStats.aggregate(
            listOf(
                trace(
                    runId = "a",
                    ok = false,
                    entries = arrayOf(retry, retry, compact, tool("terminal", ok = false)),
                ),
                trace(runId = "b", ok = true, entries = arrayOf(tool("terminal"))),
            ),
        )
        assertEquals(2, stats.runs)
        assertEquals(2, stats.retries)
        assertEquals(1, stats.retryRuns)
        assertEquals(1, stats.compactions)
        assertEquals(1, stats.compactionRuns)
        assertEquals(1, stats.failedRuns)
        assertEquals(2, stats.runsWithTools)
        // 2 次调用里 1 次失败 → 50%。
        assertEquals(0.5f, stats.toolFailureRate, 0.0001f)
    }

    @Test
    fun `没有名字的工具归入未命名桶而不是被丢弃`() {
        val stats = AgentSelfStats.aggregate(listOf(trace(entries = arrayOf(tool("")))))
        assertEquals(1, stats.toolCalls)
        assertEquals("(未命名工具)", stats.tools.single().name)
    }

    // ---- 只读部分详情的路径（真实使用时的主路径）----

    // ---- 明细挑选（看哪些、看多少）----

    private fun summaryAt(
        runId: String,
        startedAt: Long,
        sizeBytes: Long,
        toolCalls: Int = 5,
        toolFailures: Int = 1,
    ) = summary("", toolCalls, toolFailures).copy(
        runId = runId,
        startedAt = startedAt,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `明细优先取最新的，而不是最大的`() {
        // 旧的大文件、新的小文件：应该选新的 —— 体积衡量的是「跑了多久」，不是「多值得看」。
        val old = summaryAt("old-big", startedAt = 1_000, sizeBytes = 900_000)
        val new = summaryAt("new-small", startedAt = 9_000, sizeBytes = 10_000)
        val picked = AgentSelfStats.pickForDetail(listOf(old, new), maxBytes = 100_000, maxTraces = 1)
        assertEquals(listOf("new-small"), picked.map { it.runId })
    }

    @Test
    fun `干净的运行不展开`() {
        val clean = summaryAt("clean", startedAt = 9_000, sizeBytes = 1_000, toolCalls = 10, toolFailures = 0)
        val dirty = summaryAt("dirty", startedAt = 1_000, sizeBytes = 1_000)
        val picked = AgentSelfStats.pickForDetail(listOf(clean, dirty), maxBytes = 100_000, maxTraces = 10)
        assertEquals(listOf("dirty"), picked.map { it.runId })
    }

    @Test
    fun `零工具调用的运行即使没失败也要展开（可能是空转）`() {
        val idle = summaryAt("idle", startedAt = 1_000, sizeBytes = 1_000, toolCalls = 0, toolFailures = 0)
        val picked = AgentSelfStats.pickForDetail(listOf(idle), maxBytes = 100_000, maxTraces = 10)
        assertEquals(listOf("idle"), picked.map { it.runId })
    }

    @Test
    fun `超出字节预算的单个运行会跳过，不撑破预算`() {
        val huge = summaryAt("huge", startedAt = 9_000, sizeBytes = 5_000_000)
        val small = summaryAt("small", startedAt = 1_000, sizeBytes = 1_000)
        val picked = AgentSelfStats.pickForDetail(listOf(huge, small), maxBytes = 100_000, maxTraces = 10)
        assertEquals(listOf("small"), picked.map { it.runId })
    }

    @Test
    fun `总字节不会超过预算`() {
        val list = (1..10).map { summaryAt("r$it", startedAt = it.toLong() * 1_000, sizeBytes = 400_000) }
        val picked = AgentSelfStats.pickForDetail(list, maxBytes = 1_000_000, maxTraces = 10)
        assertTrue(picked.sumOf { it.sizeBytes } <= 1_000_000)
        assertEquals(2, picked.size)
    }

    @Test
    fun `份数上限先到就停`() {
        val list = (1..10).map { summaryAt("r$it", startedAt = it.toLong() * 1_000, sizeBytes = 100) }
        val picked = AgentSelfStats.pickForDetail(list, maxBytes = 1_000_000, maxTraces = 3)
        assertEquals(3, picked.size)
        // 仍然是最新的三个，且按新到旧。
        assertEquals(listOf("r10", "r9", "r8"), picked.map { it.runId })
    }

    @Test
    fun `预算或份数非正时一条都不选`() {
        val list = listOf(summaryAt("r1", startedAt = 1_000, sizeBytes = 100))
        assertTrue(AgentSelfStats.pickForDetail(list, maxBytes = 0, maxTraces = 10).isEmpty())
        assertTrue(AgentSelfStats.pickForDetail(list, maxBytes = 1_000, maxTraces = 0).isEmpty())
    }

    private fun summary(
        runId: String,
        toolCalls: Int,
        toolFailures: Int,
        retries: Int = 0,
        ok: Boolean? = true,
    ) = AgentRunTraceStore.Summary(
        runId = runId,
        model = "m",
        provider = "p",
        purpose = "chat",
        startedAt = 1_000,
        finishedAt = 9_000,
        ok = ok,
        rounds = 1,
        toolCalls = toolCalls,
        toolFailures = toolFailures,
        retries = retries,
        totalTokens = 0,
        error = null,
        sizeBytes = 100,
    )

    @Test
    fun `总数用头部 meta，不会被只读完的子集低估`() {
        // 10 个 run、共 100 次调用、共 20 次失败；只展开了其中一个（3 次调用 3 次失败）。
        val summaries = (1..9).map { summary("ok-$it", toolCalls = 10, toolFailures = 2) } +
            summary("loaded", toolCalls = 10, toolFailures = 2)
        val loaded = listOf(
            trace(
                runId = "loaded",
                entries = arrayOf(
                    tool("edit_file", ok = false, result = """{"ok":false,"code":"READ_REQUIRED"}"""),
                    tool("edit_file", ok = false, result = """{"ok":false,"code":"READ_REQUIRED"}"""),
                    tool("edit_file", ok = false, result = """{"ok":false,"code":"MATCH_NOT_FOUND"}"""),
                ),
            ),
        )
        val stats = AgentSelfStats.aggregate(loaded, summaries)
        assertEquals(10, stats.runs)
        assertEquals(100, stats.toolCalls)
        assertEquals(20, stats.toolFailures)
        assertEquals(0.2f, stats.toolFailureRate, 0.0001f)
        // 计数不能把子集重复加进去。
        assertEquals(10, stats.runsWithTools)
        // 9 个 run 有失败但没展开（loaded 已展开）。
        assertEquals(9, stats.unreadFailureRuns)
    }

    @Test
    fun `失败码只来自展开的明细，未展开的失败不归因到具体工具`() {
        // 取舍：summary 里只有「有几个失败」，没有「哪个工具失败」。
        // 硬把它摊到具体工具上就是编造，所以 per-tool 只统计展开的那部分；
        // 剩余失败体现在整体分子和 unreadFailureRuns 上。
        val stats = AgentSelfStats.aggregate(
            traces = listOf(
                trace(
                    runId = "loaded",
                    entries = arrayOf(
                        tool("edit_file", ok = false, result = """{"code":"READ_REQUIRED"}"""),
                    ),
                ),
            ),
            summaries = listOf(summary("loaded", toolCalls = 5, toolFailures = 5)),
        )
        // 整体分母用头部数据：5 次调用 5 次失败。
        assertEquals(5, stats.toolCalls)
        assertEquals(5, stats.toolFailures)
        // per-tool 只记展开的那 1 次。
        val edit = stats.tools.single()
        assertEquals(1, edit.calls)
        assertEquals(1, edit.failures)
        assertEquals(mapOf("READ_REQUIRED" to 1), edit.failureCodes)
    }

    @Test
    fun `有失败但明细没读成时 unreadFailureRuns 会露出来`() {
        val stats = AgentSelfStats.aggregate(
            traces = emptyList(),
            summaries = listOf(
                summary("gone-a", toolCalls = 5, toolFailures = 2),
                summary("gone-b", toolCalls = 5, toolFailures = 1),
                summary("clean", toolCalls = 5, toolFailures = 0),
            ),
        )
        assertEquals(2, stats.unreadFailureRuns)
        assertEquals(3, stats.toolFailures)
    }

    @Test
    fun `全部展开时 unreadFailureRuns 为 0`() {
        val stats = AgentSelfStats.aggregate(
            traces = listOf(trace(runId = "a", entries = arrayOf(tool("terminal")))),
            summaries = listOf(summary("a", toolCalls = 1, toolFailures = 0)),
        )
        assertEquals(0, stats.unreadFailureRuns)
    }
}
