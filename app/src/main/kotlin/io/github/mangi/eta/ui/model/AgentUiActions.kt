package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data object LoadEarlierMessages : AgentHomeAction
    data class HistoryViewportChanged(val atLatest: Boolean) : AgentHomeAction
    data object RetryConversationLoad : AgentHomeAction
    data object CompactContext : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    /** 输入框草稿变化；只写内存，不落盘、不触发聊天区重组。 */
    data class DraftChanged(val text: String) : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data class InterruptAndSubmitMessage(val text: String) : AgentHomeAction

    /** 回答 `ask_user` 的提问；不走排队（见 AgentAppState.answerPendingAsk）。 */
    data class AnswerAsk(val text: String) : AgentHomeAction
    data class SteerQueuedMessage(val id: String) : AgentHomeAction
    data class RecallQueuedMessage(val id: String, val currentDraftText: String) : AgentHomeAction
    data class RemoveQueuedMessage(val id: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    /** 暂停/继续当前会话的 run；由 state.isPaused 决定方向。 */
    data object ToggleRunPause : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
    data object OpenWebToolsSettings : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction

    /** 应用内选择器给出的路径（虚拟屏上系统文件选择器不可用）。 */
    data class ImportZipPath(val path: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
    data class OpenSection(val title: String) : AgentMemoryAction
    data class SectionDraftChanged(val content: String) : AgentMemoryAction
    data object SaveSection : AgentMemoryAction
    data object CancelSection : AgentMemoryAction
}

sealed interface AgentPluginsAction {
    data object NavigateBack : AgentPluginsAction
    data class TogglePlugin(val pluginId: String, val enabled: Boolean) : AgentPluginsAction
    data class UninstallPlugin(val pluginId: String) : AgentPluginsAction
    /** 回滚到指定留底版本（[version] 为空表示最新的那份）。 */
    data class RollbackPlugin(val pluginId: String, val version: String? = null) : AgentPluginsAction
    data class ImportZip(val uri: String) : AgentPluginsAction

    /** 应用内选择器给出的路径（虚拟屏上系统文件选择器不可用）。 */
    data class ImportZipPath(val path: String) : AgentPluginsAction

    /** 下载并解析链接（不安装）：成功后 state.linkPreview 出现，等用户确认。 */
    data class PrepareLinkInstall(val url: String) : AgentPluginsAction

    /** 安装预览里的那一份暂存文件。 */
    data object ConfirmLinkInstall : AgentPluginsAction

    /** 放弃这次链接安装，删除暂存文件。 */
    data object CancelLinkInstall : AgentPluginsAction

    /** 从记录的来源链接重新下载，检查是否有新版本（有则进入预览）。 */
    data class UpdateFromSource(val pluginId: String) : AgentPluginsAction

    /** 一次性对照所有「链接安装」插件的来源，只解析清单、不安装，结果标在列表上。 */
    data object CheckUpdates : AgentPluginsAction

    data object DismissNotice : AgentPluginsAction
}
