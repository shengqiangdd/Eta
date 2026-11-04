package io.github.mangi.eta.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「界面写本地、代码读 remote」的守卫。
 *
 * 设置页有两条写入路径：`agentPrefs`（本地，[Prefs.localAgentPreferences]）与 `prefs`
 * （RemotePreferences，hook 消费）。读取侧 [Prefs.isEnabled] 只对 `LOCAL_AGENT_KEYS` 里的键
 * 读本地，其余一律读 remote —— 所以一旦某个开关「界面写本地、代码用 isEnabled 读」却漏登记，
 * 开关就永远读默认值：界面点了没反应，日志里也看不出错（这个坑真实发生过）。
 *
 * 这里直接扫源码做交叉断言，不依赖任何提醒函数或注释约定：新增开关时只要漏登记就会红。
 * 纯文本解析是整个测试的可信前提，所以每条不变量都先自检「解析到了东西」，避免正则失效后
 * 变成假绿。
 */
class PrefsDualSourceGuardTest {

    private val mainKotlin: File = findMainKotlin()

    private val settingsFile: File = File(mainKotlin, "io/github/mangi/eta/ui/SettingsScreen.kt")

    private val prefsFile: File = File(mainKotlin, "io/github/mangi/eta/config/Prefs.kt")

    @Test
    fun locallyWrittenSwitchesAreRegisteredAsLocalKeys() {
        val entries = switchPrefEntries()
        val written = entries.filter { it.first == "agentPrefs" }.map { it.second }
        assertTrue("没有解析到任何 agentPrefs 开关，正则可能失效", written.isNotEmpty())
        val local = localAgentKeys()
        val missing = written.filter { constantValue(it) !in local }
        assertTrue(
            "这些开关写在本地 prefs 里，却没进 LOCAL_AGENT_KEYS，Prefs.isEnabled 会去读 remote：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun localSwitchReadersOnlyUseRegisteredLocalKeys() {
        val read = localSwitchKeys()
        assertTrue("没有解析到 localSwitch 调用，正则可能失效", read.isNotEmpty())
        val local = localAgentKeys()
        val missing = read.filter { constantValue(it) !in local }
        assertTrue(
            "localSwitch 读的是本地，但这些键不在 LOCAL_AGENT_KEYS 里，另一处用 isEnabled 时就会读 remote：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun aKeyIsNeverWrittenThroughBothSources() {
        val entries = switchPrefEntries()
        val local = entries.filter { it.first == "agentPrefs" }.map { it.second }.toSet()
        val remote = entries.filter { it.first != "agentPrefs" }.map { it.second }.toSet()
        assertTrue("没有解析到 remote 写入的开关，正则可能失效", remote.isNotEmpty())
        val both = local intersect remote
        assertTrue("同一个开关不能既写本地又写 remote，否则两边互相覆盖：$both", both.isEmpty())
    }

    // ---- 源码解析 ----

    private fun findMainKotlin(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val projectLayout = File(current, "app/src/main/kotlin")
            if (projectLayout.isDirectory) return projectLayout
            val moduleLayout = File(current, "src/main/kotlin")
            if (moduleLayout.isDirectory) return moduleLayout
            current = current.parentFile
        }
        error("找不到 src/main/kotlin，测试工作目录是 ${System.getProperty("user.dir")}")
    }

    /** 设置页每个 [SwitchPref] 的 (prefs 变量名, 键常量名)。 */
    private fun switchPrefEntries(): List<Pair<String, String>> {
        assertTrue("设置页不存在：$settingsFile", settingsFile.isFile)
        return Regex("""SwitchPref\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(settingsFile.readText())
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val prefsVar = Regex("""prefs\s*=\s*(\w+)""").find(block)?.groupValues?.get(1)
                val key = Regex("""key\s*=\s*Prefs\.Keys\.(\w+)""").find(block)?.groupValues?.get(1)
                if (prefsVar != null && key != null) prefsVar to key else null
            }
            .toList()
    }

    /** 所有 `localSwitch(context, Prefs.Keys.XXX)` 用到的键常量名。 */
    private fun localSwitchKeys(): Set<String> {
        val pattern = Regex("""localSwitch\(\s*context\s*,\s*Prefs\.Keys\.(\w+)""")
        return mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                pattern.findAll(file.readText()).map { it.groupValues[1] }.asSequence()
            }
            .toSet()
    }

    /** `const val NAME = "value"` 的映射。 */
    private fun constantValues(): Map<String, String> {
        assertTrue("Prebs 定义文件不存在：$prefsFile", prefsFile.isFile)
        return Regex("""const val (\w+) = "([^"]+)"""")
            .findAll(prefsFile.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun constantValue(name: String): String? = constantValues()[name]

    /** `LOCAL_AGENT_KEYS` 实际包含的字符串值（按常量映射）。 */
    private fun localAgentKeys(): Set<String> {
        val text = prefsFile.readText()
        val block = Regex(
            """val LOCAL_AGENT_KEYS: Set<String> = setOf\((.*?)\n\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.groupValues?.get(1) ?: error("找不到 LOCAL_AGENT_KEYS 定义，解析或代码结构变了")
        val values = constantValues()
        val resolved = Regex("""\b([A-Z][A-Z0-9_]+)\b""")
            .findAll(block)
            .mapNotNull { values[it.groupValues[1]] }
            .toSet()
        assertTrue("LOCAL_AGENT_KEYS 解析结果为空，常量映射可能失效", resolved.isNotEmpty())
        return resolved
    }
}