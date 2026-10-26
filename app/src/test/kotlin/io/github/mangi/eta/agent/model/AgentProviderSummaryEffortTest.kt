package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 摘要请求的思考档位。
 *
 * 依据（PKX110 / Android 16，用量账本与 run-traces）：89 次 compact 请求的 462,453 个输出 token 里
 * **348,820 是 reasoning（75%）**，26 次完整压缩耗时中位数 79 秒。摘要是「写正文」的活，
 * 档位越高只是把时间烧在思考上，所以这里守住三条口径：能关就关、强制思考就降到底档、
 * 没有显式档时不要乱改。纯 JVM 可跑（不依赖 Android）。
 */
class AgentProviderSummaryEffortTest {
    private fun config(
        effort: ReasoningEffort,
        capabilities: ModelReasoningCapabilities?,
    ) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = effort != ReasoningEffort.OFF,
        reasoningEffort = effort,
        reasoningCapabilities = capabilities,
        autoCompactionEnabled = false,
    )

    private val fullRange = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
    )

    @Test
    fun disablesThinkingWhenTheModelAllowsIt() {
        val capabilities = ModelReasoningCapabilities(supportedEfforts = fullRange, canDisable = true)
        assertEquals(
            ReasoningEffort.OFF,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, capabilities)),
        )
    }

    @Test
    fun mandatoryModelsDropToTheLowestExplicitEffort() {
        val capabilities = ModelReasoningCapabilities(supportedEfforts = fullRange, mandatory = true)
        assertEquals(
            ReasoningEffort.LOW,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, capabilities)),
        )
    }

    @Test
    fun minimalBeatsLowWhenTheProviderOffersIt() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.HIGH),
            mandatory = true,
        )
        assertEquals(
            ReasoningEffort.MINIMAL,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, capabilities)),
        )
    }

    @Test
    fun modelsWithoutExplicitEffortsKeepTheUserChoice() {
        // mandatory 且没有 supportedEfforts：只能选 Default，决定权在服务端。
        val capabilities = ModelReasoningCapabilities(mandatory = true)
        assertEquals(
            ReasoningEffort.DEFAULT,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.DEFAULT, capabilities)),
        )
    }

    @Test
    fun staleUserEffortFallsBackToDefaultWhenUnsupported() {
        // 换模型后库里可能残留旧档位；原样发出去会触发「当前模型不支持……」的校验失败。
        val capabilities = ModelReasoningCapabilities(mandatory = true)
        assertEquals(
            ReasoningEffort.DEFAULT,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, capabilities)),
        )
    }

    @Test
    fun unknownCapabilitiesKeepTheUserChoice() {
        // 能力未知时不要擅自降档：可能把「用户明确要的 High」悄悄改掉。
        assertEquals(
            ReasoningEffort.HIGH,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, null)),
        )
    }

    /**
     * 本机真实配置（PKX110，模型 `cn:deepseek-v4.1-flash`，自定义服务商 wkby）：
     * 能力表只有 medium / high / max，`mandatory=true`、`canDisable=false`。
     * 用户停在 High，压缩请求只能降到 Medium —— 这是这台设备上唯一可降的一档。
     */
    @Test
    fun realDeviceConfigurationDropsHighToMedium() {
        assertEquals(
            ReasoningEffort.MEDIUM,
            ProviderRequest.summaryReasoningEffort(config(ReasoningEffort.HIGH, realDeviceCapabilities())),
        )
    }

    /**
     * 端到端：从「压缩用途」一直走到真正发出去的请求体里的 `reasoning_effort`。
     *
     * 只测 [ProviderRequest.summaryReasoningEffort] 不够 —— 中间还有 `validatedEffort` 的
     * 「必须 ∈ selectableEfforts」校验和 CUSTOM 源的分派，任一处都能让降档悄悄失效，
     * 而单测照样全绿。
     */
    @Test
    fun compactionPayloadCarriesTheLoweredEffort() {
        val modelConfig = config(ReasoningEffort.HIGH, realDeviceCapabilities())

        assertEquals("high", payload(modelConfig, ProviderRequestPurpose.CHAT).optString("reasoning_effort"))
        assertEquals(
            "medium",
            payload(modelConfig, ProviderRequestPurpose.COMPACTION).optString("reasoning_effort"),
        )
    }

    /** 关不掉思考的模型：压缩请求保持「有思考」，但档位必须是能选到的最低档。 */
    private fun realDeviceCapabilities() = ModelReasoningCapabilities(
        supportedEfforts = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX),
        defaultEnabled = true,
        mandatory = true,
        canDisable = false,
    )

    private fun payload(
        modelConfig: AgentModelClient.ModelConfig,
        purpose: ProviderRequestPurpose,
    ): JSONObject {
        val request = ProviderRequest(
            config = modelConfig,
            messages = JSONArray(),
            tools = JSONArray(),
            purpose = purpose,
        )
        val body = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(body, request.effectiveConfig)
        return body
    }
}
