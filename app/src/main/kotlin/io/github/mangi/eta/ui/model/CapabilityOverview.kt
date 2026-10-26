package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.navigation.AppRoute

/**
 * 能力总览里的一项入口。
 *
 * 每个条目都直接持有目标 [AppRoute]，因此不存在「id 与路由对不上」的中间映射；
 * 新增页面时只在这里加一条即可。
 */
@Immutable
internal data class CapabilityEntryUi(
    val id: String,
    val title: String,
    val summary: String,
    val route: AppRoute,
    /** 右侧状态，如「12 项可用」「1 项待处理」；null 表示不显示。 */
    val status: String? = null,
    /** 状态需要用户处理（缺权限等）时用强调色，避免「一切正常」与「待处理」同色。 */
    val statusNeedsAttention: Boolean = false,
    /** 标题与摘要之外的搜索别名，便于用英文或缩写命中中文条目。 */
    val keywords: List<String> = emptyList(),
)

@Immutable
internal data class CapabilityGroupUi(
    val id: String,
    val title: String,
    val entries: List<CapabilityEntryUi>,
)

/**
 * 能力总览的分组过滤。
 *
 * - 空白查询返回原列表（保持引用，避免无谓重组）；
 * - 组名命中时保留整组：搜「系统」应看到该组全部能力，而不是只剩组名匹配的那一条；
 * - 否则只保留命中的条目，整组都不命中则丢弃。
 */
internal fun filterCapabilityGroups(
    groups: List<CapabilityGroupUi>,
    query: String,
): List<CapabilityGroupUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return groups
    return groups.mapNotNull { group ->
        if (group.title.contains(normalized, ignoreCase = true)) {
            group
        } else {
            val entries = group.entries.filter { it.matchesCapabilityQuery(normalized) }
            if (entries.isEmpty()) null else group.copy(entries = entries)
        }
    }
}

/** 标题、摘要或搜索别名命中即算命中；大小写不敏感，中文按字面匹配。 */
private fun CapabilityEntryUi.matchesCapabilityQuery(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        summary.contains(query, ignoreCase = true) ||
        keywords.any { it.contains(query, ignoreCase = true) }
