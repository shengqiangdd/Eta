package io.github.mangi.eta.agent.usage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentUsageLedgerTest {
    @get:Rule val temporary = TemporaryFolder()

    private val day = 86_400_000L
    private val base = 1_700_000_000_000L

    private fun ledger(): AgentUsageLedger = AgentUsageLedger(temporary.root, { base }, java.time.ZoneOffset.UTC)

    private fun record(
        ts: Long,
        provider: String = "openai",
        model: String = "gpt-x",
        input: Int? = 100,
        output: Int? = 50,
        cached: Int? = 0,
        reasoning: Int? = 0,
    ) = AgentUsageLedger.Record(ts, provider, model, "chat", input, output, cached, reasoning)

    @Test
    fun `records aggregate by day and by model`() {
        val ledger = ledger()
        ledger.record(record(base, model = "gpt-x", input = 100, output = 50, cached = 20))
        ledger.record(record(base + 1_000, model = "gpt-y", input = 200, output = 80, reasoning = 10))

        val summary = ledger.summarize(days = 7, nowMs = base)
        assertEquals(2L, summary.totals.calls)
        assertEquals(300L, summary.totals.input)
        assertEquals(130L, summary.totals.output)
        assertEquals(20L, summary.totals.cached)
        assertEquals(10L, summary.totals.reasoning)
        assertEquals(430L, summary.totals.totalTokens)
        assertEquals(2, summary.byModel.size)
        assertEquals("gpt-y", summary.byModel.first().model)
        assertEquals(7, summary.byDay.size)
        assertTrue(summary.byDay.any { it.totals.calls == 2L })
    }

    @Test
    fun `records outside the window are excluded`() {
        val ledger = ledger()
        ledger.record(record(base - 10 * day, input = 9_999))
        ledger.record(record(base, input = 10))
        val summary = ledger.summarize(days = 3, nowMs = base)
        assertEquals(1L, summary.totals.calls)
        assertEquals(10L, summary.totals.input)
    }

    @Test
    fun `cache share is derived from cached and input`() {
        val ledger = ledger()
        ledger.record(record(base, input = 100, cached = 300))
        val summary = ledger.summarize(days = 1, nowMs = base)
        assertEquals(0.75, summary.totals.cacheShare, 0.0001)
    }

    @Test
    fun `corrupted lines do not break the summary`() {
        val ledger = ledger()
        ledger.record(record(base, input = 5, output = 5))
        File(temporary.root, "usage-2023-11.jsonl").appendText("not json\n{\"ts\":\n")
        val summary = ledger.summarize(days = 2, nowMs = base)
        assertEquals(1L, summary.totals.calls)
        assertEquals(5L, summary.totals.input)
    }

    @Test
    fun `missing directory yields an empty summary`() {
        val ledger = AgentUsageLedger(File(temporary.root, "missing"), { base }, java.time.ZoneOffset.UTC)
        val summary = ledger.summarize(days = 7, nowMs = base)
        assertEquals(0L, summary.totals.calls)
        assertTrue(summary.byDay.isEmpty())
        assertTrue(summary.byModel.isEmpty())
    }

    @Test
    fun `records are appended across instances`() {
        ledger().record(record(base, input = 1, output = 1))
        ledger().record(record(base, input = 2, output = 2))
        val summary = ledger().summarize(days = 1, nowMs = base)
        assertEquals(2L, summary.totals.calls)
        assertEquals(3L, summary.totals.input)
    }

    @Test
    fun `summarizes by purpose`() {
        val ledger = ledger()
        ledger.record(record(ts = base, input = 10, output = 5))
        ledger.record(AgentUsageLedger.Record(base + 1, "openai", "gpt-x", "compact", 20, 2, 0, 0))
        ledger.record(AgentUsageLedger.Record(base + 2, "openai", "gpt-x", "compact", 30, 3, 0, 0))

        val summary = ledger.summarize(7)
        val compact = summary.byPurpose.first { it.purpose == "compact" }
        assertEquals(2, compact.totals.calls)
        assertEquals(55, compact.totals.totalTokens)
        val chat = summary.byPurpose.first { it.purpose == "chat" }
        assertEquals(1, chat.totals.calls)
        assertEquals(70.0 / 3.0, summary.averageTokensPerCall, 0.01)
    }

    @Test
    fun `hourly buckets are only produced for short windows and padded`() {
        val ledger = ledger()
        val hour = 3_600_000L
        val now = base
        ledger.record(record(ts = now - 2 * hour, input = 10, output = 0))
        ledger.record(record(ts = now, input = 20, output = 0))

        val today = ledger.summarize(1, nowMs = now)
        assertEquals(25, today.hourly.size)
        val bucketAt = { ts: Long -> ts - (ts % hour) }
        assertEquals(10, today.hourly.first { it.hourStart == bucketAt(now - 2 * hour) }.totals.input)
        assertEquals(20, today.hourly.first { it.hourStart == bucketAt(now) }.totals.input)
        assertEquals(0, today.hourly.first { it.hourStart == bucketAt(now - hour) }.totals.input)

        val month = ledger.summarize(30, nowMs = now)
        assertTrue(month.hourly.isEmpty())
    }

    @Test
    fun `daily files and legacy monthly files are both read`() {
        val ledger = ledger()
        // 旧版按月文件（2026-10-25 之前的命名）仍然要能读。
        File(temporary.root, "usage-2023-11.jsonl").appendText(
            """{"ts":${base - 1_000},"provider":"openai","model":"gpt-x","purpose":"chat","in":7,"out":0,"cached":0,"reasoning":0}""" + "\n",
        )
        ledger.record(record(base, input = 3))

        val summary = ledger.summarize(days = 1, nowMs = base)
        assertEquals("月文件 + 今日日文件都要被读到", 2L, summary.totals.calls)
        assertEquals(10L, summary.totals.input)
    }

    @Test
    fun `files older than the retention window are pruned`() {
        val staleDay = File(temporary.root, "usage-2023-01-01.jsonl").apply { writeText("{}\n") }
        val staleMonth = File(temporary.root, "usage-2023-01.jsonl").apply { writeText("{}\n") }

        ledger().record(record(base, input = 1))

        assertFalse("超出保留期的日文件应被清理", staleDay.exists())
        assertFalse("超出保留期的月文件也应被清理", staleMonth.exists())
    }

    // ── 「最近调用」明细 ─────────────────────────────────

    @Test
    fun `recent returns newest first across day files`() {
        val ledger = ledger()
        ledger.record(record(base - day, input = 1, output = 0))
        ledger.record(record(base, input = 2, output = 0))
        ledger.record(record(base + 1_000, input = 3, output = 0))

        // 前一天在另一个文件里，但仍要按时间排到后面。
        assertEquals(listOf(3L, 2L, 1L), ledger.recent(limit = 10, nowMs = base).map { it.input })
    }

    @Test
    fun `recent honours the limit`() {
        val ledger = ledger()
        repeat(5) { index -> ledger.record(record(base + index, input = index)) }

        val entries = ledger.recent(limit = 3, nowMs = base)
        assertEquals(3, entries.size)
        assertEquals(listOf(4L, 3L, 2L), entries.map { it.input })
    }

    @Test
    fun `recent ignores records older than the lookback window`() {
        val ledger = ledger()
        ledger.record(record(base - 8 * day, input = 99))
        ledger.record(record(base, input = 1))

        assertEquals(listOf(1L), ledger.recent(limit = 10, nowMs = base).map { it.input })
    }

    @Test
    fun `recent skips malformed lines`() {
        val ledger = ledger()
        ledger.record(record(base, input = 7))
        File(temporary.root, "usage-2023-11-14.jsonl").appendText("garbage\n{\"ts\":\n")

        val entries = ledger.recent(limit = 5, nowMs = base)
        assertEquals(1, entries.size)
        assertEquals(7L, entries.first().input)
    }

    @Test
    fun `recent fills placeholders for blank fields`() {
        File(temporary.root, "usage-2023-11-14.jsonl").appendText(
            """{"ts":$base,"model":"","purpose":"","in":5}""" + "\n",
        )

        val entry = ledger().recent(limit = 1, nowMs = base).single()
        assertEquals("未知模型", entry.model)
        assertEquals("unknown", entry.purpose)
        assertEquals(5L, entry.input)
        assertEquals(0L, entry.output)
    }

    @Test
    fun `legacy compaction purpose is normalised`() {
        // 旧版本压缩入口写的是长名 compaction，其它入口写 compact：面板上必须归为同一条。
        File(temporary.root, "usage-2023-11-14.jsonl").appendText(
            """{"ts":$base,"model":"m","purpose":"compaction","in":10,"out":1}""" + "\n",
        )
        val ledger = ledger()
        ledger.record(AgentUsageLedger.Record(base + 1, "p", "m", "compact", 20, 2, 0, 0))

        val summary = ledger.summarize(1, nowMs = base)
        assertEquals(1, summary.byPurpose.size)
        assertEquals("compact", summary.byPurpose.first().purpose)
        assertEquals(2L, summary.byPurpose.first().totals.calls)

        assertEquals("compact", ledger.recent(limit = 5, nowMs = base).last().purpose)
    }

    @Test
    fun `recent is empty when the ledger has no records`() {
        val ledger = AgentUsageLedger(File(temporary.root, "missing"), { base }, java.time.ZoneOffset.UTC)
        assertTrue(ledger.recent(limit = 5, nowMs = base).isEmpty())
    }

}
