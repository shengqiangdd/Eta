package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.acp.AcpProvider
import io.github.mangi.eta.agent.acp.acpLaunchEnv
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅登录（ChatGPT OAuth）接到委派的那条路。
 *
 * 这里守两件事，都很容易被后续改动悄悄弄坏：
 * 1. **能不能用**：订阅类提供商没有 apiKey，若判据还盯着 apiKey，用户已登录也会被标成不可用；
 * 2. **不外泄**：token 一旦拼进启动脚本就等于昭告 `ps`，而启动脚本确实会进命令行。
 */
class AcpSubscriptionRoutingTest {
    private fun model(id: String) = Model(id = id, modelId = id, displayName = id)

    private fun chatgptSubscription(apiKey: String = "") = OpenAiCompatibleProviderSetting(
        id = "openai-sub",
        name = "OpenAI 订阅",
        baseUrl = "https://api.openai.com/v1",
        apiKey = apiKey,
        authMode = ProviderAuthModes.OPENAI_CHATGPT,
        models = listOf(model("gpt-5")),
    )

    private fun grokSubscription() = OpenAiCompatibleProviderSetting(
        id = "grok-sub",
        name = "Grok 订阅",
        baseUrl = "https://api.x.ai/v1",
        authMode = ProviderAuthModes.XAI_GROK,
        models = listOf(model("grok-4")),
    )

    private fun subscriptionConfig(
        accessToken: String = "ACCESS_TOKEN_VALUE",
        idToken: String = "ID_TOKEN_VALUE",
        accountId: String = "ACCOUNT_VALUE",
    ) = AcpProvider.Config(
        id = "openai-sub",
        name = "OpenAI 订阅",
        baseUrl = "",
        apiKey = "",
        wireApi = AcpProvider.CODEX_WIRE_API,
        subscription = AcpProvider.Subscription(accessToken, idToken, accountId),
    )

    // ---- 谁能用 ----

    @Test
    fun chatGptSubscriptionIsUsableForCodexOnly() {
        // Claude 只认 Anthropic 口径的凭据，拿 ChatGPT 订阅给它只会得到一个 401。
        assertTrue(acpProviderSupportsAgent(chatgptSubscription(), "codex"))
        assertFalse(acpProviderSupportsAgent(chatgptSubscription(), "claude"))
    }

    @Test
    fun grokSubscriptionIsUsableByNoAgent() {
        // 没有任何 ACP 代理能跑 Grok 模型。列出来只会是一个永远点不成的选项。
        assertFalse(acpProviderSupportsAgent(grokSubscription(), "codex"))
        assertFalse(acpProviderSupportsAgent(grokSubscription(), "claude"))
    }

    @Test
    fun subscriptionReadyFollowsLoginStateNotApiKey() {
        val provider = chatgptSubscription(apiKey = "")
        // 不注入判断时保守判 false：宁可显示不可用，也不要给一个点了必然失败的选项。
        assertFalse(acpProviderReady(provider, "codex"))
        assertTrue(acpProviderReady(provider, "codex") { true })
        assertFalse(acpProviderReady(provider, "codex") { false })
    }

    @Test
    fun apiKeyProviderIsUnaffectedByTheSubscriptionPath() {
        // 反向断言：普通 key 类提供商的判据不能被新分支带偏。
        val keyed = OpenAiCompatibleProviderSetting(
            id = "keyed",
            name = "中转",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-x",
            models = listOf(model("m1")),
        )
        assertTrue(acpProviderReady(keyed, "codex"))
        assertFalse(acpProviderReady(keyed.copy(apiKey = ""), "codex"))
    }

    // ---- config.toml 该长什么样 ----

    @Test
    fun subscriptionConfigOmitsCustomProviderSection() {
        val toml = AcpProvider.configToml(subscriptionConfig(), "gpt-5")

        assertTrue(toml.contains("model = \"gpt-5\""))
        // 关键：写了 model_provider，codex 就会走「自定义提供商 + env_key」那条路，
        // 而那条路要的是 API key —— auth.json 里的订阅 token 永远不会被读到。
        assertFalse(toml.contains("model_provider"))
        assertFalse(toml.contains("[model_providers."))
        assertFalse(toml.contains("env_key"))
    }

    @Test
    fun apiKeyConfigStillWritesProviderSection() {
        val keyed = AcpProvider.Config(
            id = "relay",
            name = "中转",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-x",
            wireApi = AcpProvider.CODEX_WIRE_API,
        )
        val toml = AcpProvider.configToml(keyed, "m1")

        assertTrue(toml.contains("model_provider = \"relay\""))
        assertTrue(toml.contains("[model_providers.relay]"))
        assertTrue(toml.contains("env_key"))
    }

    @Test
    fun subscriptionConfigIsUsableWithoutApiKey() {
        // 旧判据要求 apiKey 非空，订阅提供商会被判成「不可用」而从候选里掉出去。
        assertTrue(subscriptionConfig().usable)
    }

    // ---- 安全：token 不许出现在会进命令行的字符串里 ----

    @Test
    fun launchScriptReferencesTokenVariablesButNeverTheirValues() {
        val script = AcpProvider.launchScript("s1", subscriptionConfig(), "gpt-5")

        assertTrue(script.contains("auth.json"))
        assertTrue(script.contains("chmod 600"))
        assertTrue(script.contains(AcpProvider.ENV_ACCESS_TOKEN))
        assertTrue(script.contains(AcpProvider.ENV_ID_TOKEN))
        assertTrue(script.contains(AcpProvider.ENV_ACCOUNT_ID))
        // 这一段会作为内层脚本进命令行（su -c 的 argv）。只要值出现在这里，`ps` 就能读到。
        assertFalse(script.contains("ACCESS_TOKEN_VALUE"))
        assertFalse(script.contains("ID_TOKEN_VALUE"))
        assertFalse(script.contains("ACCOUNT_VALUE"))
    }

    @Test
    fun authJsonTemplateCarriesNoRefreshToken() {
        val script = AcpProvider.launchScript("s1", subscriptionConfig(), "gpt-5")

        // refresh_token 留空是刻意的：codex 会轮换它，轮换后 Eta 手里那份就失效了，
        // 用户下次要重新登录。宁可长任务过期一次，也不碰用户的登录态。
        assertTrue(script.contains("\"refresh_token\":\"\""))
    }

    @Test
    fun tokensWithJsonBreakingCharactersAreRejectedBeforeUse() {
        // 值会嵌进 shell 拼的 JSON。带引号的值能凭空造出额外字段，必须在写入前就判死。
        assertFalse(AcpProvider.Subscription("a\"b", "it", "acc").usable)
        assertFalse(AcpProvider.Subscription("at", "it", "acc\n").usable)
        assertFalse(AcpProvider.Subscription("at", "it", "acc\\x").usable)
        assertTrue(AcpProvider.Subscription("at.OK-_1", "it", "acc-1").usable)
    }

    // ---- 启动环境 ----

    @Test
    fun subscriptionEnvCarriesThreeVarsAndSkipsApiKeyEnv() {
        val env = acpLaunchEnv(
            sessionId = "s1",
            provider = subscriptionConfig(),
            credentialEnvKeys = listOf("OPENAI_API_KEY"),
            env = { "sk-from-shell" },
        )
        val keys = env.secrets.map { it.key }

        assertTrue(AcpProvider.ENV_ACCESS_TOKEN in keys)
        assertTrue(AcpProvider.ENV_ID_TOKEN in keys)
        assertTrue(AcpProvider.ENV_ACCOUNT_ID in keys)
        assertFalse(AcpProvider.ENV_KEY in keys)
        // 环境里残留的 API key 会盖过 auth.json（codex 优先读 env），
        // 表现为「选了订阅却在按 key 计费」，且错误信息不会提这一点。
        assertFalse("OPENAI_API_KEY" in keys)
    }

    @Test
    fun apiKeyModeStillInjectsTheEnvKey() {
        val keyed = AcpProvider.Config(
            id = "relay",
            name = "中转",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-x",
            wireApi = AcpProvider.CODEX_WIRE_API,
        )
        val keys = acpLaunchEnv("s1", keyed, emptyList()).secrets.map { it.key }

        assertEquals(listOf(AcpProvider.ENV_KEY), keys)
    }
}
