package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机缺陷：`virtual_display_create` / `virtual_display_launch` 已经自动观察了首屏，
 * 紧接着 `tap_element` 却报 `NO_OBSERVATION`，还要再 `observe_screen` 一次。
 *
 * 根因：这两条工具的参数里还没有 `display_id`，scope 是 `main`；而 `tap_element`
 * 带 `display_id=<新屏>`，scope 是 `display:N`。两把锁代号本来就不相等，
 * 旧实现只比代号就清空快照。
 */
class ObservationGenerationPolicyTest {
    @Test
    fun `same display same generation keeps the snapshot`() {
        assertFalse(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 7L,
                currentGeneration = 7L,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `cross display generation mismatch is not a stale snapshot`() {
        // 建屏时拿的是 main 的代号，首屏却属于 display:124。
        // 这类快照发布时显式不绑代号（见 publishObservation），所以不应被代号作废。
        assertFalse(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = null,
                currentGeneration = 9L,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `a snapshot recorded with the main lock cannot outlive a display switch`() {
        // 万一有人真把 main 的代号记在虚拟屏快照上：同一块屏代号不同 → 过期。
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 9L,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `unbound snapshot is never invalidated by generation`() {
        // 跨屏发布的首屏：显式不绑任何锁代号。
        assertFalse(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = null,
                currentGeneration = 9L,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `same main screen with advanced generation is stale`() {
        // 用户暂停后恢复：代号推进，主屏快照真的过期了。
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 4L,
                snapshotDisplayId = null,
                targetDisplayId = null,
            )
        )
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 4L,
                snapshotDisplayId = 0,
                targetDisplayId = 0,
            )
        )
    }

    @Test
    fun `same virtual display with advanced generation is stale`() {
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 4L,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `snapshot from another virtual display is not usable`() {
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 4L,
                snapshotDisplayId = 124,
                targetDisplayId = 125,
            )
        )
    }

    @Test
    fun `main snapshot never drives a virtual display`() {
        assertTrue(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = 4L,
                snapshotDisplayId = null,
                targetDisplayId = 124,
            )
        )
    }

    @Test
    fun `unknown current generation never invalidates`() {
        assertFalse(
            ObservationGenerationPolicy.invalidate(
                snapshotGeneration = 3L,
                currentGeneration = null,
                snapshotDisplayId = 124,
                targetDisplayId = 124,
            )
        )
    }
}
