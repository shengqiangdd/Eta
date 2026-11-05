package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页里「委派专用提供商」列出哪些候选。
 *
 * 关键点：默认代理为「自动」时，最终会跑 codex 还是 claude 事先不知道，
 * 所以取两个代理的并集。否则用户必须先把代理定死才能选提供商 ——
 * 而「我不想改默认代理，只是想指定一个两边的都能用的提供商」是很常见的诉求。
 */
class AcpSelectableProvidersTest {
    private val model = Model(id = "m1", modelId = "m1", displayName = "M1")

    private val openAi = OpenAiCompatibleProviderSetting(
        id = "openai",
        name = "OpenAI 兼容",
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-x",
        models = listOf(model),
    )
    private val anthropic = AnthropicProviderSetting(
        id = "anthropic",
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        apiKey = "sk-ant",
        models = listOf(model),
    )

    @Test
    fun automaticAgentShowsBothKinds() {
        val picked = acpSelectableProviders(listOf(openAi, anthropic), agentId = "")

        assertEquals(listOf("openai", "anthropic"), picked.map { it.id })
    }

    @Test
    fun codexOnlySeesOpenAiCompatible() {
        val picked = acpSelectableProviders(listOf(openAi, anthropic), agentId = "codex")

        assertEquals(listOf("openai"), picked.map { it.id })
    }

    @Test
    fun claudeOnlySeesAnthropic() {
        // 这条正是修掉的缺陷：修之前 claude 侧连 Anthropic 提供商都列不出来。
        val picked = acpSelectableProviders(listOf(openAi, anthropic), agentId = "claude")

        assertEquals(listOf("anthropic"), picked.map { it.id })
    }

    @Test
    fun disabledProviderIsFilteredOut() {
        val disabled = anthropic.copy(isEnabled = false)

        assertTrue(acpSelectableProviders(listOf(disabled), agentId = "claude").isEmpty())
    }
}
