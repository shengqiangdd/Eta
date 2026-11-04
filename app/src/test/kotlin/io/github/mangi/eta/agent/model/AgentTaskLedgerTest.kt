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
    fun abandonRunningKeepsExistingSummaryAndSkipsWhenNothingRuns() {
        assertNull("没有运行中的条目时返回 null，调用方不必写盘", AgentTaskLedger().abandonRunning())
        assertNull(AgentTaskLedger().upsert(record("a", status = AgentTaskStatus.COMPLETED)).abandonRunning())

        val ledger = AgentTaskLedger()
            .upsert(record("plain", startedAt = 200))
            .upsert(record("with-note", startedAt = 100, summary = "第 2 轮"))
        val fixed = ledger.abandonRunning()!!

        val plain = fixed.entries.first { it.id == "plain" }
        assertEquals(AgentTaskStatus.CANCELLED, plain.status)
        assertEquals(AgentTaskLedger.ORPHAN_NOTE, plain.summary)
        // 上一轮写下的真实进度不该被「未知」盖掉。
        assertEquals("第 2 轮", fixed.entries.first { it.id == "with-note" }.summary)
    }

    @Test
    fun promptSectionIsEmptyForEmptyLedger() {
        assertEquals("", AgentTaskLedger().promptSection())
        // 空台账与 maxLines=0 都不产生段落：调用方据此不追加系统消息。
        assertEquals("", AgentTaskLedger().upsert(record("a")).promptSection(maxLines = 0))
    }

    @Test
    fun promptSectionPutsRunningFirstAndPointsToTasksToolWhenTruncated() {
        val ledger = AgentTaskLedger()
            .upsert(record("newer", status = AgentTaskStatus.COMPLETED, startedAt = 300))
            .upsert(record("older", status = AgentTaskStatus.RUNNING, startedAt = 200))

        val section = ledger.promptSection(maxLines = 1)
        assertTrue(section.startsWith("<subagent_tasks>"))
        assertTrue(section.endsWith("</subagent_tasks>"))
        // 运行中的优先：只列一条时必须是它，而不是开始得更晚但已收尾的那条。
        assertTrue(section.contains("任务 older"))
        assertFalse(section.contains("[completed]"))
        assertTrue(section.contains("另有 1 条更早的任务未列出"))
        assertTrue(section.contains("subagent(resume="))
    }

    @Test
    fun promptSectionListsEverythingWhenUnderTheLineLimit() {
        val ledger = AgentTaskLedger()
            .upsert(record("a", status = AgentTaskStatus.COMPLETED, startedAt = 100))
            .upsert(record("b", status = AgentTaskStatus.FAILED, startedAt = 200))

        val section = ledger.promptSection()
        assertTrue(section.contains("任务 a") && section.contains("任务 b"))
        assertFalse("没超上限时不该提「未列出」", section.contains("未列出"))
        // 汇总行与逐条状态同一口径。
        assertTrue(section.contains("已完成 1"))
        assertTrue(section.contains("失败 1"))
    }

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

    /**
     * 跨进程后台账仍会把旧 id 列出来（那是执行痕迹，有用的），但不能还教模型去 resume：
     * 系统提示与 `tasks` 两边必须同一口径，否则模型会去调一个必然失败的工具。
     */
    @Test
    fun sectionAndToolNoteStopPointingAtResumeOnceSessionsExpire() {
        val live = AgentTaskLedger().upsert(record("a", status = AgentTaskStatus.COMPLETED, startedAt = 100))
        assertTrue(live.promptSection().contains("subagent(resume="))
        // 未失效时那段只是一句「标了失效的条目不能 resume」的提醒，不能出现「这些条目都已经失效」。
        assertFalse(live.promptSection().contains("这些条目的上下文都已经失效"))
        assertTrue(live.resumeNote().contains("都可以"))

        val expired = live.abandonRunning(expireSessions = true)!!
        val section = expired.promptSection()
        assertFalse("全部失效时不该再指向 resume", section.contains("subagent(resume="))
        assertTrue(section.contains("这些条目的上下文都已经失效"))
        assertTrue(expired.entries.single().renderLine().contains("不能 resume"))
        assertTrue(expired.resumeNote().contains("不能 resume"))
    }

    @Test
    fun expiringOnlyTouchesTheFlagWhenNothingIsRunning() {
        val settled = AgentTaskLedger().upsert(record("a", status = AgentTaskStatus.COMPLETED, startedAt = 100))
        // 没有运行中的条目、且全部仍未标记 → 仍要返回一个新台账（否则标记就写不进去）。
        val expired = settled.abandonRunning(expireSessions = true)!!
        assertTrue(expired.entries.single().sessionExpired)
        assertEquals(AgentTaskStatus.COMPLETED, expired.entries.single().status)
        // 已经标过的不再重复产生新对象：读路径每轮都跑，不该无谓写盘。
        assertNull(expired.abandonRunning(expireSessions = true))
    }

    @Test
    fun progressOnlyTouchesRunningEntriesAndFinishClearsIt() {
        val ledger = AgentTaskLedger()
            .upsert(record("run", status = AgentTaskStatus.RUNNING, startedAt = 1))
            .upsert(record("done", status = AgentTaskStatus.COMPLETED, startedAt = 2))

        val updated = ledger.progress("run", "正在读取日志", 5_000L)!!
        assertEquals("正在读取日志", updated.entries.first { it.id == "run" }.progress)
        assertEquals(5_000L, updated.entries.first { it.id == "run" }.updatedAt)
        assertNull("已结束的条目不能被迟到的进度写回", ledger.progress("done", "迟到", 5_000L))

        val finished = updated.finish("run", AgentTaskStatus.COMPLETED, "完成", 6_000L)!!
        val run = finished.entries.first { it.id == "run" }
        assertNull(run.progress)
        assertEquals(6_000L, run.updatedAt)
    }
}
