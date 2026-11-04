package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.trace.AgentSelfStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自评块的渲染规则。
 *
 * 重点守三类「看起来能用、实际有害」的行为：
 * 1. 没问题时也输出一段文字 —— 每轮占预算却从不带来新信息；
 * 2. 分母很小时报百分比 —— 1/3 显示成 33%，把偶发说成系统性缺陷；
 * 3. 没有专属改法时编一句「注意一下」 —— 训练模型忽略这段。
 */
class AgentSelfBriefTest {

    private fun tool(
        name: String,
        calls: Int,
        failures: Int,
        codes: Map<String, Int> = emptyMap(),
    ) = AgentSelfStats.ToolBehavior(name, calls, failures, codes)

    private fun stats(
        runs: Int = 30,
        runsWithTools: Int = 20,
        toolCalls: Int = 200,
        toolFailures: Int = 20,
        tools: List<AgentSelfStats.ToolBehavior> = emptyList(),
        unreadFailureRuns: Int = 0,
    ) = AgentSelfStats.Stats(
        runs = runs,
        runsWithTools = runsWithTools,
        toolCalls = toolCalls,
        toolFailures = toolFailures,
        retries = 0,
        retryRuns = 0,
        compactions = 0,
        compactionRuns = 0,
        failedRuns = 0,
        idleRuns = emptyList(),
        tools = tools,
        unreadFailureRuns = unreadFailureRuns,
    )

    /** 真实存在的错误码——只有它们才有专属改法（也就是长文本）。 */
    private val realCodes = listOf(
        "READ_REQUIRED", "FILE_CHANGED", "MATCH_NOT_FOUND", "AMBIGUOUS_MATCH",
        "FILE_TOO_LARGE", "NOT_DIRECTORY", "FILE_NOT_FOUND", "NO_OBSERVATION",
        "STALE_CONTENT", "SEARCH_CHALLENGE", "INVALID_QUERY", "TOOL_NOT_AVAILABLE",
    )

    @Test
    fun `没有数据时不输出`() {
        assertEquals("", AgentSelfBrief.render(null))
        assertEquals("", AgentSelfBrief.render(stats(runs = 0, runsWithTools = 0, toolCalls = 0, toolFailures = 0)))
    }

    @Test
    fun `全部健康时不输出`() {
        // 有调用、但一个失败都没有：注入「一切正常」只会白占预算。
        val healthy = stats(
            toolCalls = 500,
            toolFailures = 0,
            tools = listOf(tool("terminal", 300, 0), tool("read_file", 200, 0)),
        )
        assertEquals("", AgentSelfBrief.render(healthy))
    }

    @Test
    fun `有专属改法时给出改法`() {
        val text = AgentSelfBrief.render(
            stats(
                toolFailures = 15,
                tools = listOf(tool("edit_file", 100, 15, mapOf("READ_REQUIRED" to 15))),
            ),
        )
        assertTrue(text, text.contains("edit_file"))
        assertTrue(text, text.contains("READ_REQUIRED"))
        // 改法本身要出现，而不是只有错误码。
        assertTrue(text, text.contains(AgentFailureHints.fixFor("edit_file", "READ_REQUIRED")))
    }

    @Test
    fun `没有专属改法时只报次数不编建议`() {
        val text = AgentSelfBrief.render(
            stats(
                toolFailures = 3,
                tools = listOf(tool("some_tool", 40, 3, mapOf("SOMETHING_WEIRD" to 3))),
            ),
        )
        assertTrue(text, text.contains("SOMETHING_WEIRD"))
        assertTrue(text, text.contains("无固定改法"))
    }

    @Test
    fun `分母太小不报失败率`() {
        // 1/3 = 33%，如果不设门槛会显得像严重问题。
        val text = AgentSelfBrief.render(
            stats(
                toolCalls = 5,
                toolFailures = 1,
                tools = listOf(tool("tap_element", 3, 1, mapOf("NO_OBSERVATION" to 1))),
            ),
        )
        assertTrue(text, text.contains("tap_element"))
        assertFalse("分母不足时不应出现该工具自己的失败率", text.contains("失败率 33"))
    }

    @Test
    fun `分母足够时才报失败率`() {
        val text = AgentSelfBrief.render(
            stats(
                tools = listOf(tool("edit_file", 100, 18, mapOf("FILE_CHANGED" to 18))),
            ),
        )
        assertTrue(text, text.contains("失败率"))
    }

    @Test
    fun `按失败数排序并限制工具数量`() {
        val text = AgentSelfBrief.render(
            stats(
                tools = listOf(
                    tool("a_low", 50, 1, mapOf("X" to 1)),
                    tool("b_high", 50, 30, mapOf("Y" to 30)),
                    tool("c_mid", 50, 10, mapOf("Z" to 10)),
                    tool("d_extra", 50, 2, mapOf("W" to 2)),
                ),
            ),
        )
        // 第 3 名是 d_extra（失败 2 次），不是 a_low（1 次）。
        assertTrue(text, text.contains("b_high"))
        assertTrue(text, text.contains("c_mid"))
        assertTrue("失败数第三多的是 d_extra，应被列出", text.contains("d_extra"))
        assertFalse("失败最少的 a_low 应被挤掉", text.contains("a_low"))
        // 顺序：失败多的在前。
        assertTrue(text, text.indexOf("b_high") < text.indexOf("c_mid"))
        assertTrue(text, text.indexOf("c_mid") < text.indexOf("d_extra"))
    }

    @Test
    fun `有未展开明细时明确说明归因不完整`() {
        val text = AgentSelfBrief.render(
            stats(
                toolFailures = 5,
                tools = listOf(tool("edit_file", 100, 5, mapOf("MATCH_NOT_FOUND" to 5))),
                unreadFailureRuns = 3,
            ),
        )
        assertTrue(text, text.contains("归因不完整"))
        assertTrue(text, text.contains("3"))
    }

    @Test
    fun `无论输入多大都不超过字数上限`() {
        // 用真实错误码：编造的码会走「无固定改法」的短分支，测不出长文本。
        val fatTools = realCodes.chunked(2).mapIndexed { i, codes ->
            tool(name = "tool_$i", calls = 100, failures = 90, codes = codes.associateWith { 5 })
        }
        val text = AgentSelfBrief.render(stats(toolFailures = 900, tools = fatTools))
        assertTrue("实际长度 ${text.length}", text.length <= AgentSelfBrief.MAX_CHARS)
    }

    @Test
    fun `单个工具的错误码过多时也不超预算`() {
        val many = realCodes.associateWith { 3 }
        val text = AgentSelfBrief.render(
            stats(toolFailures = 36, tools = listOf(tool("edit_file", 50, 36, many))),
        )
        assertTrue("实际长度 ${text.length}", text.length <= AgentSelfBrief.MAX_CHARS)
    }

    @Test
    fun `超预算时留下可查完整明细的提示`() {
        // 注意：真实数据（3 工具 × 2 错码 × 最长 fix）最多约 620 字符，
        // 永远超不过 700 —— 所以这里必须用**人为超长工具名**才能覆盖到截断分支。
        // 这条用例守的是「防御路径真的能跑」，不是「真实数据会走这里」。
        val fatTools = realCodes.chunked(2).mapIndexed { i, codes ->
            tool(
                name = "edit_file_" + "x".repeat(200) + "_$i",
                calls = 100,
                failures = 90,
                codes = codes.associateWith { 9 },
            )
        }
        val text = AgentSelfBrief.render(stats(toolFailures = 900, tools = fatTools))
        assertTrue("长度 ${text.length}", text.length <= AgentSelfBrief.MAX_CHARS)
        assertTrue("未触发截断，长度 ${text.length}", text.contains("节选"))
    }

    @Test
    fun `纯函数：同样输入给同样输出`() {
        val input = stats(
            toolFailures = 15,
            tools = listOf(tool("edit_file", 100, 15, mapOf("READ_REQUIRED" to 15))),
        )
        assertEquals(AgentSelfBrief.render(input), AgentSelfBrief.render(input))
    }
}
