package io.github.mangi.eta.agent.trace

import io.github.mangi.eta.agent.memory.AgentFailureHints
import io.github.mangi.eta.agent.memory.AgentSelfBrief
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 从「写进磁盘的真实 trace 文件」一路验到「系统提示里那段文字非空」。
 *
 * 为什么需要这层：`AgentSelfBriefTest` 和 `AgentSelfStatsTest` 都直接构造内存对象，
 * 已经证明渲染与聚合各自正确 —— 但它们证明不了**两者接起来能出东西**。
 * 真机上就是这样：两半各自单测全绿，实际注入却一直是空的，
 * 因为中间还隔着 `AgentRunTraceStore.list()` 的文件发现、
 * `pickForDetail` 的明细挑选、以及「首行必须是 meta」这条隐含前提。
 */
class AgentSelfBriefEndToEndTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("eta-self-brief", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        AgentRunTraceStore.init(dir)
    }

    @After
    fun tearDown() {
        AgentRunTraceStore.init(File.createTempFile("eta-reset", "").let { it.delete(); it })
        dir.deleteRecursively()
    }

    /** 写一份能被 [AgentRunTraceStore.summarize] 认出来的 trace。 */
    private fun writeTrace(
        runId: String,
        toolName: String,
        failureCode: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val result = """{"ok":false,"code":"$failureCode","message":"boom"}"""
        File(dir, "$runId.jsonl").writeText(
            buildString {
                appendLine("""{"k":"meta","v":1,"id":"$runId","at":$atMillis,"model":"m","provider":"p","purpose":"chat","window":256000}""")
                appendLine("""{"k":"start","tools":66,"images":0,"at":$atMillis}""")
                appendLine("""{"k":"tool","r":1,"name":"$toolName","ms":5,"ok":false,"args":"{}","args_chars":2,"res":${jsonQuote(result)}}""")
            },
        )
    }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `磁盘上的真实 trace 能一路变成非空的自评正文`() {
        repeat(9) { writeTrace("t$it", "edit_file", "READ_REQUIRED") }

        val stats = AgentSelfStatsLoader.load()
        assertNotNull("loader 读到了 9 份有失败的 trace，不该返回 null", stats)

        val text = AgentSelfBrief.render(stats)
        assertTrue("正文不该为空，实际 stats=$stats", text.isNotEmpty())
        assertTrue("应指出出问题的工具，实际：$text", text.contains("edit_file"))
        assertTrue("应带上失败码，实际：$text", text.contains("READ_REQUIRED"))
        assertTrue("应给出专属改法，实际：$text", text.contains(AgentFailureHints.fixFor("edit_file", "READ_REQUIRED")))
    }

    @Test
    fun `全部成功时不注入任何文字`() {
        repeat(5) { i ->
            File(dir, "ok$i.jsonl").writeText(
                buildString {
                    appendLine("""{"k":"meta","v":1,"id":"ok$i","at":${System.currentTimeMillis()},"model":"m","provider":"p","purpose":"chat","window":256000}""")
                    appendLine("""{"k":"start","tools":66,"images":0,"at":${System.currentTimeMillis()}}""")
                    appendLine("""{"k":"tool","r":1,"name":"read_file","ms":3,"ok":true,"args":"{}","args_chars":2,"res":"{}"}""")
                },
            )
        }

        val stats = AgentSelfStatsLoader.load()
        assertNotNull(stats)
        assertTrue("全部成功时不该注入，实际：${AgentSelfBrief.render(stats)}", AgentSelfBrief.render(stats).isEmpty())
    }

    @Test
    fun `窗口外的旧 trace 不参与统计`() {
        // 72 小时前，默认窗口是 48 小时。
        val old = System.currentTimeMillis() - 72L * 3_600_000
        repeat(6) { writeTrace("old$it", "edit_file", "READ_REQUIRED", atMillis = old) }

        val stats = AgentSelfStatsLoader.load()
        val text = AgentSelfBrief.render(stats)
        assertTrue("窗口外的旧数据不该被当成「最近表现」，实际：$text", text.isEmpty())
    }
}
