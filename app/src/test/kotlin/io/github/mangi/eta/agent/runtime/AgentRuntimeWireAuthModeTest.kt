package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 订阅（OAuth）服务商必须把认证方式带过进程边界。
 *
 * 真实故障：`requestFromBundle` 重建 `ModelConfig` 时漏了 `authMode`，落到默认值 `api_key`，
 * 于是 Codex 变成「有服务商名与官方地址、但 apiKey 为空」→ 发消息直接报
 * 「请先配置 API Key（OpenAI · https://chatgpt.com/backend-api/codex）」，
 * 而同一服务商的模型列表却能正常拉取（那条路径不过这个 Bundle）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class AgentRuntimeWireAuthModeTest {
    @Test
    fun oauthAuthModeSurvivesTheWire() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "wire-auth-mode",
            prompt = "",
            images = emptyList(),
            config = AgentModelClient.ModelConfig(
                providerId = "builtin-openai",
                providerName = "OpenAI",
                baseUrl = "https://chatgpt.com/backend-api/codex",
                apiKey = "",
                model = "gpt-6.1-sol",
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
                systemPrompt = "",
                reasoningEffort = ReasoningEffort.OFF,
            ),
        )

        val restored = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toBundle(request, emptyList(), null),
        )

        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.config.authMode)
        assertTrue("订阅凭据不得带 apiKey 过线", restored.config.apiKey.isEmpty())
    }
}