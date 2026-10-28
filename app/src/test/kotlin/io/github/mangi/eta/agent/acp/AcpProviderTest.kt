package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.data.model.CustomHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpProviderTest {

    private val provider = AcpProvider.Config(
        id = "lingzhan",
        name = "lingzhan",
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        wireApi = "responses",
        defaultModel = "deepseek-v4.1-flash",
    )

    @Test
    fun configTomlContainsProviderAndModelButNotApiKey() {
        val toml = AcpProvider.configToml(provider, "deepseek-v4.1-flash")

        assertTrue(toml.contains("model = \"deepseek-v4.1-flash\""))
        assertTrue(toml.contains("model_provider = \"lingzhan\""))
        assertTrue(toml.contains("[model_providers.lingzhan]"))
        assertTrue(toml.contains("base_url = \"https://api.example.com/v1\""))
        assertTrue(toml.contains("env_key = \"ETA_CODEX_API_KEY\""))
        assertTrue(toml.contains("wire_api = \"responses\""))
        assertTrue(toml.contains("image_generation = false"))
        assertFalse(toml.contains("sk-test"))
    }

    @Test
    fun launchScriptCreatesPerSessionCodexHome() {
        val script = AcpProvider.launchScript("acp123", provider, "deepseek-v4.1-flash")

        assertTrue(script.contains("export CODEX_HOME='/tmp/eta-codex-acp123'"))
        assertTrue(script.contains("mkdir -p '/tmp/eta-codex-acp123'"))
        assertTrue(script.contains("cat > '/tmp/eta-codex-acp123/config.toml'"))
        assertTrue(script.contains("<<'ETA_CODEX_CONFIG'"))
        assertTrue(script.contains("model = \"deepseek-v4.1-flash\""))
        // 自定义头的值也在文件里：目录 / 文件不能给同机其他用户读。
        assertTrue(script.contains("chmod 700 '/tmp/eta-codex-acp123'"))
        assertTrue(script.contains("chmod 600 '/tmp/eta-codex-acp123/config.toml'"))
    }

    @Test
    fun customHeadersBecomeHttpHeadersSubTable() {
        val withHeaders = provider.copy(
            customHeaders = listOf(
                CustomHeader("X-Api-Version", "2026-01"),
                CustomHeader("X-Route", "fast"),
            ),
        )

        val toml = AcpProvider.configToml(withHeaders, "m")

        assertTrue(toml.contains("[model_providers.lingzhan.http_headers]"))
        assertTrue(toml.contains("\"X-Api-Version\" = \"2026-01\""))
        assertTrue(toml.contains("\"X-Route\" = \"fast\""))
        // 子表必须排在父表键之后，否则 TOML 会把前面的键算到子表里。
        assertTrue(
            toml.indexOf("[model_providers.lingzhan.http_headers]")
                > toml.indexOf("wire_api = \"responses\""),
        )
    }

    @Test
    fun headerValuesAreEscapedAndBlankNamesSkipped() {
        val withHeaders = provider.copy(
            customHeaders = listOf(
                CustomHeader(" ", "ignored"),
                CustomHeader(" X-Quote", "a\"b"),
            ),
        )

        val toml = AcpProvider.configToml(withHeaders, "m")

        assertFalse(toml.contains("ignored"))
        assertTrue(toml.contains("\"X-Quote\" = \"a\\\"b\""))
    }

    @Test
    fun noCustomHeadersKeepsConfigClean() {
        val toml = AcpProvider.configToml(provider, "m")

        assertFalse(toml.contains("http_headers"))
    }

    @Test
    fun configLimitsUpstreamRetries() {
        val toml = AcpProvider.configToml(provider, "m")

        // 默认 6 次重试在上游挂起时会把「一直等」拉成 240 秒；这里压到最多 2 次尝试。
        assertTrue(toml.contains("request_max_retries = 1"))
        assertTrue(toml.contains("stream_max_retries = 1"))
    }

    @Test
    fun tomlStringEscapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", AcpProvider.tomlString("a\"b\\c\nd"))
    }

    @Test
    fun codexAlwaysUsesResponsesWireApi() {
        // codex 0.164+ 只支持 Responses API；Eta 端的 endpointMode 只影响日常对话。
        assertEquals("responses", AcpProvider.CODEX_WIRE_API)
        val toml = AcpProvider.configToml(provider.copy(wireApi = AcpProvider.CODEX_WIRE_API), "m")
        assertTrue(toml.contains("wire_api = \"responses\""))
    }

    @Test
    fun configRequiresKeyUrlAndSupportedWireApi() {
        assertTrue(provider.usable)
        assertFalse(provider.copy(apiKey = "").usable)
        assertFalse(provider.copy(baseUrl = "").usable)
        assertFalse(provider.copy(wireApi = "grpc").usable)
        // codex 0.164+ 连 chat 都不接受，不能再让这种配置被当成可用。
        assertFalse(provider.copy(wireApi = "chat").usable)
    }
}
