package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceBehaviorAndDefaultToCoui() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceUiStyle.COUI, settings.uiStyle)
        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertTrue(settings.predictiveBackEnabled)
        assertEquals(DEFAULT_INTERFACE_SCALE, settings.interfaceScale)
        assertEquals(ChatViewMode.ALL, settings.chatViewMode)
    }

    @Test
    fun chatViewModeFallsBackToAllAndKeepsKnownValues() {
        assertEquals(ChatViewMode.ALL, ChatViewMode.fromPersistedValue(null))
        assertEquals(ChatViewMode.ALL, ChatViewMode.fromPersistedValue("unknown"))
        assertEquals(ChatViewMode.DIALOG, ChatViewMode.fromPersistedValue("dialog"))
        assertEquals(ChatViewMode.TRACE, ChatViewMode.fromPersistedValue("trace"))
    }

    @Test
    fun chatViewModeUiOrderCoversEveryModeExactlyOnce() {
        // 外观页的下拉索引走 uiOrder，不用枚举 ordinal：它必须覆盖全部模式、无重复，
        // 且「全部」排第一（默认值就是它）—— 否则界面会出现重复项、漏项或默认选中错位。
        assertEquals(ChatViewMode.entries.size, ChatViewMode.uiOrder.size)
        assertEquals(ChatViewMode.entries.toSet(), ChatViewMode.uiOrder.toSet())
        assertEquals(ChatViewMode.uiOrder.size, ChatViewMode.uiOrder.toSet().size)
        assertEquals(ChatViewMode.ALL, ChatViewMode.uiOrder.first())
    }

    @Test
    fun legacyDialogOnlyFlagOnlyAppliesWhenTheNewKeyIsAbsent() {
        // 旧版本升上来的用户：新键还没有，布尔为 true 时要保持他原本的选择。
        assertEquals(ChatViewMode.DIALOG, resolveChatViewMode(null, legacyDialogOnly = true))
        assertEquals(ChatViewMode.ALL, resolveChatViewMode(null, legacyDialogOnly = false))
        assertEquals(ChatViewMode.ALL, resolveChatViewMode(null, legacyDialogOnly = null))
        // 新键一旦写过就以它为准：否则用户在新版本里改回「全部」后，旧键会把它顶回去。
        assertEquals(ChatViewMode.ALL, resolveChatViewMode("all", legacyDialogOnly = true))
        assertEquals(ChatViewMode.TRACE, resolveChatViewMode("trace", legacyDialogOnly = true))
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue(null))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("unknown"))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("coui"))
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue("miuix"))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }

    @Test
    fun interfaceScaleIsFiniteAndClamped() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(0.95f, normalizeInterfaceScale(0.95f))
    }
}
