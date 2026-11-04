package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.HOTWORD_SELF_HEAL to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.LOCKSCREEN_VOICE_COMMAND to false,
                Prefs.Keys.SCREEN_ON_VOICE_COMMAND to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to true,
                Prefs.Keys.AGENT_KEEP_SCREEN_ON to false,
                Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED to true,
                Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED to true,
                Prefs.Keys.AGENT_PLAN_MODE to false,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED to true,
                // 对话状态卡片默认收纳（气泡只留一行摘要）。
                Prefs.Keys.AGENT_CHAT_CARDS_AUTO_COLLAPSE to true,
                // 后台任务续跑：默认「点一下才发」，自动续跑要用户自己打开。
                Prefs.Keys.AGENT_BACKGROUND_RESUME_BUTTON to true,
                Prefs.Keys.AGENT_BACKGROUND_RESUME_AUTO to false,

                Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                Prefs.Keys.AGENT_KEEP_SCREEN_ON,
                Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED,
                Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED,
                Prefs.Keys.AGENT_PLAN_MODE,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED,
                Prefs.Keys.AGENT_CHAT_CARDS_AUTO_COLLAPSE,
                Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }

    /**
     * 结构不变量：凡是「本地为事实源」的**布尔**键，必须在 `BOOLEAN_DEFAULTS` 里有默认值。
     *
     * 这条守的是一次真实闪退（2026-11-04，由我引入）：给 `LOCAL_AGENT_KEYS` 加了两个
     * **字符串**键，而 `reconcileAgentPreferences` 会对集合里每个键做
     * `BOOLEAN_DEFAULTS.getValue(key)` → `NoSuchElementException: Key ... is missing
     * in the map`，一进设置页就崩。
     *
     * 为什么不能只靠上面那条「精确集合」测试：它只能发现「集合内容变了」，
     * 发现不了「这个键放进来之后类型对不对」。加字符串键时它同样会红，
     * 但红在「集合不匹配」，很容易被顺手把这个键补进期望集合里改绿 ——
     * 然后闪退照旧。这条不变量直接卡住「集合里的键必须都能取到布尔默认值」。
     */
    @Test
    fun everyLocalAgentBooleanKeyHasADefault() {
        val missing = Prefs.Keys.LOCAL_AGENT_KEYS.filterNot { key ->
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(key)
        }
        assertEquals(
            "这些键在 LOCAL_AGENT_KEYS 里却没有布尔默认值，读默认值时必然抛异常：" +
                "$missing（字符串键请放进 LOCAL_AGENT_STRING_KEYS）",
            emptyList<String>(),
            missing,
        )
    }

    /** 两个集合不能重叠：同一个键既算布尔又算字符串，说明分类搞错了。 */
    @Test
    fun stringKeysAndBooleanKeysAreDisjoint() {
        val overlap = Prefs.Keys.LOCAL_AGENT_KEYS intersect Prefs.Keys.LOCAL_AGENT_STRING_KEYS
        assertEquals("同一个键不能同时是布尔键与字符串键：$overlap", emptySet<String>(), overlap)
    }
}
