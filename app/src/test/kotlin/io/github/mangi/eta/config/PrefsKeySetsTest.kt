package io.github.mangi.eta.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个键集合的约束守卫。
 *
 * 背景：`reconcileAgentPreferences` 对 [Prefs.Keys.LOCAL_AGENT_KEYS] 里的每个键调用
 * `BOOLEAN_DEFAULTS.getValue(key)` —— 是 `getValue` 而不是 `get`，没有默认值就抛
 * `NoSuchElementException`。这个异常发生在**设置页的组合阶段**（`DisposableEffect`），
 * 所以代价是打开设置即闪退，而不是某个功能不可用。
 *
 * 反过来的错误同样存在：字符串键（如 `agent_disabled_tools`）被塞进布尔集合，会因为
 * `getBoolean` 的类型不匹配而静默读成默认值，或者更糟——直接崩。
 *
 * 这两条约束靠人记住必然复发，用测试钉住。
 */
class PrefsKeySetsTest {

    @Test
    fun `布尔键集合里的每个键都必须有布尔默认值`() {
        val missing = Prefs.Keys.LOCAL_AGENT_KEYS.filterNot { it in Prefs.Keys.BOOLEAN_DEFAULTS }
        assertTrue(
            "这些键会让 reconcileAgentPreferences 抛 NoSuchElementException（设置页闪退）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `字符串键不得混入布尔键集合`() {
        val leaked = Prefs.Keys.LOCAL_AGENT_STRING_KEYS.intersect(Prefs.Keys.LOCAL_AGENT_KEYS)
        assertTrue("字符串键混入布尔集合会被 getBoolean 误读：$leaked", leaked.isEmpty())
    }

    @Test
    fun `逐项禁用工具是字符串键`() {
        // 值是逗号分隔的工具名，不能当布尔处理。
        assertTrue(Prefs.Keys.AGENT_DISABLED_TOOLS in Prefs.Keys.LOCAL_AGENT_STRING_KEYS)
        assertTrue(Prefs.Keys.AGENT_DISABLED_TOOLS !in Prefs.Keys.LOCAL_AGENT_KEYS)
    }
}
