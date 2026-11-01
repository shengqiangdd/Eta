package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskLedgerTest {

    private fun record(
        id: String,
        status: AgentTaskStatus = AgentTaskStatus.RUNNING,
        startedAt: Long = 1_000L,
        summary: String? = null,
        kind: String = AgentTaskRecord.KIND_SUBAGENT,
    ) = AgentTaskRecord(
        id = id,
        kind = kind,
        title = "任务 $id",
        status = status,
        ref = null,
        summary = summary,
        startedAt = startedAt,
        endedAt = null,
    )

    @Test
    fun upsertReplacesSameIdAndKeepsNewestFirst() {
        val ledger = AgentTaskLedger()
            .upsert(record("a", startedAt = 100))
            .upsert(record("b", startedAt = 200))
            .upsert(record("a", startedAt = 300))

        assertEquals(listOf("a", "b"), ledger.entries.map { it.id })
        assertEquals(2, ledger.entries.size)
    }

    @Test
    fun finishUpdatesStatusAndKeepsPosition() {
        val ledger = AgentTaskLedger()
            .upsert(record("a", startedAt = 100))
            .upsert(record("b", startedAt = 200))
            .finish("a", AgentTaskStatus.COMPLETED, "全部通过", 500)!!

        assertEquals(listOf("b", "a"), ledger.entries.map { it.id })
        val done = ledger.entries.first { it.id == "a" }
        assertEquals(AgentTaskStatus.COMPLETED, done.status)
        assertEquals("全部通过", done.summary)
        assertEquals(500L, done.endedAt)
    }

    @Test
    fun finishUnknownIdReturnsNull() {
        assertNull(AgentTaskLedger().finish("missing", AgentTaskStatus.FAILED, "x", 1))
    }

    @Test
    fun finishTruncatesSummary() {
        val done = AgentTaskLedger()
            .upsert(record("a"))
            .finish("a", AgentTaskStatus.FAILED, "错".repeat(5_000), 2)!!
        assertEquals(AgentTaskRecord.MAX_SUMMARY_CHARS, done.entries.single().summary!!.length)
    }

    @Test
    fun ledgerKeepsAtMostMaxEntries() {
        var ledger = AgentTaskLedger()
        repeat(AgentTaskLedger.MAX_ENTRIES + 5) { index ->
            ledger = ledger.upsert(record("t$index", startedAt = index.toLong()))
        }
        assertEquals(AgentTaskLedger.MAX_ENTRIES, ledger.entries.size)
        // 留下的必须是最新的那批：0..4 被挤掉。
        assertFalse(ledger.entries.any { it.id == "t0" })
        assertTrue(ledger.entries.any { it.id == "t${AgentTaskLedger.MAX_ENTRIES + 4}" })
    }

    @Test
    fun filtersByStatus() {
        val ledger = AgentTaskLedger()
            .upsert(record("a", status = AgentTaskStatus.RUNNING, startedAt = 1))
            .upsert(record("b", status = AgentTaskStatus.COMPLETED, startedAt = 2))
            .upsert(record("c", status = AgentTaskStatus.COMPLETED, startedAt = 3))

        assertEquals(listOf("c", "b"), ledger.withStatus(AgentTaskStatus.COMPLETED).map { it.id })
        assertEquals(3, ledger.withStatus(null).size)
        assertTrue(ledger.withStatus(AgentTaskStatus.CANCELLED).isEmpty())
    }

    @Test
    fun storedJsonRoundTrips() {
        val original = AgentTaskLedger()
            .upsert(record("a", startedAt = 100))
            .upsert(record("b", startedAt = 200))
            .finish("b", AgentTaskStatus.COMPLETED, "完成", 900)!!

        val restored = AgentTaskLedger.fromStoredJson(original.toJson().toString())
        assertEquals(original, restored)
    }

    @Test
    fun storedJsonSkipsBrokenAndDuplicateEntries() {
        val json = JSONObject()
            .put("version", 1)
            .put(
                "tasks",
                org.json.JSONArray()
                    .put(record("a", startedAt = 100).toJson())
                    .put(record("a", startedAt = 100).toJson())
                    .put(JSONObject().put("id", ""))
                    .put(record("b", startedAt = 200).toJson()),
            )
        val restored = AgentTaskLedger.fromStoredJson(json.toString())!!
        assertEquals(listOf("b", "a"), restored.entries.map { it.id })
    }

    @Test
    fun corruptFileReadsAsNull() {
        assertNull(AgentTaskLedger.fromStoredJson("not json"))
        assertNull(AgentTaskLedger.fromStoredJson(JSONObject().put("version", 1).toString()))
    }

    @Test
    fun summaryCountsOnlyPresentStatuses() {
        val empty = AgentTaskLedger()
        assertEquals("本会话还没有派发过子任务", empty.renderSummary())

        val ledger = AgentTaskLedger()
            .upsert(record("a", status = AgentTaskStatus.RUNNING, startedAt = 1))
            .upsert(record("b", status = AgentTaskStatus.COMPLETED, startedAt = 2))
        val summary = ledger.renderSummary()
        assertTrue(summary.contains("运行中 1"))
        assertTrue(summary.contains("已完成 1"))
        assertFalse(summary.contains("失败"))
    }

    @Test
    fun renderedLineCarriesStatusAndId() {
        val line = record("sub-1", status = AgentTaskStatus.RUNNING, summary = "正在读取日志").renderLine()
        assertTrue(line.startsWith("[running]"))
        assertTrue(line.contains("sub-1"))
        assertTrue(line.endsWith("正在读取日志"))
        // 子代理是默认类型，不重复标注（否则每一行都带同样的尾巴）。
        assertFalse(line.contains("（subagent）"))
    }
}
