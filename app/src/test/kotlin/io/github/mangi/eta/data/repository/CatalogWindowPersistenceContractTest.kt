package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.repository.ProviderRepository.withCatalogContextWindows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 窗口补全写入数据库后不会丢——纯 JVM，不依赖 Robolectric。
 *
 * `fillCatalogContextWindows` 的落库路径是
 * `withCatalogContextWindows` → `replaceModels` → `Model.toEntity` →
 * （Room upsert）→ `ProviderModelEntity.toDomain` → `effectiveContextWindow`。
 * 中间任一环漏掉 `contextWindowOverride`，窗口就白填了，而 UI 上查不出来：
 * `contextWindow` 列仍然显示正常，只有运行时压缩与预算静默回落到 128K 兜底。
 *
 * 这里调的是**真实**的 `toEntity` / `toDomain`（已从 private 提到 internal），
 * 不是手写的副本——手写副本无法发现映射本身漏字段。
 * 唯一没覆盖的是 Room 的 SQL 读写本身；那条由 `ProviderRepositoryTest` 在支持
 * Robolectric 的机器上覆盖。
 */
class CatalogWindowPersistenceContractTest {

    private fun catalogModel(modelId: String, window: Int) = Model(
        id = "preset-$modelId",
        modelId = modelId,
        displayName = modelId,
        contextWindow = window,
    )

    @Test
    fun overrideSurvivesTheRealEntityRoundTrip() {
        val original = catalogModel("claude-fable-5", 1_000_000)
            .copy(source = ModelSource.CATALOG, contextWindowOverride = 1_000_000)

        val restored = original.toEntity("builtin-anthropic").toDomain()

        assertEquals(1_000_000, restored.contextWindow)
        assertEquals("写库再读回，override 不能丢", 1_000_000, restored.contextWindowOverride)
        assertEquals(1_000_000, restored.effectiveContextWindow)
    }

    @Test
    fun fillProducesAnOverrideThatThenSurvivesTheRoundTrip() {
        val presets = listOf(
            catalogModel("claude-fable-5", 1_000_000),
            catalogModel("kimi-k3", 262_144),
        )
        val stored = presets.map {
            it.copy(id = it.id + "-stored", source = ModelSource.CATALOG, contextWindowOverride = null)
        }

        // 未补全时运行时确实看不到窗口——这是修复前的病。
        stored.forEach { assertEquals(null, it.effectiveContextWindow) }

        val filled = withCatalogContextWindows(stored, presets)
        filled.forEach { model ->
            assertEquals("${model.modelId} 应被填上 override", model.contextWindow, model.contextWindowOverride)
            val afterDb = model.toEntity("builtin-anthropic").toDomain()
            assertEquals(
                "${model.modelId} 落库读回后运行时仍应有窗口",
                model.contextWindow,
                afterDb.effectiveContextWindow,
            )
        }
    }

    @Test
    fun aUserConfiguredOverrideAlsoSurvivesTheRoundTrip() {
        val userSet = catalogModel("claude-fable-5", 1_000_000)
            .copy(source = ModelSource.CATALOG, contextWindowOverride = 64_000)
        val afterDb = userSet.toEntity("builtin-anthropic").toDomain()
        assertEquals(64_000, afterDb.effectiveContextWindow)
    }

    @Test
    fun secondRunIsANoOpSoRevisionBumpDoesNotRewriteEveryRow() {
        // fillCatalogContextWindows 只在 updated != provider.models 时调 replaceModels；
        // 已填过的再跑一遍必须完全相同，否则每次启动都全表重写一遍模型。
        val presets = listOf(catalogModel("claude-fable-5", 1_000_000))
        val first = withCatalogContextWindows(
            presets.map { it.copy(source = ModelSource.CATALOG, contextWindowOverride = null) },
            presets,
        )
        assertEquals(first, withCatalogContextWindows(first, presets))
    }
}
