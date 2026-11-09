package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录窗口接通运行时的纯函数契约。
 *
 * 不放 ProviderRepositoryTest：那个类整个依赖 Robolectric，本机（Linux aarch64）没有
 * native runtime，被 Gradle 整体排除——写在里面的断言根本不会执行（见
 * tools/test_subset_baseline.txt）。这里只测 [ProviderRepository.withCatalogContextWindows]
 * 的内核，不碰数据库，普通 JUnit 就能跑。
 */
class CatalogContextWindowFillTest {

    private fun catalog(body: String) = Model(
        id = "catalog-$body",
        modelId = body,
        displayName = body,
        source = ModelSource.CATALOG,
        contextWindow = 1_000_000,
    )

    @Test
    fun catalogAnnotatedWindowBecomesTheRuntimeWindow() {
        val stored = catalog("claude-fable-5")
        val result = ProviderRepository.withCatalogContextWindows(
            models = listOf(stored),
            presets = listOf(catalog("claude-fable-5")),
        ).single()
        assertEquals(1_000_000, result.effectiveContextWindow)
        assertEquals(1_000_000, result.contextWindow)
    }

    @Test
    fun everyBuiltInCatalogProviderWithAnAnnotationGetsARuntimeWindow() {
        // 回归：只有 OpenAI/Gemini 在目录里自带 override，其余七个 provider 的窗口是死的。
        var providers = 0
        var models = 0
        val nonOpenAi = mutableSetOf<String>()
        for (builtin in BuiltinProviders.PROVIDERS) {
            val provider = BuiltinProviders.providerById(builtin.id) ?: continue
            val presets = OfficialModelCatalog.modelsForProvider(provider).filter { it.contextWindow != null }
            if (presets.isEmpty()) continue
            val filled = ProviderRepository.withCatalogContextWindows(
                models = presets.map { it.copy(contextWindowOverride = null) },
                presets = presets,
            )
            filled.forEach { model ->
                assertEquals(
                    "${provider.id}/${model.modelId} 的目录窗口未接通运行时",
                    model.contextWindow,
                    model.effectiveContextWindow,
                )
            }
            providers++
            models += filled.size
            if (builtin.id != BuiltinProviders.OPENAI_ID && builtin.id != BuiltinProviders.GEMINI_ID) {
                nonOpenAi += builtin.id
            }
        }
        assertTrue("应覆盖多个 provider，实际 $providers", providers >= 7)
        assertTrue("应覆盖 >= 20 个模型，实际 $models", models >= 20)
        assertTrue("应包含非 OpenAI/Gemini 的 provider，实际 $nonOpenAi", nonOpenAi.isNotEmpty())
    }

    @Test
    fun aUserConfiguredWindowIsNeverOverwritten() {
        val userSet = catalog("claude-fable-5").copy(contextWindowOverride = 64_000)
        val result = ProviderRepository.withCatalogContextWindows(
            models = listOf(userSet),
            presets = listOf(catalog("claude-fable-5")),
        ).single()
        assertEquals(64_000, result.effectiveContextWindow)
    }

    @Test
    fun manualAndRemoteModelsAreLeftAlone() {
        val manual = Model(
            id = "m1", modelId = "claude-fable-5", displayName = "手动",
            source = ModelSource.MANUAL,
        )
        val remoteWithoutWindow = Model(
            id = "r1", modelId = "claude-fable-5", displayName = "远端",
            source = ModelSource.REMOTE,
        )
        val result = ProviderRepository.withCatalogContextWindows(
            models = listOf(manual, remoteWithoutWindow),
            presets = listOf(catalog("claude-fable-5")),
        )
        assertNull(result[0].effectiveContextWindow)
        assertNull(
            "远端模型没标注窗口时不应从目录凭空补",
            result[1].effectiveContextWindow,
        )
    }

    @Test
    fun remoteModelWithItsOwnWindowKeepsThatWindow() {
        val remote = Model(
            id = "r2", modelId = "claude-fable-5", displayName = "远端",
            source = ModelSource.REMOTE, contextWindow = 272_000,
        )
        val result = ProviderRepository.withCatalogContextWindows(
            models = listOf(remote),
            presets = listOf(catalog("claude-fable-5")),
        ).single()
        assertEquals(272_000, result.effectiveContextWindow)
    }

    @Test
    fun modelWithoutACatalogEntryIsUntouched() {
        val unknown = Model(
            id = "c9", modelId = "not-in-catalog", displayName = "未知",
            source = ModelSource.CATALOG,
        )
        val result = ProviderRepository.withCatalogContextWindows(
            models = listOf(unknown),
            presets = listOf(catalog("claude-fable-5")),
        ).single()
        assertNull(result.effectiveContextWindow)
    }

    @Test
    fun oauthLoggedInOpenAiStillGetsItsWindowsFilled() {
        // 回归：revision 5 曾用 `authMode == API_KEY` 过滤，ChatGPT 登录后
        // authMode=openai_chatgpt，整个 provider 被跳过，窗口永远补不上。
        val openAi = BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting
        val models = OfficialModelCatalog.modelsForProvider(openAi).ifEmpty { listOf(catalog("gpt-6.1-sol")) }
        for (mode in listOf(
            ProviderAuthModes.API_KEY,
            ProviderAuthModes.OPENAI_CHATGPT,
            ProviderAuthModes.XAI_GROK,
        )) {
            val provider = openAi.copy(authMode = mode).withModels(models)
            assertTrue(
                "authMode=$mode 的 OpenAI 应该参与窗口补全",
                ProviderRepository.shouldFillCatalogContextWindows(provider),
            )
        }
    }

    @Test
    fun nonBuiltInOrModelLessProvidersAreNotFilled() {
        val openAi = BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting
        val models = listOf(catalog("gpt-6.1-sol"))
        // 用户自建的 provider 不属于目录，不该被动。
        val custom = openAi.copy(id = "custom-1", isBuiltIn = false).withModels(models)
        assertFalse(ProviderRepository.shouldFillCatalogContextWindows(custom))
        // 没模型的 provider 没东西可填。
        assertFalse(ProviderRepository.shouldFillCatalogContextWindows(openAi.withModels(emptyList())))
    }
}
