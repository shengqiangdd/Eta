package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 节点快照与目标屏必须同屏（fix163）。
 *
 * 守的是真机那条：快照取自虚拟屏、`display_id=0`（主屏）时
 * `tap_element` 仍返回 `ok=true`，`after.package` 却是虚拟屏上的应用。
 */
class ObservationScopePolicyTest {
    @Test
    fun `a snapshot from the same virtual display is usable`() {
        assertEquals(
            ObservationScopePolicy.Scope.MATCH,
            ObservationScopePolicy.check(observedDisplayId = 124, requestedDisplayId = 124),
        )
    }

    @Test
    fun `a virtual snapshot cannot drive the main screen`() {
        // 真机实测：这条以前返回 ok=true，动作其实打在虚拟屏上。
        assertEquals(
            ObservationScopePolicy.Scope.OTHER_DISPLAY,
            ObservationScopePolicy.check(observedDisplayId = 124, requestedDisplayId = null),
        )
        // display_id=0 与不传等价，都要认成主屏。
        assertEquals(
            ObservationScopePolicy.Scope.OTHER_DISPLAY,
            ObservationScopePolicy.check(observedDisplayId = 124, requestedDisplayId = 0),
        )
    }

    @Test
    fun `a main screen snapshot cannot drive a virtual display`() {
        // 这个方向更严重：动作会真的打在用户的主屏上。
        assertEquals(
            ObservationScopePolicy.Scope.OTHER_DISPLAY,
            ObservationScopePolicy.check(observedDisplayId = null, requestedDisplayId = 124),
        )
    }

    @Test
    fun `main screen null and zero are the same scope`() {
        assertEquals(
            ObservationScopePolicy.Scope.MATCH,
            ObservationScopePolicy.check(observedDisplayId = 0, requestedDisplayId = null),
        )
        assertEquals(
            ObservationScopePolicy.Scope.MATCH,
            ObservationScopePolicy.check(observedDisplayId = null, requestedDisplayId = null),
        )
    }

    @Test
    fun `different virtual displays are different scopes`() {
        assertEquals(
            ObservationScopePolicy.Scope.OTHER_DISPLAY,
            ObservationScopePolicy.check(observedDisplayId = 124, requestedDisplayId = 125),
        )
    }
}
