package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class)
class AgentExecutionServiceTest {
    @Test
    @Config(sdk = [33, 36])
    fun agentRunPromotionEndsWhileTerminalSessionRemains() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:terminal-session"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, activeRunCount = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(Build.VERSION.SDK_INT >= 36, notification.extras.getBoolean("android.requestPromotedOngoing"))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(Notification.ProgressStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
                assertEquals(context.getString(R.string.overlay_reasoning), notification.extras.getString("android.shortCriticalText"))
            }

            AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            assertFalse(remaining.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(remaining.extras.containsKey("android.shortCriticalText"))
            assertTrue(remaining.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun executionNotificationCarriesSupplementAndPauseActions() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:notification-actions"
        // 暂停/补充按钮只在真实 Agent run 租约存在时出现；终端会话租约只有「停止」。
        assertTrue(AgentExecutionService.acquire(context, leaseId, agentRunId = "test-run") {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, activeRunCount = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(3, notification.actions.size)

            val supplement = notification.actions[0]
            assertEquals(context.getString(R.string.execution_supplement), supplement.title)
            val remoteInputs = requireNotNull(supplement.remoteInputs)
            assertEquals(1, remoteInputs.size)
            assertEquals(AgentExecutionService.KEY_SUPPLEMENT, remoteInputs.single().resultKey)

            assertEquals(context.getString(R.string.execution_pause), notification.actions[1].title)
            assertEquals(context.getString(R.string.execution_stop), notification.actions[2].title)

            // 暂停后标签要变成「继续」，否则用户会以为再点一次仍是暂停。
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Paused, activeRunCount = 1)
            shadowOf(Looper.getMainLooper()).idle()
            // refreshNotification() 走 NotificationManager.notify，不会更新 ShadowService.lastForegroundNotification。
            val paused = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single().notification
            assertEquals(context.getString(R.string.execution_resume), paused.actions[1].title)
        } finally {
            AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun terminalOnlyNotificationExposesStopWithoutAgentActions() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:terminal-only"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            // 终端会话没有可暂停的推理：多出来的按钮只会“点了没反应”。
            assertEquals(1, notification.actions.size)
            assertEquals(context.getString(R.string.execution_stop), notification.actions.single().title)
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun foregroundExecutionUsesOnlyServiceTypesSupportedByThePlatform() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                service.foregroundServiceType,
            )
        } finally {
            controller.destroy()
        }
    }
}
