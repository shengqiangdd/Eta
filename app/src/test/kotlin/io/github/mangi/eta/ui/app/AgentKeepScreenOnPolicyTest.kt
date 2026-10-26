package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKeepScreenOnPolicyTest {
    @Test fun disabledSwitchNeverKeepsScreenOn() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = false, streaming = false))
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = false, streaming = true))
    }

    @Test fun enabledOnlyDuringStreaming() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = false))
        assertTrue(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true))
    }

    @Test fun pausedRunDoesNotKeepScreenOn() {
        assertFalse(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true, paused = true))
        assertTrue(AgentKeepScreenOnPolicy.shouldKeep(enabled = true, streaming = true, paused = false))
    }
}
