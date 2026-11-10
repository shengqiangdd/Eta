package io.github.mangi.eta.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个键集合的约束守卫。
 *
 * 背景：`reconcileAgentPreferences` 会遍历 [Prefs.Keys.LOCAL_AGENT_KEYS] 做布尔迁移。
 * 它原先用 `BOOLEAN_DEFAULTS.getValue(key)`——没有默认值就抛 `NoSuchElementException`，
 * 而异常发生在**设置页的组合阶段**（`DisposableEffect`），代价是打开设置即闪退。
 * `agent_disabled_tools`（字符串）误入布尔集合时正是这样炸的。
 *
 * 现在迁移本身已经容错（下标取值 + 跳过 + 日志），设置页不会再因此起不来。
 * 但这张表仍然应该干净：下面的测试守住这一点，避免「靠跳过兜住」变成常态，
 * 把真正的类型错误一直藏下去。
 */
class PrefsKeySetsTest {

    @Test
    fun `布尔键集合里的每个键都必须有布尔默认值`() {
        val missing = Prefs.Keys.LOCAL_AGENT_KEYS.filterNot { it in Prefs.Keys.BOOLEAN_DEFAULTS }
        assertTrue(
            "这些键没有布尔默认值，迁移时只能被跳过（说明它们本不该在这张表里）：$missing",
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
