package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保屏锁决策。
 *
 * 真机踩过两个方向相反的缺陷，这里把两边都钉住：
 *
 * **一、任务跑着却熄屏**
 *
 * 旧实现把锁与续期协程放在 `AgentAppState`（Activity 作用域的 ViewModel）里，
 * 用 `conversationsById[*].isStreaming` 这个**本地镜像**判定。运行态不入库，
 * Activity 被系统回收后重建时 `isStreaming` 恢复为 false，于是算出「没有任务在跑」，
 * 主动释放锁；前台服务其实还活着、任务仍在推进。
 *
 * **二、会话结束后整夜亮屏**
 *
 * 反过来只按「服务在前台 + 开关」持锁也是错的：服务可能因终端命令等其它租约继续存活，
 * 或停在租约清空到 `stopSelf()` 之间。用户反馈「一晚上屏幕开着，电池耗光」。
 * 被暂停的 run 不会推进，同样不该亮屏。
 *
 * 结论：必须由前台服务用**自己登记的 Agent 租约**判定，并叠加用户开关；
 * 任何 UI 运行态镜像都不得参与。
 */
class AgentKeepScreenLockPolicyTest {
    @Test
    fun holdsWhileRunIsActiveAndUserEnabled() {
        assertTrue(
            AgentKeepScreenLockPolicy.shouldHold(
                foregroundActive = true,
                hasRunningTask = true,
                paused = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun userDisabledSwitchNeverHoldsLock() {
        // 开关关掉时必须立刻释放：否则任务在后台跑几十分钟会一直亮屏耗电。
        assertFalse(
            AgentKeepScreenLockPolicy.shouldHold(
                foregroundActive = true,
                hasRunningTask = true,
                paused = false,
                enabled = false,
            ),
        )
    }

    @Test
    fun noForegroundServiceNeverHoldsLock() {
        assertFalse(
            AgentKeepScreenLockPolicy.shouldHold(
                foregroundActive = false,
                hasRunningTask = true,
                paused = false,
                enabled = true,
            ),
        )
    }

    /**
     * 会话结束后必须释放屏幕锁。
     *
     * 这是用户反馈的「开一晚上把电池耗光」：服务可能因其它租约（终端后台命令）继续存活，
     * 此时没有任何 Agent 任务在推进，亮屏纯属浪费。
     */
    @Test
    fun finishedConversationReleasesLockEvenIfServiceStillAlive() {
        assertFalse(
            AgentKeepScreenLockPolicy.shouldHold(
                foregroundActive = true,
                hasRunningTask = false,
                paused = false,
                enabled = true,
            ),
        )
    }

    /** 暂停的 run 不会推进，亮屏没有意义。 */
    @Test
    fun pausedRunReleasesLock() {
        assertFalse(
            AgentKeepScreenLockPolicy.shouldHold(
                foregroundActive = true,
                hasRunningTask = true,
                paused = true,
                enabled = true,
            ),
        )
    }

    /**
     * 决策入参被钉死成「服务状态 + 活跃任务 + 暂停 + 用户开关」四个 boolean。
     *
     * 这是根因护栏：只要决策依赖 `isStreaming` 这类 UI 镜像，Activity 一被回收，
     * 镜像失真就会把锁放掉；而漏掉 `hasRunningTask` 则会退化成整夜亮屏。
     */
    @Test
    fun decisionTakesOnlyAuthoritativeServiceStateAndPreference() {
        val parameterTypes = AgentKeepScreenLockPolicy::class.java.declaredMethods
            .first { it.name == "shouldHold" }
            .parameterTypes
            .map { it.name }
        assertTrue(
            "保屏决策只能依赖服务状态与用户开关，不得引入 UI 运行态镜像：$parameterTypes",
            parameterTypes == listOf("boolean", "boolean", "boolean", "boolean"),
        )
    }
}
