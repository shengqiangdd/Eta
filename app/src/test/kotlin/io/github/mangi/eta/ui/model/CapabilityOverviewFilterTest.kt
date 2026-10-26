package io.github.mangi.eta.ui.model

import io.github.mangi.eta.ui.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CapabilityOverviewFilterTest {

    private fun entry(
        id: String,
        title: String,
        summary: String = "",
        keywords: List<String> = emptyList(),
    ) = CapabilityEntryUi(
        id = id,
        title = title,
        summary = summary,
        route = AppRoute.Memory,
        keywords = keywords,
    )

    private val conversation = CapabilityGroupUi(
        id = "conversation",
        title = "对话与角色",
        entries = listOf(
            entry("characters", "角色库", "角色、人格与剧情记忆", listOf("character")),
            entry("memory", "记忆", "跨会话保存的偏好与事实", listOf("memory")),
            entry("backup", "数据备份", "导出或恢复全部对话与设置", listOf("backup")),
        ),
    )

    private val device = CapabilityGroupUi(
        id = "device",
        title = "设备与系统",
        entries = listOf(
            entry("permissions", "权限", "运行时授权与系统访问状态", listOf("permission")),
            entry("terminal", "终端", "常驻 Shell 会话", listOf("shell")),
        ),
    )

    private val groups = listOf(conversation, device)

    @Test
    fun blankQueryReturnsOriginalList() {
        assertSame(groups, filterCapabilityGroups(groups, ""))
        assertSame(groups, filterCapabilityGroups(groups, "   "))
        assertSame(groups, filterCapabilityGroups(groups, "\n\t "))
    }

    @Test
    fun titleAndSummaryBothMatch() {
        // 「记忆」既命中「记忆」标题，也命中角色摘要里的「剧情记忆」——摘要参与搜索是刻意的，
        // 否则用户记得住描述却搜不到条目。
        val result = filterCapabilityGroups(groups, "记忆")
        assertEquals(listOf("conversation"), result.map { it.id })
        assertEquals(listOf("characters", "memory"), result[0].entries.map { it.id })
    }

    @Test
    fun summaryMatchKeepsEntry() {
        val result = filterCapabilityGroups(groups, "授权")
        assertEquals(1, result.size)
        assertEquals(listOf("permissions"), result[0].entries.map { it.id })
    }

    @Test
    fun keywordMatchIsCaseInsensitive() {
        val result = filterCapabilityGroups(groups, "SHELL")
        assertEquals(listOf("device"), result.map { it.id })
        assertEquals(listOf("terminal"), result[0].entries.map { it.id })
    }

    @Test
    fun groupTitleMatchKeepsWholeGroup() {
        val result = filterCapabilityGroups(groups, "系统")
        assertEquals(listOf("device"), result.map { it.id })
        assertEquals(2, result[0].entries.size)
    }

    @Test
    fun multiGroupMatchesKeepOriginalOrder() {
        // 「e」在两组里都命中英文别名，验证同时命中时分组顺序与组内顺序都保持不变。
        val result = filterCapabilityGroups(groups, "e")
        assertEquals(listOf("conversation", "device"), result.map { it.id })
        assertEquals(listOf("characters", "memory"), result[0].entries.map { it.id })
        assertEquals(listOf("permissions", "terminal"), result[1].entries.map { it.id })
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertEquals(emptyList<CapabilityGroupUi>(), filterCapabilityGroups(groups, "不存在的关键词"))
    }

    @Test
    fun queryIsTrimmed() {
        val result = filterCapabilityGroups(groups, "  权限  ")
        assertEquals(listOf("permissions"), result.single().entries.map { it.id })
    }

    @Test
    fun chineseSubstringMatches() {
        // 中文没有词边界，按字面包含即可：输入「备份」应命中「数据备份」。
        val result = filterCapabilityGroups(groups, "备份")
        assertEquals(listOf("backup"), result.single().entries.map { it.id })
    }
}
