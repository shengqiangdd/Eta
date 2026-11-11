package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryDocument
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemorySectionUi
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.SectionEditorUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private var hasLoadedOnce = false

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        if (!hasLoadedOnce) {
            memoryState = memoryState.copy(isLoading = true, notice = null)
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                MemoryLoad(
                    snapshot = snapshot.content,
                    byteSize = snapshot.byteSize,
                    enabled = enabled,
                    coreBudget = AgentMemoryContextBuilder.coreBudgetChars(contextWindow),
                    sections = snapshot.content.toSectionUi(),
                )
            }.fold(
                onSuccess = { loaded ->
                    withContext(Dispatchers.Main) {
                        val keepDraft = hasLoadedOnce && memoryState.hasUnsavedChanges
                        val nextDraft = if (keepDraft) memoryState.draft else snapshot.content
                        val nextBytes = if (keepDraft) memoryState.draftBytes else snapshot.byteSize
                        hasLoadedOnce = true
                        memoryState = AgentMemoryUiState(
                            enabled = loaded.enabled,
                            isLoading = false,
                            draft = nextDraft,
                            savedContent = loaded.snapshot,
                            draftBytes = nextBytes,
                            coreBudgetChars = loaded.coreBudget,
                            sections = loaded.sections,
                            notice = memoryState.notice,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                sections = memoryState.draft.toSectionUi(),
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                sections = emptyList(),
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    fun openSection(title: String) {
        val target = AgentMemoryDocument.find(AgentMemoryDocument.sections(memoryState.draft), title)
        if (target == null) {
            memoryState = memoryState.copy(notice = appContext.getString(R.string.memory_notice_section_missing, title))
            return
        }
        memoryState = memoryState.copy(
            sectionEditor = SectionEditorUi(title = target.title, draft = target.body),
            notice = null,
        )
    }

    fun updateSectionDraft(content: String) {
        val editor = memoryState.sectionEditor ?: return
        memoryState = memoryState.copy(sectionEditor = editor.copy(draft = content), notice = null)
    }

    fun cancelSection() {
        memoryState = memoryState.copy(sectionEditor = null)
    }

    /**
     * 保存单个章节。
     *
     * 整页有未保存改动时直接拒绝：两种写路径同时存在时，「以磁盘为准」的章节保存
     * 会悄悄丢掉用户刚在整页编辑里改的内容。先让用户保存或放弃整页修改，意图才明确。
     */
    fun saveSection() {
        val editor = memoryState.sectionEditor ?: return
        if (editor.isSaving) return
        if (memoryState.hasUnsavedChanges) {
            memoryState = memoryState.copy(notice = appContext.getString(R.string.memory_notice_save_page_first))
            return
        }
        memoryState = memoryState.copy(sectionEditor = editor.copy(isSaving = true), notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                AgentMemoryRepository.mutate(
                    AgentMemoryMutation.ReplaceSection(
                        revision = snapshot.revision,
                        section = editor.title,
                        content = editor.draft,
                    ),
                )
            }.fold(
                onSuccess = { result ->
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is AgentMemoryWriteResult.Success -> {
                                val content = result.snapshot.content
                                memoryState = memoryState.copy(
                                    draft = content,
                                    savedContent = content,
                                    draftBytes = result.snapshot.byteSize,
                                    sections = content.toSectionUi(),
                                    sectionEditor = null,
                                    notice = appContext.getString(R.string.memory_notice_section_saved, editor.title),
                                )
                            }
                            is AgentMemoryWriteResult.Conflict -> memoryState = memoryState.copy(
                                sectionEditor = memoryState.sectionEditor?.copy(isSaving = false),
                                notice = appContext.getString(R.string.memory_notice_conflict),
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_section_save_failed") {
                        "Agent memory section save failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            sectionEditor = memoryState.sectionEditor?.copy(isSaving = false),
                            notice = throwable.message ?: appContext.getString(R.string.memory_notice_section_save_failed),
                        )
                    }
                },
            )
        }
    }

    private data class MemoryLoad(
        val snapshot: String,
        val byteSize: Int,
        val enabled: Boolean,
        val coreBudget: Int,
        val sections: List<AgentMemorySectionUi>,
    )

}


/** 解析章节为界面目录项；解析失败时退化为空目录，不影响整页编辑。 */
private fun String.toSectionUi(): List<AgentMemorySectionUi> = runCatching {
    AgentMemoryDocument.sections(this).map { section ->
        AgentMemorySectionUi(
            title = section.title,
            level = section.level,
            startLine = section.startLine,
            endLine = section.endLine,
            chars = section.charCount,
        )
    }
}.getOrDefault(emptyList())
