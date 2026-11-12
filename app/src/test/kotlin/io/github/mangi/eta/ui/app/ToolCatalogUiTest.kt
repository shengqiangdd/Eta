package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import io.github.mangi.eta.ui.screens.mcp.collapseDescriptionPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全部是纯 JVM 断言（`R.string.*` 是编译期常量），所以不需要 Robolectric。
 *
 * 需要用 SharedPreferences 的 Prefs 开关用例放在 [ToolCatalogPrefsTest]。
 * 两者必须分开：app/build.gradle.kts 按“文件里是否出现该 runner 的名字”
 * 整类排除跑不了的用例，并把整个类的纯 JVM 断言一起牺牲掉。
 */
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState { id -> "res:$id" }.groups
            .flatMap { it.tools }.map { it.id }
        // 一次报全：逐个 assert 只会在第一个失败处停下，修一个冒一个。
        val offenders = (runtimeNames + cardIds).distinct()
            .filter { iconForTool(it) == Icons.Rounded.Build }
        assertEquals("这些工具落到了默认 Build 图标：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, iconForTool("网页搜索"))
        assertEquals(iconForTool("网页搜索"), iconForTool("web_search"))
        assertEquals(Icons.Rounded.Language, iconForTool("browser_use"))
        assertEquals(Icons.AutoMirrored.Rounded.Article, iconForTool("fetch_url"))
        assertEquals(Icons.Rounded.Extension, iconForTool("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }

    @Test
    fun publicWebCardsHaveNamesAndStayInTheWebGroup() {
        val web = buildToolsState { id -> "res:$id" }.groups.first { it.id == "web" }
        assertTrue(web.tools.map { it.id }.containsAll(listOf("web_search", "fetch_url", "browser_use")))
        assertEquals(R.string.tool_web_search, toolDisplayNameResource("web_search"))
        assertEquals(R.string.tool_fetch_url, toolDisplayNameResource("fetch_url"))
    }

    @Test
    fun terminalToolsExposeSeparateLifecyclesInTheSameGroup() {
        val terminal = buildToolsState { id -> "res:$id" }.groups.first { it.id == "terminal" }
        assertTrue(terminal.tools.map { it.id }.containsAll(listOf("terminal", "terminal_job", "terminal_daemon")))
        assertEquals(R.string.tool_terminal_job, toolDisplayNameResource("terminal_job"))
        assertEquals(R.string.tool_terminal_daemon, toolDisplayNameResource("terminal_daemon"))
    }

    @Test
    fun personalQueriesAndNativeOperationsHaveSeparateGroups() {
        val group = buildToolsState { id -> "res:$id" }.groups.first { it.id == "personal_data" }
        assertTrue(group.tools.any { it.id == "search_bills" })
        assertTrue(group.tools.none { it.id == "personal_context" || it.id == "create_note" })
        val operations = buildToolsState { id -> "res:$id" }.groups.first { it.id == "device_direct" }
        assertTrue(operations.tools.any { it.id == "set_flashlight" })
        assertTrue(operations.tools.any { it.id == "create_calendar_event" })
        assertEquals(RootRequirement.REQUIRED, toolCardRequirement("search_bills").rootRequirement)
        assertTrue(toolCardRequirement("search_bills").colorOs)
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState { id -> "res:$id" }.groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }

    @Test
    fun mcpDescriptionPreviewCollapsesHeadingsAndBulletLines() {
        val raw = """
            # Search Tool
            - First capability
            * Second capability
        """.trimIndent()
        assertEquals(
            "Search Tool · First capability · Second capability",
            collapseDescriptionPreview(raw),
        )
    }
}
