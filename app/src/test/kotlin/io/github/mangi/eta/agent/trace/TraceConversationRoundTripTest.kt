package io.github.mangi.eta.agent.trace

import java.io.File
import java.io.FileInputStream
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 写→读闭环：`begin(conversationId=…)` 真的把归属写进 meta，`latestRunIdFor` 真的读得回来。
 *
 * 前面的 [TraceConversationIdTest] 是手写 meta 行测读取端；这里走**真实写入路径**，
 * 因为真正会出错的往往是写入那一侧 —— 参数没传到、被 `take()` 截断、
 * 空串被当成有效值写进去。手写文件测不到这些。
 */
class TraceConversationRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var dir: File? = null

    private fun freshDir(): File {
        val d = temp.newFolder("traces")
        AgentRunTraceStore.init(d)
        dir = d
        return d
    }

    @After
    fun cleanup() {
        dir?.let { AgentRunTraceStore.init(it) }
    }

    private fun metaOf(d: File, runId: String): JSONObject {
        val file = File(d, "$runId.jsonl")
        return FileInputStream(file).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readLine()) }
    }

    @Test
    fun `begin 写入的 meta 带有会话归属`() {
        val d = freshDir()
        AgentRunTraceStore.begin(
            runId = "run-abc",
            model = "m",
            provider = "p",
            conversationId = "conv-xyz",
        )

        assertEquals("conv-xyz", metaOf(d, "run-abc").optString("conv"))
        assertEquals("run-abc", AgentRunTraceStore.latestRunIdFor("conv-xyz"))
    }

    @Test
    fun `不传会话时 meta 里没有 conv 键`() {
        val d = freshDir()
        AgentRunTraceStore.begin(runId = "run-nocnv", model = "m", provider = "p")

        assertTrue(
            "空串不该写成 conv 键：写进去会让它看起来像「属于某个空 id 的会话」",
            !metaOf(d, "run-nocnv").has("conv"),
        )
        assertNull(AgentRunTraceStore.latestRunIdFor(""))
    }

    @Test
    fun `同一个会话跑两次，最新那次能被优先找到`() {
        val d = freshDir()
        AgentRunTraceStore.begin(runId = "run-1", model = "m", provider = "p", conversationId = "conv-a")
        Thread.sleep(20)
        AgentRunTraceStore.begin(runId = "run-2", model = "m", provider = "p", conversationId = "conv-a")

        // run-2 的文件更新，应当排在前面。
        assertEquals("run-2", AgentRunTraceStore.latestRunIdFor("conv-a"))
    }

    @Test
    fun `不同会话不会互相串`() {
        val d = freshDir()
        AgentRunTraceStore.begin(runId = "run-a", model = "m", provider = "p", conversationId = "conv-a")
        Thread.sleep(20)
        AgentRunTraceStore.begin(runId = "run-b", model = "m", provider = "p", conversationId = "conv-b")

        assertEquals("run-a", AgentRunTraceStore.latestRunIdFor("conv-a"))
        assertEquals("run-b", AgentRunTraceStore.latestRunIdFor("conv-b"))
        assertNotEquals(
            AgentRunTraceStore.latestRunIdFor("conv-a"),
            AgentRunTraceStore.latestRunIdFor("conv-b"),
        )
    }

    @Test
    fun `超长会话 id 被截断但仍可精确匹配`() {
        val d = freshDir()
        val longId = "conv-" + "x".repeat(300)
        AgentRunTraceStore.begin(runId = "run-long", model = "m", provider = "p", conversationId = longId)

        val stored = metaOf(d, "run-long").optString("conv")
        assertEquals("写入端应按 120 字截断", 120, stored.length)
        assertEquals(
            "查询端用同一个值才能匹配上",
            "run-long",
            AgentRunTraceStore.latestRunIdFor(longId.take(120)),
        )
    }

    @Test
    fun `写完之后 read 能拿到首字与解码数据`() {
        // 走真实的事件入口 record()，而不是测试专用注入口：
        // 真正会断的是「事件 → 追踪条目」这一层，绕过它就等于没测。
        val d = freshDir()
        AgentRunTraceStore.begin(runId = "run-t", model = "m", provider = "p", conversationId = "conv-t")
        AgentRunTraceStore.record("run-t", AgentEvent.ProviderRequestStarted(round = 1))
        Thread.sleep(20)
        AgentRunTraceStore.record(
            "run-t",
            AgentEvent.AssistantBlockStart(round = 1, kind = AgentEvent.AssistantBlockKind.TEXT, index = 0),
        )
        AgentRunTraceStore.record(
            "run-t",
            AgentEvent.UsageReceived(
                round = 1,
                usage = AgentTokenUsage(outputTokens = 40, inputTokens = 10),
            ),
        )

        val runId = AgentRunTraceStore.latestRunIdFor("conv-t")
        assertNotNull("按会话必须能找到刚写的追踪", runId)
        val trace = AgentRunTraceStore.read(runId!!)
        assertNotNull(trace)
        assertEquals("conv-t", trace!!.conversationId)
        assertNotNull("首字延迟应当算得出来（写入→读取没断）", trace.lastFirstTokenMs)
        assertTrue("首字延迟应为正数", trace.lastFirstTokenMs!! >= 0)
    }
}
