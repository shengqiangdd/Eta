package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    val notice: String? = null,
    /** MEMORY.md 的章节目录；为空表示还没有任何标题。 */
    val sections: List<AgentMemorySectionUi> = emptyList(),
    /** 正在单独编辑的章节；null 表示没有打开章节编辑器。 */
    val sectionEditor: SectionEditorUi? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}


/** 记忆目录项：只带界面需要的字段，不持有正文（正文可能有几十 KB）。 */
@androidx.compose.runtime.Immutable
data class AgentMemorySectionUi(
    val title: String,
    val level: Int,
    val startLine: Int,
    val endLine: Int,
    val chars: Int,
)

/**
 * 章节编辑器状态。
 *
 * 单独编辑一段而不是整篇：长记忆整篇保存容易漏抄，章节替换把改动限定在目标范围，
 * 也避免用户为了改一句话而重新滚动整份文档。
 */
@androidx.compose.runtime.Immutable
data class SectionEditorUi(
    val title: String,
    val draft: String,
    val isSaving: Boolean = false,
    /** 打开时该章节起始行；重名标题靠它区分，保存时也按它定位。 */
    val startLine: Int = 0,
)
