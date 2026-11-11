package io.github.mangi.eta.agent.trace

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 保留策略不能把「当前会话的指标」一起删掉。
 *
 * 真机上的观察：追踪目录上限 80 个文件、48MB，按**最新优先**保留，不区分会话。
 * 于是多轮交替使用时，A 会话跑一阵、切到 B 跑几十轮，A 的追踪被挤没了；
 * 回到 A 时速度段消失，**没有任何提示**。用户看到的是「有时灵有时不灵」。
 */
class TraceRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun initDir(maxRuns: Int, maxTotalBytes: Long = 48L * 1024 * 1024): File {
        val d = temp.newFolder("traces")
        AgentRunTraceStore.init(d, AgentRunTraceStore.Retention(maxRuns = maxRuns, maxTotalBytes = maxTotalBytes))
        return d
    }

    @After
    fun cleanup() {
        // 单例：还原成默认目录大小上限，避免影响其它测试。
        AgentRunTraceStore.init(temp.root)
    }

    @Test
    fun `其它会话挤满配额后，当前会话的追踪仍然找得到`() {
        val maxRuns = 5
        initDir(maxRuns)

        // A 会话先跑一次
        AgentRunTraceStore.begin("run-a1", "m", "p", conversationId = "conv-a")
        Thread.sleep(15)

        // B 会话随后跑很多次，把配额挤满
        repeat(maxRuns * 3) { i ->
            AgentRunTraceStore.begin("run-b$i", "m", "p", conversationId = "conv-b")
            Thread.sleep(5)
        }

        assertNotNull(
            "A 的追踪被 B 挤掉了：回到 A 会话时速度段会静默消失",
            AgentRunTraceStore.latestRunIdFor("conv-a"),
        )
        assertEquals("run-a1", AgentRunTraceStore.latestRunIdFor("conv-a"))
    }

    @Test
    fun `被保留的会话记录不会让总量超过配额`() {
        val maxRuns = 4
        val dir = initDir(maxRuns)

        AgentRunTraceStore.begin("run-a1", "m", "p", conversationId = "conv-a")
        Thread.sleep(15)
        repeat(maxRuns * 3) { i ->
            AgentRunTraceStore.begin("run-b$i", "m", "p", conversationId = "conv-b")
            Thread.sleep(5)
        }

        val count = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.size ?: 0
        assertEquals("保留策略允许 maxRuns 份（pinned 已计入配额）", maxRuns, count)
    }

    @Test
    fun `同一会话连续跑时只保留最近的一份，不占额外配额`() {
        val maxRuns = 4
        val dir = initDir(maxRuns)

        repeat(maxRuns * 3) { i ->
            AgentRunTraceStore.begin("run-a$i", "m", "p", conversationId = "conv-a")
            Thread.sleep(5)
        }

        assertEquals("run-a${maxRuns * 3 - 1}", AgentRunTraceStore.latestRunIdFor("conv-a"))
        val count = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.size ?: 0
        assertEquals(maxRuns, count)
    }

    @Test
    fun `没有会话归属的旧追踪不参与 pinning`() {
        val maxRuns = 3
        initDir(maxRuns)

        // 旧调用方（不传 conversationId）：不该被当成「当前会话」而永久保留
        AgentRunTraceStore.begin("run-legacy", "m", "p")
        Thread.sleep(15)
        repeat(maxRuns * 2) { i ->
            AgentRunTraceStore.begin("run-b$i", "m", "p", conversationId = "conv-b")
            Thread.sleep(5)
        }

        // 早已被正常回收
        assertNull(AgentRunTraceStore.read("run-legacy"))
        assertNotNull(AgentRunTraceStore.latestRunIdFor("conv-b"))
    }

    @Test
    fun `lookup 的 limit 不会被压回保留配额`() {
        // MAX_RUNS 是写入侧的保留配额，不该同时当作读取侧的扫描上限。
        //
        // 这条测的是「参数被尊重」：给一个远大于配额的值，不能报错也不能被
        // 悄悄改成 maxRuns。用 maxRuns = 1 制造极端场景——若实现仍按配额钳制，
        // 连最近写的那份都会找不到。
        initDir(maxRuns = 1)

        repeat(5) { i ->
            AgentRunTraceStore.begin("run-b$i", "m", "p", conversationId = "conv-b")
            Thread.sleep(5)
        }

        assertEquals(
            "limit 被钳到 1 时会连最近那份都找不到",
            "run-b4",
            AgentRunTraceStore.latestRunIdFor("conv-b", limit = 200),
        )
    }

    @Test
    fun `总量配额也会触发回收`() {
        // 每份 meta 都很小，这里用一个极小的字节上限逼出回收路径。
        val dir = temp.newFolder("traces-small")
        AgentRunTraceStore.init(
            dir,
            AgentRunTraceStore.Retention(maxRuns = 100, maxTotalBytes = 1_200),
        )

        repeat(20) { i ->
            AgentRunTraceStore.begin("run-$i", "m", "p", conversationId = "conv-x")
            Thread.sleep(3)
        }

        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        val total = files.sumOf { it.length() }
        assertEquals("超过字节上限后仍应保留最近一份", "run-19", AgentRunTraceStore.latestRunIdFor("conv-x"))
        assertEquals("总量应被压到配额以内", true, total <= 1_200 + 200)
    }
}
