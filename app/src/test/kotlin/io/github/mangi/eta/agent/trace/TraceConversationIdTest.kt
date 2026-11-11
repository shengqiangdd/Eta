package io.github.mangi.eta.agent.trace

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 会话归属：`meta.conv` 的写入与按会话查询。
 *
 * 这一层存在的理由是把「UI 按会话找它的运行」从隐式约定（消息 id 前缀 = runId）
 * 换成显式字段。所以测试要钉住两件事：
 *
 * 1. 没有 conv 的旧追踪**不能**被误认成任何会话 —— 否则会串到别人会话的速度；
 * 2. 按会话查要拿到**最近**的那次，不是第一个碰上的。
 *
 * 走真实文件：这套逻辑的价值全在「跨进程读回同一份文件」，用 mock 测不到。
 */
class TraceConversationIdTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var dir: File? = null

    /** 每个用例独立的追踪目录；用 init(dir) 注入，不需要测试专用接口。 */
    private fun freshDir(): File {
        val d = temp.newFolder("traces")
        AgentRunTraceStore.init(d)
        dir = d
        return d
    }

    @After
    fun cleanup() {
        // 单例状态要还原，避免污染其它测试。
        dir?.let { AgentRunTraceStore.init(it) }
    }

    /** 直接写 meta 行，绕开 begin()（begin 依赖 Android 的 directory 注入）。 */
    private fun writeTrace(
        dir: File,
        runId: String,
        conv: String?,
        atMs: Long,
    ): File {
        val file = File(dir, "$runId.jsonl")
        val meta = JSONObject()
            .put("k", AgentRunTraceStore.KIND_META)
            .put("id", runId)
            .put("at", atMs)
            .put("model", "m")
            .put("provider", "p")
        if (conv != null) meta.put("conv", conv)
        file.writeText(meta.toString() + "\n")
        file.setLastModified(atMs)
        return file
    }

    @Test
    fun `按会话查询能命中带 conv 的追踪`() {
        val dir = freshDir()
        writeTrace(dir, "run-a", "conv-1", 1_000)
        writeTrace(dir, "run-b", "conv-2", 2_000)

        assertEquals("run-a", AgentRunTraceStore.latestRunIdFor("conv-1"))
        assertEquals("run-b", AgentRunTraceStore.latestRunIdFor("conv-2"))
    }

    @Test
    fun `同一会话多次运行时取最近一次`() {
        val dir = freshDir()
        writeTrace(dir, "run-old", "conv-1", 1_000)
        writeTrace(dir, "run-new", "conv-1", 5_000)

        assertEquals("run-new", AgentRunTraceStore.latestRunIdFor("conv-1"))
    }

    @Test
    fun `没有 conv 的旧追踪不属于任何会话`() {
        val dir = freshDir()
        writeTrace(dir, "run-legacy", null, 9_000)

        assertNull(
            "旧追踪缺 conv，必须当成「未知」，不能命中任意会话",
            AgentRunTraceStore.latestRunIdFor("conv-1"),
        )
    }

    @Test
    fun `未知会话返回 null 而不是第一个文件`() {
        val dir = freshDir()
        writeTrace(dir, "run-a", "conv-1", 1_000)

        assertNull(AgentRunTraceStore.latestRunIdFor("conv-nonexistent"))
    }

    @Test
    fun `空会话 id 直接返回 null，不去扫盘`() {
        val dir = freshDir()
        writeTrace(dir, "run-a", "", 1_000)

        assertNull(AgentRunTraceStore.latestRunIdFor(""))
    }

    @Test
    fun `目录不存在时安静返回 null`() {
        AgentRunTraceStore.init(File(temp.root, "not-there"))
        assertNull(AgentRunTraceStore.latestRunIdFor("conv-1"))
    }

    @Test
    fun `解析出的 Trace 也带上会话归属`() {
        val dir = freshDir()
        val file = writeTrace(dir, "run-a", "conv-9", 1_000)
        // 补一条 done，让 parse 走到结尾
        file.appendText(
            JSONObject().put("k", AgentRunTraceStore.KIND_DONE).put("at", 1_100).put("ok", true)
                .toString() + "\n",
        )

        val trace = AgentRunTraceStore.read(file.nameWithoutExtension)
        assertEquals("conv-9", trace?.conversationId)
    }

    @Test
    fun `旧追踪解析出的会话归属是 null`() {
        val dir = freshDir()
        val file = writeTrace(dir, "run-legacy", null, 1_000)
        file.appendText(
            JSONObject().put("k", AgentRunTraceStore.KIND_DONE).put("at", 1_100).put("ok", true)
                .toString() + "\n",
        )

        val trace = AgentRunTraceStore.read(file.nameWithoutExtension)
        assertNull("旧追踪没有 conv 键，解析结果必须是 null", trace?.conversationId)
        assertTrue("旧文件本身仍应能解析", trace != null && trace.runId.isNotBlank())
    }
}
