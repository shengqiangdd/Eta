package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩进度日志的分流规则。
 *
 * 这段规则原先写死在 executor 的 lambda 里，注释写着「两类行必须落盘」，
 * 实现却只覆盖了其中一类，分批进度全丢——真机上压缩跑了却查不到「几批、多慢」。
 * 没有测试守着，这类遗漏能一直躺着。
 */
class AgentCompactionLogRoutingTest {

    @Test
    fun `判定行原样落盘`() {
        val line = "agent_context_decision run=r1 compact=no cause=none chars=100 tokens=25"
        assertEquals(line, AgentCompactionLogRouting.format(line, "r1"))
    }

    @Test
    fun `摘要请求补 run 前缀`() {
        val line = "摘要请求 model=m1 目标=6000字符 历史=618条 耗时=8176ms"
        val out = AgentCompactionLogRouting.format(line, "run-abc")
        assertEquals("agent_context_summary run=run-abc $line", out)
    }

    @Test
    fun `分批进度必须落盘——这是曾经丢掉的那一类`() {
        // 真机上压缩确实跑了（summary 行有记录），却一条分批进度都查不到，
        // 原因就是这行落到了普通 info，被 DEBUG 事件顶出环形缓冲。
        val line = "上下文摘要分批：第 1/3 批完成，输入 240000 字符，本批 7676ms，累计 7676ms"
        val out = AgentCompactionLogRouting.format(line, "run-abc")
        assertEquals("agent_context_batch run=run-abc $line", out)
    }

    @Test
    fun `合并与跳过合并同样落盘`() {
        // 这两个是同一族的另外两种写法，以前逐个列举前缀时一并漏掉了。
        val merge = "上下文摘要合并：3 批合成一段，耗时 900ms"
        val skip = "上下文摘要跳过合并：2 批拼接后 12000 字符，未超 60000，省一轮模型调用"
        assertTrue(AgentCompactionLogRouting.format(merge, "r")!!.startsWith("agent_context_batch"))
        assertTrue(AgentCompactionLogRouting.format(skip, "r")!!.startsWith("agent_context_batch"))
    }

    @Test
    fun `摘要请求不会被分批规则抢走`() {
        // 「摘要请求」和「上下文摘要…」都以中文开头，判断顺序写反就会把它归到分批。
        assertEquals(
            AgentCompactionLogRouting.Sink.SUMMARY,
            AgentCompactionLogRouting.sinkFor("摘要请求 model=m"),
        )
    }

    @Test
    fun `无关行不进持久化`() {
        // 普通 info 走 logcat 就够了，没必要塞进 boot.log 挤掉真正的诊断线索。
        assertNull(AgentCompactionLogRouting.format("run replay events=94 compacted=65", "r"))
        assertNull(AgentCompactionLogRouting.format("", "r"))
    }
}
