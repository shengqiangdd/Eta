package io.github.mangi.eta.agent.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayPreferencePolicyTest {
    @Test
    fun legacySwitchOffKeepsGlowHiddenAfterUpgrade() {
        // 旧版只有一个开关且用户关掉过：升级后不能因为流光默认开启又冒出来。
        assertFalse(
            AgentOverlayPreferencePolicy.glowEnabled(
                glowConfigured = false,
                glowValue = true,
                capsuleConfigured = true,
                capsuleValue = false,
            ),
        )
    }

    @Test
    fun legacySwitchOnKeepsGlowVisibleAfterUpgrade() {
        assertTrue(
            AgentOverlayPreferencePolicy.glowEnabled(
                glowConfigured = false,
                glowValue = false,
                capsuleConfigured = true,
                capsuleValue = true,
            ),
        )
    }

    @Test
    fun freshInstallShowsGlowByDefault() {
        assertTrue(
            AgentOverlayPreferencePolicy.glowEnabled(
                glowConfigured = false,
                glowValue = false,
                capsuleConfigured = false,
                capsuleValue = false,
            ),
        )
    }

    @Test
    fun explicitGlowValueWinsOverLegacyCapsuleValue() {
        // 用户显式关掉流光，即使胶囊仍开着，也必须保持关闭。
        assertFalse(
            AgentOverlayPreferencePolicy.glowEnabled(
                glowConfigured = true,
                glowValue = false,
                capsuleConfigured = true,
                capsuleValue = true,
            ),
        )
        // 用户显式打开流光，即使旧胶囊关着，也尊重新选择。
        assertTrue(
            AgentOverlayPreferencePolicy.glowEnabled(
                glowConfigured = true,
                glowValue = true,
                capsuleConfigured = true,
                capsuleValue = false,
            ),
        )
    }
}
