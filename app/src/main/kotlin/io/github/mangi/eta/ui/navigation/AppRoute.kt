package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data class Browser(val sessionKey: String = BrowserSessionRegistry.MANUAL_SESSION_KEY) : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object WebToolsSettings : AppRoute

    @Serializable
    data object Skills : AppRoute

    /** 插件管理：已安装插件的能力、开关与卸载。 */
    @Serializable
    data object Plugins : AppRoute

    @Serializable
    data object Subagents : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object SpeechRecognition : AppRoute

    @Serializable
    data object SpeechSynthesis : AppRoute

    @Serializable
    data object SpeechOss : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object EnvVars : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    /** 能力总览：按能力分组收拢所有页面入口。 */
    @Serializable
    data object Capabilities : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object CommunityCatalog : AppRoute

    @Serializable
    data class CommunityCatalogProvider(val catalogId: String) : AppRoute

    @Serializable
    data object McpServers : AppRoute

    /** 模型用量：本地 token 账本的时间窗聚合。 */
    @Serializable
    data object Usage : AppRoute

    /** 运行详情：runId 为空时展示最近运行列表。 */
    @Serializable
    data class RunInspector(val runId: String? = null) : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic, Gemini }
