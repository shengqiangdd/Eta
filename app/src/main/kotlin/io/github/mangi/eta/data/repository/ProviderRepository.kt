package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.withSortOrder
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ProviderRepository {
    /** 配置与模型共用修改锁，避免旧连接的目录在认证方式切换后落库。 */
    internal val mutationMutex = Mutex()

    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        applicationContext = context.applicationContext
        ProviderCredentialRepository.init(context)
    }

    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain() }
                .sortedBy(ProviderSetting::sortOrder)
        }

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain() }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val added = mutationMutex.withLock {
            val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
            provider.withSortOrder(nextOrder).also { replaceProvider(it) }
        }
        repairSelection()
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        mutationMutex.withLock {
            val previous = requireNotNull(providerById(provider.id)) { "Provider 不存在" }
            if (previous.authMode != provider.authMode) {
                forgetCredentials(provider.id)
            }
            require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        }
        repairSelection()
    }

    internal suspend fun replaceModels(providerId: String, models: List<Model>) {
        val provider = requireNotNull(providerById(providerId)) { "Provider 不存在" }
        dao().replaceModels(
            providerId = providerId,
            models = provider.withModels(models).toModelEntities(),
        )
    }

    suspend fun deleteProvider(id: String) {
        val deleted = mutationMutex.withLock {
            val provider = providerById(id) ?: return@withLock false
            if (provider.isBuiltIn) return@withLock false
            forgetCredentials(id)
            dao().deleteProvider(id)
            true
        }
        if (!deleted) return
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val copy = mutationMutex.withLock {
            val source = providerById(id) ?: return@withLock null
            val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
            source.deepCopy(
                id = newId(),
                name = "${source.name} 副本",
                sortOrder = nextOrder,
                builtIn = false,
            ).also { replaceProvider(it) }
        } ?: return null
        repairSelection()
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        mutationMutex.withLock {
            val current = providerById(id)
            forgetCredentials(id)
            val restored = seedOfficialModelsIfEmpty(
                current
                    ?.let { existing ->
                        builtIn.withApiKey(existing.apiKey.takeIf { existing.authMode == ProviderAuthModes.API_KEY }.orEmpty())
                            .withSortOrder(existing.sortOrder)
                    }
                    ?: builtIn
            )
            replaceProvider(restored)
        }
        repairSelection()
    }

    suspend fun ensureBuiltInsMerged() {
        mutationMutex.withLock {
            val current = allProviders()
            if (current.isEmpty()) {
                insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
                // 新装用户不走下面的 revision 分支，窗口要在这里补一次，否则新装的
                // Claude/Kimi/Qwen 等目录窗口同样永远是死的。
                allProviders().forEach { fillCatalogContextWindows(it) }
                SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
                return@withLock
            }

            val existingIds = current.mapTo(mutableSetOf()) { it.id }
            val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
            if (missing.isNotEmpty()) {
                val nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
                insertProviders(missing.mapIndexed { index, provider ->
                    seedOfficialModelsIfEmpty(provider.withSortOrder(nextOrder + index))
                })
                missing.forEach { provider -> fillCatalogContextWindows(requireNotNull(providerById(provider.id))) }
            }
            val appliedRevision = SettingsDataStore.officialModelCatalogRevision()
            if (appliedRevision < OfficialModelCatalog.CURRENT_REVISION) {
                if (appliedRevision < 2) {
                    current.filter { ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI }
                        .forEach { refreshOpenAiPresets(it, appliedRevision) }
                }
                current.filter {
                    it.isBuiltIn && it.authMode == ProviderAuthModes.API_KEY && BuiltinProviders.providerById(it.id) != null
                }.forEach { provider ->
                    val additions = if (ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.OPENAI) {
                        emptyList()
                    } else OfficialModelCatalog.modelsAddedSince(provider, appliedRevision)
                    dao().appendModelsIfAbsent(
                        providerId = provider.id,
                        candidates = provider.withModels(additions).toModelEntities(),
                    )
                    if (appliedRevision < 1) {
                        refreshLegacyCatalogReasoningCapabilities(provider)
                    }
                }
                if (appliedRevision < 3) {
                    current.filter { ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI }.forEach { provider ->
                        fillCatalogContextWindows(requireNotNull(providerById(provider.id)))
                    }
                }
                // revision 5：其余七个内置 provider 的目录窗口一直是死的（<3 那档只填 OpenAI，
                // 且 OpenAI/Gemini 在目录里自带了 override）。补全后再统一改版本号，否则
                // 用户永远停在 appliedRevision=4，这段不会重跑。
                //
                // 不能按 authMode == API_KEY 过滤：OpenAI/xAI 用 OAuth 登录后 authMode 会变成
                // openai_chatgpt/xai_grok（ProviderConfigDraft.withAuthMode 经 updateProvider 落库），
                // 整个 provider 因此被跳过，这批用户的窗口永远补不上。窗口补全不碰凭据，
                // 只依据模型是否带窗口，所以按 isBuiltIn 过滤即可。
                if (appliedRevision < 5) {
                    current.filter(::shouldFillCatalogContextWindows).forEach { provider ->
                        fillCatalogContextWindows(requireNotNull(providerById(provider.id)))
                    }
                }
                SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
            }
        }
        repairSelection()
    }

    private suspend fun refreshOpenAiPresets(provider: ProviderSetting, revision: Int) {
        val currentPresets = OfficialModelCatalog.modelsForProvider(provider)
        val currentIds = currentPresets.mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        val retained = provider.models.filter { it.source != ModelSource.CATALOG || it.modelId.normalizedModelId() in currentIds }
        // 已有远端目录或用户清空的连接不能再由预设填充。
        val additions = if (provider.models.any { it.source == ModelSource.CATALOG } &&
            provider.models.none { it.source == ModelSource.REMOTE }
        ) OfficialModelCatalog.modelsAddedSince(provider, revision) else emptyList()
        val existingIds = retained.mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        val nextOrder = (retained.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val updated = retained + additions.filterNot { it.modelId.normalizedModelId() in existingIds }
            .mapIndexed { index, model -> model.copy(id = newId(), sortOrder = nextOrder + index) }
        if (updated != provider.models) replaceModels(provider.id, updated)
    }

    /**
     * 把官方目录里标注的窗口写进运行时窗口（[Model.contextWindowOverride]）。
     *
     * 官方目录的 `contextWindow` 只是元数据：运行时只认 `contextWindowOverride`（见
     * [Model.effectiveContextWindow]）。两个值不会自动打通，所以每个目录都必须显式接一次，
     * 否则标注值永远不生效。这里对**所有**内置 provider 做，不再只限 OpenAI——以前
     * 只有 OpenAI/Gemini 在目录里自带了 `copy(contextWindowOverride = ...)`，其余七个
     * provider 的 28 个模型（Claude 1M、Kimi 256K、Qwen 1M 等）窗口全部是死的。
     *
     * 收益不在压缩触发点（那条是 `min(窗口×0.85, 10 万)`，大窗口本来就被绝对上限定住），
     * 而在两处用户能直接感知的地方：
     * - 记忆核心预算 `min(32000, max(4000, 窗口/16))`：1M 窗口从 8,000 字符提到 32,000；
     * - 硬上限 `max(1.2M, 窗口×2)`：1M 窗口从 1.2M 字符提到 2M，安全阀不会过早触发。
     *
     * 不覆盖用户手填值：`contextWindowOverride != null` 直接跳过，MANUAL 模型也不动。
     */
    private suspend fun fillCatalogContextWindows(provider: ProviderSetting) {
        val updated = withCatalogContextWindows(
            models = provider.models,
            presets = OfficialModelCatalog.modelsForProvider(provider),
        )
        if (updated != provider.models) replaceModels(provider.id, updated)
    }

    /**
     * 判断某 provider 是否需要做一次目录窗口补全。
     *
     * 故意**不看** `authMode`：OpenAI/xAI 用 OAuth 登录后 `authMode` 会变成
     * openai_chatgpt/xai_grok（`ProviderConfigDraft.withAuthMode` 经 `updateProvider` 落库）。
     * 曾经这里写 `authMode == API_KEY`，结果这批用户的窗口永远补不上——补全不碰凭据，
     * 只看模型本身，与该用哪种登录方式无关。
     */
    internal fun shouldFillCatalogContextWindows(provider: ProviderSetting): Boolean =
        provider.isBuiltIn && BuiltinProviders.providerById(provider.id) != null && provider.models.isNotEmpty()

    /**
     * [fillCatalogContextWindows] 的纯函数内核，抽出来是为了能在未安装 Robolectric native
     * runtime 的开发机（Linux aarch64）上直接单测：整个逻辑只涉及 list 与 copy，不需要数据库。
     */
    internal fun withCatalogContextWindows(models: List<Model>, presets: List<Model>): List<Model> {
        val byModelId = presets.associateBy { it.modelId.normalizedModelId() }
        return models.map { model ->
            if (model.contextWindowOverride != null || model.source == ModelSource.MANUAL) return@map model
            val preset = byModelId[model.modelId.normalizedModelId()] ?: return@map model
            val window = model.contextWindow
                ?: preset.contextWindow.takeIf { model.source == ModelSource.CATALOG }
                ?: return@map model
            model.copy(contextWindow = window, contextWindowOverride = window)
        }
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val activeModel = selectedProvider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = selectedProvider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val selectedModel = activeModel ?: selectedProvider?.selectedOrFirstModel(rememberedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun forgetCredentials(providerId: String) = withContext(Dispatchers.IO) {
        ProviderCredentialRepository.forget(providerId)
    }

    private fun dao() =
        EtaDatabase.get(appContext()).providerDao()

    private fun appContext(): Context {
        check(::applicationContext.isInitialized) {
            "ProviderRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }

    private suspend fun replaceProvider(provider: ProviderSetting) {
        dao().replaceProvider(
            provider = provider.toEntity(),
            models = provider.toModelEntities(),
        )
    }

    private suspend fun insertProviders(providers: List<ProviderSetting>) {
        dao().insertProvidersWithModels(
            providers.map { provider ->
                ProviderWithModelsSeed(
                    provider = provider.toEntity(),
                    models = provider.toModelEntities(),
                )
            }
        )
    }

    private fun seedOfficialModelsIfEmpty(provider: ProviderSetting): ProviderSetting {
        if (provider.models.isNotEmpty() || ProviderAuthModes.isOAuth(provider.authMode)) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }
    private suspend fun refreshLegacyCatalogReasoningCapabilities(provider: ProviderSetting) {
        val legacyCapabilities = when (provider.id) {
            BuiltinProviders.ANTHROPIC_ID -> mapOf("claude-fable-5" to ModelReasoningCapabilities(
                supportedEfforts = listOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX,
                ),
                defaultEffort = ReasoningEffort.HIGH,
                defaultEnabled = true,
                canDisable = true,
            ))
            BuiltinProviders.STEPFUN_ID -> mapOf("step-3.7-flash" to ModelReasoningCapabilities(
                defaultEnabled = true,
                mandatory = true,
            ))
            else -> return
        }
        val updatedJsonByModelId = provider.withModels(OfficialModelCatalog.modelsForProvider(provider))
            .toModelEntities()
            .associate { it.modelId to it.reasoningCapabilitiesJson }
        val storedRowsById = dao().models(provider.id).associateBy { it.id }
        provider.models.forEach { storedModel ->
            val previousCapabilities = legacyCapabilities[storedModel.modelId] ?: return@forEach
            if (storedModel.source != ModelSource.CATALOG ||
                storedModel.reasoningCapabilities != previousCapabilities
            ) return@forEach
            val updatedJson = updatedJsonByModelId[storedModel.modelId] ?: return@forEach
            val storedRow = storedRowsById[storedModel.id] ?: return@forEach
            if (updatedJson == storedRow.reasoningCapabilitiesJson) return@forEach
            dao().updateCatalogReasoningCapabilitiesIfUnchanged(
                providerId = provider.id,
                modelId = storedModel.id,
                apiModelId = storedModel.modelId,
                previousJson = storedRow.reasoningCapabilitiesJson,
                updatedJson = updatedJson,
            )
        }
    }

    private fun ProviderSetting.deepCopy(
        id: String,
        name: String,
        sortOrder: Int,
        builtIn: Boolean,
    ): ProviderSetting {
        val copiedModels = models.mapIndexed { index, model ->
            model.copy(id = newId(), isBuiltIn = builtIn, sortOrder = index)
        }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is GeminiProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is AnthropicProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is CustomProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )
        }
    }
}
