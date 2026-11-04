package io.github.mangi.eta.ui.components

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.acp.AcpClient

/**
 * 「默认委派给谁」的可选项。
 *
 * **id 必须与 `AcpClient.BUILTIN_AGENTS` 一致**，这里有单测守着（`AcpDelegationAgentsTest`）。
 * 不直接从那个清单生成是因为它带的是可执行文件路径这类启动细节，界面只需要 id 与文案；
 * 但也不能各写各的 —— 加一个代理却忘了加一项，用户在设置页就找不到它。
 */
internal object AcpDelegationAgents {

    data class Entry(
        val id: String,
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int,
    )

    /** 顺序即设置页里的显示顺序；默认「自动」排在最前，另在组件里单独渲染。 */
    val entries: List<Entry> = listOf(
        Entry(
            id = "codex",
            titleRes = R.string.acp_agent_codex_title,
            summaryRes = R.string.acp_agent_codex_summary,
        ),
        Entry(
            id = "claude",
            titleRes = R.string.acp_agent_claude_title,
            summaryRes = R.string.acp_agent_claude_summary,
        ),
    )

    /** 与内置代理清单的一致性：多一个（界面能选但不存在）或少一个（存在但选不到）都是缺陷。 */
    fun mismatchedWithBuiltin(builtinIds: Collection<String>): List<String> {
        val ui = entries.map { it.id }.toSet()
        val builtin = builtinIds.toSet()
        return ((ui - builtin) + (builtin - ui)).sorted()
    }
}
