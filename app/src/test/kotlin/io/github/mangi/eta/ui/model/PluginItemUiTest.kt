package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件能力的"可执行通道"计数。
 *
 * 这条规则存在的理由：manifest 里的 `tools` 只是能力声明，没有执行器
 * （真正能跑的是 MCP 服务器与 ACP 代理）。如果把它们算进"这个插件能干活"，
 * 用户装完一个只有 `tools` 的插件，界面会显示"已安装"，而 Agent 实际什么都没多出来。
 */
class PluginItemUiTest {

    private fun plugin(
        toolCount: Int = 0,
        mcp: List<String> = emptyList(),
        acp: List<String> = emptyList(),
        prompt: Boolean = false,
    ) = PluginItemUi(
        id = "demo",
        name = "Demo",
        version = "1.0.0",
        description = "",
        author = "",
        enabled = true,
        toolCount = toolCount,
        mcpServers = mcp,
        acpAgents = acp,
        hasPrompt = prompt,
    )

    @Test
    fun `capability declarations alone are not executable`() {
        assertEquals(0, plugin(toolCount = 5).executableChannelCount)
    }

    @Test
    fun `prompt extension alone is not executable`() {
        assertEquals(0, plugin(prompt = true).executableChannelCount)
    }

    @Test
    fun `mcp and acp channels are counted`() {
        assertEquals(
            3,
            plugin(toolCount = 2, mcp = listOf("a", "b"), acp = listOf("codex")).executableChannelCount,
        )
    }
}
