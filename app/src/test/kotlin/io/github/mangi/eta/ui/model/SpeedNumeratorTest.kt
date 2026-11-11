package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 速度的分子只取 outputTokens，不额外加 reasoningTokens。
 *
 * 供应商把推理放在 output 的**明细**里，不是并列项：
 * - OpenAI：`output_tokens_details.reasoning_tokens`
 * - Anthropic：`usage.thinking_output_tokens`
 * - Responses：`output_tokens_details.reasoning_tokens`
 *
 * 真机 200 条样本全部满足 `reasoning ≤ output`。因此相加会虚高：
 * 平均 1.04x、推理密集的轮次最坏 1.88x。追踪侧的 `speedOf` 也只用 outputTokens，
 * 两边口径必须一致，否则同一个数字会有两种算法。
 */
class SpeedNumeratorTest {

    private fun assistant(output: Int?, reasoning: Int?) = AgentMessageUi(
        id = "a-1",
        content = "答",
        usage = TokenUsageUi(outputTokens = output, reasoningTokens = reasoning),
    )

    private fun lastOutputOf(usage: AgentSessionUsageUi) = usage.lastOutputTokens

    @Test
    fun `分子等于 outputTokens，不加推理`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 590, reasoning = 370)))
        assertEquals(
            "output 已含推理，再加一遍会把速度算高",
            590,
            lastOutputOf(usage),
        )
    }

    @Test
    fun `推理占大头时也不能翻倍`() {
        // 真机上出现过 output=100 / reasoning=88 这类轮次：
        // 相加会让速度接近真实值的两倍。
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 100, reasoning = 88)))
        assertEquals(100, lastOutputOf(usage))
    }

    @Test
    fun `没有推理时同样取 outputTokens`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 123, reasoning = 0)))
        assertEquals(123, lastOutputOf(usage))
    }

    @Test
    fun `output 缺失时不退回推理数`() {
        // 只有推理数、没有 output 的旧数据：宁可不给速度，也不要用一个
        // 语义不明的数当分子（它大概率是 output 的子集，单独拿出来会偏小）。
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = null, reasoning = 50)))
        assertEquals(null, lastOutputOf(usage))
    }

    @Test
    fun `output 为 0 时不给速度分子`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant(output = 0, reasoning = 10)))
        assertEquals(null, lastOutputOf(usage))
    }

    @Test
    fun `推理总量仍然单独统计，供卡片展示`() {
        // 分子统一到 output，但卡片上「其中推理」那一行仍要能显示数值。
        val usage = AgentSessionUsageUi.of(
            listOf(
                assistant(output = 300, reasoning = 120),
                assistant(output = 200, reasoning = 30),
            ),
        )
        assertEquals(150, usage.reasoningTokens)
        assertEquals("分子取最后一轮的 output", 200, lastOutputOf(usage))
    }
}
