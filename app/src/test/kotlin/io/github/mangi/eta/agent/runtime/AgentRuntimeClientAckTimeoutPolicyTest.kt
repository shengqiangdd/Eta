package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeClientAckTimeoutPolicyTest {
    @Test
    fun continuesOnlyWhenTheActiveRunIsTheRequestItself() {
        assertTrue(shouldContinueAfterAckTimeout("run-1", "run-1"))
    }

    @Test
    fun givesUpWhenTheServiceReportsAnotherRun() {
        assertFalse(shouldContinueAfterAckTimeout("run-2", "run-1"))
    }

    @Test
    fun givesUpWhenTheServiceCouldNotBeReached() {
        assertFalse(shouldContinueAfterAckTimeout(null, "run-1"))
        assertFalse(shouldContinueAfterAckTimeout("", "run-1"))
        assertFalse(shouldContinueAfterAckTimeout("   ", "run-1"))
    }
}
