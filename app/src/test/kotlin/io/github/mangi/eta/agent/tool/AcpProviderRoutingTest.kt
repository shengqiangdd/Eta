package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 委派时「哪个提供商能给哪个代理用」。
 *
 * 钉这条是因为 2026-11-05 查出的真缺陷：判据只有 `isCodexCompatible()`，
 * 却被两个代理共用，而 `AnthropicProviderSetting` 是独立类型 →
 * 用户配好了 Anthropic 官方/中转，Claude 委派却**选不到它**，
 * 只能退回 claude 自己的登录态。一个叫 codex 的判据给 claude 用，就是这么坏的。
 */
class AcpProviderRoutingTest {
    private fun model(id: String, display: String = id, enabled: Boolean = true) =
        Model(id = "m-$id", modelId = id, displayName = display, isEnabled = enabled)

    private fun openAi(
        id: String = "openai",
        name: String = "OpenAI 兼容",
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "sk-x",
        models: List<Model> = listOf(model("gpt-5")),
        enabled: Boolean = true,
    ): OpenAiCompatibleProviderSetting = OpenAiCompatibleProviderSetting(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        isEnabled = enabled,
    )

    private fun anthropic(
        id: String = "anthropic",
        name: String = "Anthropic",
        baseUrl: String = "https://api.anthropic.com",
        apiKey: String = "sk-ant-x",
        models: List<Model> = listOf(model("claude-sonnet-5")),
        enabled: Boolean = true,
    ): AnthropicProviderSetting = AnthropicProviderSetting(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        isEnabled = enabled,
    )

    private fun custom(
        id: String = "custom",
        name: String = "中转站",
        baseUrl: String = "https://relay.example.com",
        apiKey: String = "sk-y",
        models: List<Model> = listOf(model("some-model")),
    ): CustomProviderSetting = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
    )

    // ---- 类型判据：两个代理认的不是同一批 ----

    @Test
    fun anthropicProviderIsUsableForClaude() {
        assertTrue(acpProviderSupportsAgent(anthropic(), "claude"))
    }

    @Test
    fun anthropicProviderIsNotUsableForCodex() {
        // codex 只会写 config.toml 走 OpenAI 兼容接口，喂它 Anthropic 端点必然失败。
        assertFalse(acpProviderSupportsAgent(anthropic(), "codex"))
    }

    @Test
    fun openAiProviderIsUsableForCodexButNotClaude() {
        assertTrue(acpProviderSupportsAgent(openAi(), "codex"))
        // 反过来也要挡住：拿 OpenAI 兼容端点当 ANTHROPIC_BASE_URL 发，
        // Claude 侧会得到一个语焉不详的上游错误。
        assertFalse(acpProviderSupportsAgent(openAi(), "claude"))
    }

    @Test
    fun customProviderWorksForBoth() {
        // 自定义类型是两者的交集：用户填什么端点自己负责，程序不替他判断。
        assertTrue(acpProviderSupportsAgent(custom(), "codex"))
        assertTrue(acpProviderSupportsAgent(custom(), "claude"))
    }

    @Test
    fun disabledProviderIsNeverUsable() {
        assertFalse(acpProviderSupportsAgent(anthropic(enabled = false), "claude"))
        assertFalse(acpProviderSupportsAgent(openAi(enabled = false), "codex"))
    }

    @Test
    fun blankBaseUrlIsNeverUsable() {
        assertFalse(acpProviderSupportsAgent(anthropic(baseUrl = ""), "claude"))
        assertFalse(acpProviderSupportsAgent(openAi(baseUrl = "  "), "codex"))
    }

    // ---- ready：还要有 key 和模型 ----

    @Test
    fun readyRequiresApiKey() {
        assertTrue(acpProviderReady(anthropic(), "claude"))
        assertFalse(acpProviderReady(anthropic(apiKey = ""), "claude"))
    }

    @Test
    fun readyRequiresAtLeastOneEnabledModelWithId() {
        assertFalse(acpProviderReady(anthropic(models = emptyList()), "claude"))
        assertFalse(
            acpProviderReady(anthropic(models = listOf(model("x", enabled = false))), "claude"),
        )
        assertFalse(acpProviderReady(anthropic(models = listOf(model(""))), "claude"))
    }

    @Test
    fun modelIdsSkipDisabledAndBlank() {
        val provider = anthropic(
            models = listOf(model("a"), model("b", enabled = false), model(""), model("c")),
        )

        assertEquals(listOf("a", "c"), acpProviderModelIds(provider))
    }

    // ---- 默认选择优先级 ----

    @Test
    fun preferredProviderWinsOverMainModel() {
        val preferred = openAi(id = "relay")
        val main = openAi(id = "cheap")

        val pick = pickAcpProvider(
            candidates = listOf(main, preferred),
            preferredId = "relay",
            mainSelectedId = "cheap",
        )

        assertSame(preferred, pick?.provider)
        assertEquals(AcpProviderSource.PREFERRED, pick?.source)
    }

    @Test
    fun fallsBackToMainModelWhenNoPreference() {
        val cheap = openAi(id = "cheap")
        val pick = pickAcpProvider(
            candidates = listOf(openAi(id = "other"), cheap),
            preferredId = "",
            mainSelectedId = "cheap",
        )

        assertSame(cheap, pick?.provider)
        assertEquals(AcpProviderSource.MAIN, pick?.source)
    }

    @Test
    fun stalePreferenceFallsBackInsteadOfFailing() {
        // 用户删了那个提供商，或它变得不可用（候选列表里没有）：
        // 不能因此让委派整个失败，应该退回「跟随主模型」并继续能用。
        val main = openAi(id = "cheap")
        val pick = pickAcpProvider(
            candidates = listOf(main),
            preferredId = "已被删除的提供商",
            mainSelectedId = "cheap",
        )

        assertSame(main, pick?.provider)
        assertEquals(AcpProviderSource.MAIN, pick?.source)
    }

    @Test
    fun lastResortIsFirstCandidate() {
        val first = openAi(id = "first")
        val pick = pickAcpProvider(
            candidates = listOf(first, openAi(id = "second")),
            preferredId = null,
            mainSelectedId = null,
        )

        assertSame(first, pick?.provider)
        assertEquals(AcpProviderSource.FALLBACK, pick?.source)
    }

    @Test
    fun noCandidatesMeansNoPick() {
        // 返回 null 而不是抛异常：调用方据此保留「用代理自己的登录态」这条路径。
        assertNull(pickAcpProvider(emptyList(), "x", "y"))
    }

    // ---- 按名字点名 ----

    @Test
    fun matchesByIdNameOrModelName() {
        val relay = custom(id = "relay-1", name = "中转站", models = listOf(model("sq6", "SQ6")))
        val pool: List<ProviderSetting> = listOf(relay)

        assertSame(relay, matchAcpProvider(pool, "relay-1"))
        assertSame(relay, matchAcpProvider(pool, "中转站"))
        assertSame(relay, matchAcpProvider(pool, "sq6"))
        // 模型显示名也要能点：用户通常记得的是界面上看到的那个名字。
        assertSame(relay, matchAcpProvider(pool, "SQ6"))
        // 大小写不该影响 id 匹配。
        assertSame(relay, matchAcpProvider(pool, "RELAY-1"))
        assertNull(matchAcpProvider(pool, "不存在"))
    }
}
