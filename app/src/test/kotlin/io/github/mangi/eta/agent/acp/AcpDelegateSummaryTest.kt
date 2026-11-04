package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片上「这次委派做了什么」的那一行。
 *
 * 回归点（PKX110，2026-10-27）：一次 90 秒、两步的委派，卡片只显示「完成」。
 * 用户的原话是「回传的信息太简陋了」。
 */
class AcpDelegateSummaryTest {

    private val formatter = AgentTraceFormatter()

    private fun summarize(json: JSONObject): String =
        formatter.summarizeResult("acp_delegate", AgentModelClient.ToolResult(json.toString()))

    private fun step(title: String, status: String) = JSONObject()
        .put("title", title)
        .put("kind", "execute")
        .put("status", status)
        .put("elapsed_ms", 50_000L)

    @Test
    fun `success summary carries duration steps usage and the first answer line`() {
        val json = JSONObject()
            .put("ok", true)
            .put("elapsed_ms", 90_176L)
            .put("steps", JSONArray().put(step("sleep 50", "completed")))
            .put("usage", JSONObject().put("used", 30_176L).put("size", 258_400L))
            .put("text", "我会先等待 50 秒，再统计。\nDONE 690")

        val line = summarize(json)
        assertTrue(line, line.contains("完成"))
        assertTrue(line, line.contains("90.2s"))
        assertTrue(line, line.contains("1 步"))
        assertTrue(line, line.contains("上下文 11%"))
        assertTrue(line, line.contains("我会先等待"))
    }

    @Test
    fun `failed delegate reports the failure reason and step count`() {
        val json = JSONObject()
            .put("ok", false)
            .put("elapsed_ms", 170_035L)
            .put("steps", JSONArray().put(step("sleep 70", "failed")))
            .put("message", "超过 170s 未完成，已终止代理进程")

        val line = summarize(json)
        assertTrue(line, line.contains("失败"))
        assertTrue(line, line.contains("170.0s"))
        assertTrue(line, line.contains("1 步（1 失败）"))
        assertTrue(line, line.contains("超过 170s 未完成"))
    }

    @Test
    fun `delegate without steps still shows duration and answer`() {
        val json = JSONObject()
            .put("ok", true)
            .put("elapsed_ms", 18_085L)
            .put("text", "OK")

        val line = summarize(json)
        assertTrue(line, line.contains("18.1s"))
        assertTrue(line, line.contains("OK"))
    }

    /**
     * 失败/超时 ≠ 什么都没做。
     *
     * 真实场景：代理跑到第 8 分钟被超时杀掉，前 7 分钟已经改过文件。
     * 卡片只说「失败 · 8m0s · 5 步（1 失败）」会让人以为仓库还干净 ——
     * 接着在同一个仓库里继续干活，就会在不知道的改动上叠加。
     */
    @Test
    fun `failed delegate that edited files says so`() {
        val steps = JSONArray()
            .put(step("读 src/a.kt", "completed"))
            .put(JSONObject().put("title", "写 src/a.kt").put("kind", "edit").put("status", "completed"))
            .put(JSONObject().put("title", "写 src/b.kt").put("kind", "edit").put("status", "completed"))
            .put(step("跑测试", "failed"))
        val json = JSONObject()
            .put("ok", false)
            .put("elapsed_ms", 480_000L)
            .put("steps", steps)
            .put("message", "超过 480s 未完成，已终止代理进程")

        val line = summarize(json)
        assertTrue(line, line.contains("失败"))
        assertTrue(line, line.contains("2 次文件改动"))
        assertTrue(line, line.contains("可能已落盘"))
    }

    /** 只是跑命令、没碰文件的失败，不应当凭空多出「可能已落盘」这句。 */
    @Test
    fun `failed delegate without edits stays clean`() {
        val json = JSONObject()
            .put("ok", false)
            .put("elapsed_ms", 60_000L)
            .put("steps", JSONArray().put(step("跑测试", "failed")))
            .put("message", "超过 60s 未完成")

        val line = summarize(json)
        assertTrue(line, line.contains("失败"))
        assertTrue(line, !line.contains("可能已落盘"))
    }

    /** 成功的委派不加这句：它有自己的正文和步骤汇报，不必重复警告。 */
    @Test
    fun `successful delegate does not warn about side effects`() {
        val json = JSONObject()
            .put("ok", true)
            .put("elapsed_ms", 30_000L)
            .put(
                "steps",
                JSONArray().put(JSONObject().put("title", "写 a.kt").put("kind", "edit").put("status", "completed")),
            )
            .put("text", "改完了")

        val line = summarize(json)
        assertTrue(line, line.contains("完成"))
        assertTrue(line, !line.contains("可能已落盘"))
    }
}
