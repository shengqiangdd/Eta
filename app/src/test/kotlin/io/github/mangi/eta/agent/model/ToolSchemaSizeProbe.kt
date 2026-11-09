package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.junit.Test

/**
 * 用与真实运行相同的开关量 tools schema 体积。
 *
 * 与 trace 对齐：真机 run_started 记录 tools=147，构建时只带
 * terminal/browser 开关只能得到 81 —— 差额来自 skillGitHubDiscovery/Install、
 * memoryTools、deviceSensitive* 以及 additionalTools（MCP/插件）。
 * 这里把前四项都打开，逼近真实值。
 */
class ToolSchemaSizeProbe {

    @Test
    fun measureToolSchemaFootprint() {
        val caps = AgentToolCapabilities(rootAvailable = true)
        val variants = listOf(
            Triple("真实:全开+敏感+skill github", true, true),
            Triple("真实:关browser", true, false),
            Triple("真实:关terminal", false, true),
        )
        for ((label, term, browser) in variants) {
            val arr = AgentToolCatalog.build(
                terminalTools = term,
                browserTools = browser,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = true,
                memoryWritable = true,
                capabilities = caps,
                localWebSearch = true,
                mode = AgentMode.DEFAULT,
            )
            val json = arr.toString()
            println("=== " + label + " ===")
            println("  工具数: " + arr.length())
            println("  JSON 字符数: " + json.length)
            println("  估算 token(字符/3.2): " + (json.length / 3.2).toInt())

            var total = 0
            val rows = ArrayList<Pair<String, Int>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getJSONObject("function").getString("name")
                val len = obj.toString().length
                rows.add(Pair(name, len))
                total += len
            }
            println("  Top 15:")
            rows.sortedByDescending { it.second }.take(15).forEach { p ->
                val pct = p.second * 100.0 / total
                println("    " + p.first + ": " + p.second + " 字符 (" + "%.1f".format(pct) + "%)")
            }
            // 按前缀分组，看哪些工具族最肥
            val groups = HashMap<String, Int>()
            for (p in rows) {
                val key = when {
                    p.first.startsWith("virtual_display") -> "virtual_display"
                    p.first.startsWith("observe") || p.first.contains("screen") -> "screen观察"
                    p.first.startsWith("tap") || p.first.startsWith("swipe") ||
                        p.first.startsWith("scroll") || p.first.startsWith("long_press") -> "手势"
                    p.first.startsWith("device") || p.first.startsWith("set_") ||
                        p.first.startsWith("get_") -> "设备状态"
                    p.first.startsWith("memory") -> "记忆"
                    p.first.startsWith("skill") -> "技能"
                    p.first.startsWith("acp") -> "ACP"
                    p.first.startsWith("personal") || p.first.startsWith("search") -> "个人数据检索"
                    p.first.startsWith("terminal") || p.first.startsWith("read_file") ||
                        p.first.startsWith("write_file") || p.first.startsWith("edit") -> "终端/文件"
                    else -> "其他"
                }
                groups[key] = (groups[key] ?: 0) + p.second
            }
            println("  按族:")
            groups.entries.sortedByDescending { it.value }.forEach { e ->
                println("    " + e.key + ": " + e.value + " 字符 (" +
                    "%.1f".format(e.value * 100.0 / total) + "%)")
            }
        }
    }
}
