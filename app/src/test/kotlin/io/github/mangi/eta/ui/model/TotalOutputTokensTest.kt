package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片上的「输出」总量必须是真实输出，不能把推理再加一遍。
 *
 * `AgentTokenUsage.outputTokens` 已统一折算为「含推理」（见该字段的注释）：
 * OpenAI / Anthropic 原生即如此，Gemini 在解析时把 `total_thought_tokens` 加进
 * `total_output_tokens`。所以 `reasoningTokens` 是**子集**，不是并列项。
 *
 * 早先这里写成 `outputTokens + reasoningTokens`，实测某会话累计虚高 11.6%
 * （350,914 vs 314,398）。
 */
class TotalOutputTokensTest {

    private fun assistant(output: Int?, reasoning: Int?) = AgentMessageUi(
        id = "a-1",
        content = "答",
        usage = TokenUsageUi(outputTokens = output, reasoningTokens = reasoning),
    )

    @Test
    fun `总量等于各轮 output 之和，不加推理`() {
        val usage = AgentSessionUsageUi.of(
            listOf(
                assistant(output = 100, reasoning = 80),
                assistant(output = 240, reasoning = 60),
            ),
        )
        assertEquals("推理是 output 的子集，再加一遍就重复了", 340, usage.totalOutputTokens)
    }

    @Test
    fun `推理占比高时不会虚高`() {
        // 真机上出现过 output=100 / reasoning=88 这类轮次：相加会接近两倍。
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 100, reasoning = 88)))
        assertEquals(100, usage.totalOutputTokens)
    }

    @Test
    fun `完全没有输出时为 null 而不是 0`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 0, reasoning = 0)))
        assertNull(
            "0 会让界面显示「输出 0 tok」，但真实含义是「没有数据」",
            usage.totalOutputTokens,
        )
    }

    @Test
    fun `只有推理没有 output 时不拿推理数顶替总量`() {
        // 正常不该出现（reasoning 是 output 的子集），但旧数据可能有。
        // 关键是**不能**把推理数当成总量显示：它是子集，拿出来会偏小，
        // 而「偏小的输出量」比「没有数据」更容易被当真。
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = null, reasoning = 42)))
        assertEquals(0, usage.outputTokens)
        assertEquals(42, usage.reasoningTokens)
        assertEquals("总量是 0（没有确切的 output），不是 42", 0, usage.totalOutputTokens)
    }

    @Test
    fun `推理总量仍单独统计，供「其中推理」那行显示`() {
        val usage = AgentSessionUsageUi.of(
            listOf(
                assistant(output = 300, reasoning = 120),
                assistant(output = 200, reasoning = 30),
            ),
        )
        assertEquals(150, usage.reasoningTokens)
        assertEquals(500, usage.totalOutputTokens)
        assertTrueLess(usage.reasoningTokens!!, usage.totalOutputTokens!!)
    }

    private fun assertTrueLess(a: Int, b: Int) {
        org.junit.Assert.assertTrue("推理应小于等于总量：$a vs $b", a <= b)
    }
}
