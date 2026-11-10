package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话用量汇总的口径。
 *
 * 每一条断言都对应一个「界面上看起来正常、数字其实错了」的情形：
 * 命中率分母取错、分子反推、累计写成覆盖、推理 token 漏算。
 */
class AgentSessionUsageUiTest {

    private fun assistant(usage: TokenUsageUi?, id: String = "a") = AgentMessageUi(
        id = id,
        content = "正文",
        usage = usage,
    )

    private fun user(id: String = "u") = UserMessageUi(id = id, content = "问题")

    @Test
    fun `缓存命中率用总输入作分母`() {
        // Anthropic 风格：input 不含缓存，prompt 是总量。
        val message = assistant(
            TokenUsageUi(inputTokens = 100, promptTokens = 1000, cachedTokens = 900, outputTokens = 50),
        )
        val usage = AgentSessionUsageUi.of(listOf(user(), message))
        // 900 / 1000 = 0.9；若误用 inputTokens 作分母会得到 9.0 再被截断成 1.0。
        assertEquals(0.9f, usage.cacheHitRate!!, 0.001f)
    }

    @Test
    fun `旧消息只有 inputTokens 时回退，而不是丢掉这一轮`() {
        val message = assistant(TokenUsageUi(inputTokens = 200, cachedTokens = 50))
        val usage = AgentSessionUsageUi.of(listOf(message))
        assertEquals(200, usage.promptTokens)
        assertEquals(0.25f, usage.cacheHitRate!!, 0.001f)
    }

    @Test
    fun `缓存命中率不会因反推出现负数或超过 100%`() {
        // OpenAI 兼容端点：inputTokens 已含缓存。cached 大于 input 也不该产生负命中率。
        val message = assistant(TokenUsageUi(inputTokens = 100, promptTokens = 100, cachedTokens = 130))
        val usage = AgentSessionUsageUi.of(listOf(message))
        assertEquals(1f, usage.cacheHitRate!!, 0.0001f)
    }

    @Test
    fun `没有输入数据时命中率是 null 而不是 0`() {
        val usage = AgentSessionUsageUi.of(listOf(assistant(TokenUsageUi(outputTokens = 10))))
        assertNull("数据不足应显示「—」，不能显示 0%", usage.cacheHitRate)
    }

    @Test
    fun `会话累计是求和而不是取最后一条`() {
        val messages = listOf(
            assistant(TokenUsageUi(promptTokens = 100, cachedTokens = 10, outputTokens = 20), "a1"),
            assistant(TokenUsageUi(promptTokens = 300, cachedTokens = 90, outputTokens = 40), "a2"),
        )
        val usage = AgentSessionUsageUi.of(messages)
        assertEquals(400, usage.promptTokens)
        assertEquals(100, usage.cachedTokens)
        assertEquals(60, usage.outputTokens)
        assertEquals(2, usage.requestCount)
        assertEquals(0.25f, usage.cacheHitRate!!, 0.001f)
    }

    @Test
    fun `上下文占用取最近一条，不求和`() {
        val messages = listOf(
            assistant(TokenUsageUi(contextTokens = 1000), "a1"),
            assistant(TokenUsageUi(contextTokens = 8000), "a2"),
        )
        val usage = AgentSessionUsageUi.of(messages, contextWindow = 200_000)
        // 求和会得到 9000，虽然仍在窗口内，但语义错了：占用是快照不是累计。
        assertEquals(8000, usage.contextTokens)
        assertEquals(200_000, usage.contextWindow)
    }

    @Test
    fun `输出总量包含推理 token`() {
        val usage = AgentSessionUsageUi.of(
            listOf(assistant(TokenUsageUi(outputTokens = 100, reasoningTokens = 400))),
        )
        // 推理也是模型真实生成的；排除它会让 tok/s 虚高。
        assertEquals(500, usage.totalOutputTokens)
    }

    @Test
    fun `用户消息与空用量不参与统计`() {
        val usage = AgentSessionUsageUi.of(
            listOf(
                user(),
                assistant(null),
                assistant(TokenUsageUi(), "empty"),
                assistant(TokenUsageUi(promptTokens = 10), "real"),
            ),
        )
        assertEquals(1, usage.requestCount)
        assertEquals(10, usage.promptTokens)
    }

    @Test
    fun `空会话不算失败，只是 isEmpty`() {
        val usage = AgentSessionUsageUi.of(emptyList())
        assertTrue(usage.isEmpty)
        assertNull(usage.totalOutputTokens)
        assertNull(usage.cacheHitRate)
    }
}
