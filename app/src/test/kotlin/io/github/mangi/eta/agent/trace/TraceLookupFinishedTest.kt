package io.github.mangi.eta.agent.trace

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「最新一份」不等于「最近完成的一份」。
 *
 * 追踪是**边跑边写**的，mtime 每次追加都更新。所以按 mtime 排序时，最新那份
 * 往往正是此刻还在跑的这个 run —— 它可能还没写 `first`（这一轮还没吐出第一个字），
 * 速率先落到上一次运行上，界面显示的数字属于上一轮而用户以为是这一轮。
 *
 * 真机表现：卡片显示 TTFT 20s，而当时最新那份算出来是 5.6s，
 * 20s 来自它前一份（19.7s）—— 就是那次运行。
 */
class TraceLookupFinishedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writeTrace(
        dir: File,
        runId: String,
        conv: String,
        atMs: Long,
        finished: Boolean,
        firstTokenDelayMs: Long = 500,
    ): File {
        val file = File(dir, "$runId.jsonl")
        val meta = JSONObject()
            .put("k", AgentRunTraceStore.KIND_META)
            .put("id", runId)
            .put("at", atMs)
            .put("model", "m")
            .put("provider", "p")
            .put("conv", conv)
        val request = JSONObject()
            .put("k", AgentRunTraceStore.KIND_REQUEST)
            .put("at", atMs + 10)
            .put("r", 1)
        file.writeText(meta.toString() + "\n" + request.toString() + "\n")
        if (finished) {
            file.appendText(
                JSONObject()
                    .put("k", AgentRunTraceStore.KIND_FIRST)
                    .put("at", atMs + 10 + firstTokenDelayMs)
                    .put("r", 1)
                    .toString() + "\n",
            )
            file.appendText(
                JSONObject().put("k", AgentRunTraceStore.KIND_DONE).put("at", atMs + 5_000).toString() + "\n",
            )
        }
        file.setLastModified(atMs)
        return file
    }

    @Test
    fun `仍在运行的最新一份不会顶掉已完成的上一份`() {
        val dir = temp.newFolder("traces")
        AgentRunTraceStore.init(dir)
        // 旧的已跑完，新的还在跑（mtime 更新）
        writeTrace(dir, "run-done", "conv-1", atMs = 1_000, finished = true, firstTokenDelayMs = 800)
        writeTrace(dir, "run-live", "conv-1", atMs = 5_000, finished = false)

        assertEquals(
            "还在跑的那份没有首字，取它会让界面显示上一轮的数字",
            "run-done",
            AgentRunTraceStore.latestRunIdFor("conv-1"),
        )
    }

    @Test
    fun `都已完成时取最新的一份`() {
        val dir = temp.newFolder("traces")
        AgentRunTraceStore.init(dir)
        writeTrace(dir, "run-old", "conv-1", atMs = 1_000, finished = true)
        writeTrace(dir, "run-new", "conv-1", atMs = 5_000, finished = true)

        assertEquals("run-new", AgentRunTraceStore.latestRunIdFor("conv-1"))
    }

    @Test
    fun `首次运行仍在进行时退回最新一份`() {
        // 一份完成的都没有：总得让界面有东西可显示（哪怕是进行中的值）。
        val dir = temp.newFolder("traces")
        AgentRunTraceStore.init(dir)
        writeTrace(dir, "run-only", "conv-1", atMs = 5_000, finished = false)

        assertEquals("run-only", AgentRunTraceStore.latestRunIdFor("conv-1"))
    }

    @Test
    fun `只在本会话的追踪里挑，不受其它会话的进行中记录影响`() {
        val dir = temp.newFolder("traces")
        AgentRunTraceStore.init(dir)
        writeTrace(dir, "run-a-done", "conv-a", atMs = 1_000, finished = true)
        // 另一个会话正在跑，且更新
        writeTrace(dir, "run-b-live", "conv-b", atMs = 9_000, finished = false)

        assertEquals("run-a-done", AgentRunTraceStore.latestRunIdFor("conv-a"))
    }

    @After
    fun cleanup() {
        AgentRunTraceStore.init(temp.root)
    }
}
