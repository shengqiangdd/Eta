package io.github.mangi.eta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置搜索的匹配规则。
 *
 * 这里测的是「用户输一个词，能不能找得到那道设置」——搜索一旦漏掉某个叫法，
 * 用户得到的是一个空列表，比没有搜索更糟。
 */
class SettingsSearchTest {
    @Test
    fun blankQueryShowsEverything() {
        // 没输词时所有组都要显示，否则设置页直接变空。
        assertTrue(settingsSectionMatches("", SETTINGS_AGENT_KEYWORDS))
        assertTrue(settingsSectionMatches("   ", SETTINGS_PERMISSIONS_KEYWORDS))
    }

    @Test
    fun matchesChineseEntryName() {
        // 「委派」是组内条目的名字，不是组标题：只匹配标题的话这里会失败。
        // 这一组从「模型提供商」里拆出来以后，「委派」只在它自己的关键词表里，
        // 拿旧表断言会失败 —— 正好说明拆分的边界在哪。
        assertTrue(settingsSectionMatches("委派", SETTINGS_DELEGATION_KEYWORDS))
        assertTrue(settingsSectionMatches("悬浮窗", SETTINGS_PERMISSIONS_KEYWORDS))
    }

    @Test
    fun splitGroupsDoNotLeakIntoEachOther() {
        // 拆成两组后各自要能单独命中：搜「用量」只该是模型与用量，
        // 搜「重试」只该是对话与委派。两边都命中或都不命中都算拆坏了。
        assertTrue(settingsSectionMatches("用量", SETTINGS_AGENT_KEYWORDS))
        assertFalse(settingsSectionMatches("重试", SETTINGS_AGENT_KEYWORDS))
        assertTrue(settingsSectionMatches("重试", SETTINGS_DELEGATION_KEYWORDS))
    }

    @Test
    fun matchesEnglishIgnoringCase() {
        assertTrue(settingsSectionMatches("PROVIDER", SETTINGS_AGENT_KEYWORDS))
        assertTrue(settingsSectionMatches("Gemini", SETTINGS_GEMINI_KEYWORDS))
    }

    @Test
    fun unrelatedQueryMatchesNothing() {
        assertFalse(settingsSectionMatches("量子纠缠", SETTINGS_AGENT_KEYWORDS))
        assertFalse(settingsSectionMatches("zzzz", SETTINGS_ABOUT_KEYWORDS))
    }

    @Test
    fun everySectionIsReachableByItsOwnTitleWord() {
        // 每组至少能被一个常见词命中；漏一组就是「搜不到」的盲区。
        val sections = listOf(
            SETTINGS_AGENT_KEYWORDS to "模型",
            SETTINGS_DELEGATION_KEYWORDS to "委派",
            SETTINGS_CHAT_UI_KEYWORDS to "聊天",
            SETTINGS_CONTEXT_KEYWORDS to "上下文",
            SETTINGS_TOOLS_KEYWORDS to "工具",
            SETTINGS_ENHANCE_KEYWORDS to "能力",
            SETTINGS_ASSISTANT_KEYWORDS to "助手",
            SETTINGS_OEM_KEYWORDS to "小布",
            SETTINGS_GEMINI_KEYWORDS to "gemini",
            SETTINGS_CIRCLE_KEYWORDS to "圈选",
            SETTINGS_GENERAL_KEYWORDS to "通用",
            SETTINGS_PERMISSIONS_KEYWORDS to "权限",
            SETTINGS_ABOUT_KEYWORDS to "关于",
        )
        sections.forEach { (keywords, probe) ->
            assertTrue("关键词表缺少「$probe」", settingsSectionMatches(probe, keywords))
        }
    }

    @Test
    fun searchForcesSectionsOpen() {
        // 命中却只显示一行标题，用户还得再点开才能看到结果 —— 搜索等于白搜。
        assertTrue(settingsSectionExpanded("委派", userExpanded = false))
        assertTrue(settingsSectionExpanded("委派", userExpanded = true))
    }

    @Test
    fun withoutQueryRespectsUserCollapse() {
        // 没在搜索时必须尊重用户手动收起的状态，否则折叠功能就失效了。
        assertFalse(settingsSectionExpanded("", userExpanded = false))
        assertFalse(settingsSectionExpanded("   ", userExpanded = false))
        assertTrue(settingsSectionExpanded("", userExpanded = true))
    }
}
