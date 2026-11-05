package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
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
    fun userAddedCustomProvidersWorkForBothAgents() {
        // 这一条是被真机数据提醒才补的：本机 22 个提供商里用户自己加的 10 个
        // **全是 custom 类型**，而此前的用例只造了 openAi 与 anthropic 两种，
        // 等于「用户实际用的那种」从来没被覆盖过。
        val custom = CustomProviderSetting(
            id = "sq6",
            name = "sq6",
            baseUrl = "https://cli-p.example.com/v1",
            apiKey = "sk-custom",
            models = listOf(model),
        )

        assertEquals(
            listOf("sq6"),
            acpSelectableProviders(listOf(custom), agentId = "codex").map { it.id },
        )
        assertEquals(
            listOf("sq6"),
            acpSelectableProviders(listOf(custom), agentId = "claude").map { it.id },
        )
    }

    @Test
    fun longListIsNotTruncated() {
        // 真机上内置的 10 个排在 sort_order 前面、用户自建的排在后面。
        // 这里钉住「过滤不截断」：如果以后有人为了性能加 take(n)，
        // 用户就会又一次「只看得到内置的那几个」。
        // （看不见还有另一个原因 —— 对话框不可滚动，那个在 UI 层，纯函数测不到。）
        val builtins = (1..10).map { index ->
            OpenAiCompatibleProviderSetting(
                id = "builtin-$index",
                name = "内置 $index",
                baseUrl = "https://api$index.example.com/v1",
                apiKey = "",
                models = listOf(model),
            )
        }
        val mine = (1..7).map { index ->
            CustomProviderSetting(
                id = "mine-$index",
                name = "自建 $index",
                baseUrl = "https://mine$index.example.com/v1",
                apiKey = "sk-$index",
                models = listOf(model),
            )
        }

        val picked = acpSelectableProviders(builtins + mine, agentId = "codex")

        assertEquals("10 个内置 + 7 个自建都应该在候选里", 17, picked.size)
        assertTrue(
            "自建的不能因为排在后面就被丢掉",
            picked.map { it.id }.containsAll(mine.map { it.id }),
        )
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
