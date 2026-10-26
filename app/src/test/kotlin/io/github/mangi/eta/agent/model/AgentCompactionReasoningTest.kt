package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要请求的思考开关：压缩 75% 的输出 token 是 reasoning，关掉它是压缩提速的主要手段。
 *
 * 这里守住的不是"一定要关"，而是**只在模型允许时**关：
 * 强制思考的模型（capabilities.mandatory）或不在可选集合里的模型必须保持用户设置，
 * 否则 `validatedEffort` 会抛「当前模型强制启用思考，不能选择 Off」，整轮回答被打断。
 */
class AgentCompactionReasoningTest {

    private fun config(
        effort: ReasoningEffort?,
        capabilities: ModelReasoningCapabilities?,
    ) = AgentModelClient.ModelConfig(
        baseUrl = "http://127.0.0.1:1",
        apiKey = "fixture",
        model = "fixture",
        systemPrompt = "",
        thinkingEnabled = effort == ReasoningEffort.HIGH,
        reasoningEffort = effort,
        reasoningCapabilities = capabilities,
    )

    private fun request(config: AgentModelClient.ModelConfig, purpose: ProviderRequestPurpose) =
        ProviderRequest(config = config, messages = JSONArray(), tools = JSONArray(), purpose = purpose)

    @Test
    fun compactionTurnsThinkingOffWhenModelAllowsIt() {
        // 与 deepseek-v4.1-flash 的能力声明一致：可关闭，支持 LOW/HIGH/MAX。
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            defaultEffort = ReasoningEffort.HIGH,
            canDisable = true,
        )
        val chatConfig = config(ReasoningEffort.HIGH, capabilities)
        assertEquals(
            ReasoningEffort.OFF,
            request(chatConfig, ProviderRequestPurpose.COMPACTION).effectiveConfig.reasoningEffort,
        )
        // 对话本身不能被改：用户选的 HIGH 仍要生效。
        assertEquals(
            ReasoningEffort.HIGH,
            request(chatConfig, ProviderRequestPurpose.CHAT).effectiveConfig.reasoningEffort,
        )
    }

    @Test
    fun compactionKeepsThinkingForMandatoryReasoningModels() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
            defaultEffort = ReasoningEffort.HIGH,
            mandatory = true,
        )
        val chatConfig = config(ReasoningEffort.HIGH, capabilities)
        assertEquals(
            ReasoningEffort.HIGH,
            request(chatConfig, ProviderRequestPurpose.COMPACTION).effectiveConfig.reasoningEffort,
        )
    }

    @Test
    fun compactionKeepsConfiguredEffortWhenCapabilitiesAreUnknown() {
        // 能力未知时不能猜：发一个端点不认的 reasoning_effort=none 会让摘要直接 400。
        val chatConfig = config(ReasoningEffort.MAX, capabilities = null)
        assertEquals(
            ReasoningEffort.MAX,
            request(chatConfig, ProviderRequestPurpose.COMPACTION).effectiveConfig.reasoningEffort,
        )
    }

    @Test
    fun compactionStillStripsHostedSearchAndCustomBody() {
        val chatConfig = config(ReasoningEffort.HIGH, capabilities = null).copy(
            hostedWebSearchEnabled = true,
            extraBodyJson = """{"temperature":1}""",
        )
        val effective = request(chatConfig, ProviderRequestPurpose.COMPACTION).effectiveConfig
        assertTrue(!effective.hostedWebSearchEnabled)
        assertEquals("", effective.extraBodyJson)
        assertTrue(effective.customBody.isEmpty())
    }
}
