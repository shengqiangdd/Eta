package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayImePolicyTest {
    @Test fun hidePolicyIsTheOnlyAcceptedPerDisplayIsolation() {
        assertTrue(VirtualDisplayImePolicy.isHidePolicy(VirtualDisplayImePolicy.IME_POLICY_HIDE))
        assertFalse(VirtualDisplayImePolicy.isHidePolicy(0))
        assertFalse(VirtualDisplayImePolicy.isHidePolicy(1))
        assertFalse(VirtualDisplayImePolicy.isHidePolicy(null))
        assertEquals("eta_vd_ime_policy_12", VirtualDisplayImePolicy.imePolicySettingKey(12))
    }

    @Test fun hiddenImeIsNotTreatedAsInput() {
        assertFalse(VirtualDisplayImePolicy.mainImeVisible(""))
        assertFalse(VirtualDisplayImePolicy.mainImeVisible("mInputShown=false\nmImeWindowVis=0x0"))
    }


    @Test fun focusedDisplayAndVirtualInputSessionAreParsed() {
        val input = "  FocusedDisplayId: 61\n  FocusedApplications:"
        assertEquals(61, VirtualDisplayImePolicy.focusedDisplayId(input))
        assertEquals(0, VirtualDisplayImePolicy.focusedDisplayId("FocusedDisplayId: 0"))

        val withSession = """
            ClientState{93a89b4 mUid=10643 mPid=31369 mSelfReportedDisplayId=61}:
              client=com.android.server.inputmethod.IInputMethodClientInvoker@b409b7b
              curSession=SessionState{uid=10643 pid=31369 method=95f47b3}
              sessionRequested=false
              selfReportedDisplayId=61
        """.trimIndent()
        assertTrue(VirtualDisplayImePolicy.virtualDisplayHasInputSession(withSession, 61))
        assertFalse(VirtualDisplayImePolicy.virtualDisplayHasInputSession(withSession, 0))

        val withoutSession = """
            ClientState{93a89b4 mUid=10643 mPid=31369 mSelfReportedDisplayId=61}:
              sessionRequested=false
              selfReportedDisplayId=61
        """.trimIndent()
        assertFalse(VirtualDisplayImePolicy.virtualDisplayHasInputSession(withoutSession, 61))
    }

    @Test fun shownOrVisibleImeIsDetected() {
        assertTrue(VirtualDisplayImePolicy.mainImeVisible("mInputShown=true"))
        assertTrue(VirtualDisplayImePolicy.mainImeVisible("mImeWindowVis=0x1"))
        assertTrue(VirtualDisplayImePolicy.mainImeVisible("mInputShown=false\nmImeWindowVis=0x3"))
    }
}
