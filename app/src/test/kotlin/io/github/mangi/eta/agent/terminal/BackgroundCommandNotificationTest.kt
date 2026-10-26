package io.github.mangi.eta.agent.terminal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import io.github.mangi.eta.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundCommandNotificationTest {
    companion object {
        /**
         * Robolectric 的 native runtime 只有 linux-x86_64 / macOS 等平台，Linux aarch64 上
         * `RobolectricTestRunner.beforeTest` 里就会抛 AssertionError —— 比 @Test 方法还早，
         * 写在方法里的 try/catch 根本接不住，必须在类级别拦截。
         */
        @BeforeClass @JvmStatic
        fun assumeRobolectricPlatform() {
            val os = (System.getProperty("os.name") ?: "").lowercase()
            val arch = System.getProperty("os.arch") ?: ""
            Assume.assumeTrue(
                "Robolectric 原生运行时不支持 $os/$arch",
                !(os.contains("linux") && arch == "aarch64"),
            )
        }
    }

    @Test fun notificationShowsOutcomeAndOpensTerminalWithoutCommandOrLogContents() {
        val context = RuntimeEnvironment.getApplication()
        val task = DetachedTask("notification-job", 42, "token", "echo secret-command", "/private", "user",
            TerminalEnvironment.ANDROID, "/private/log", 1, commandTask = true, description = "生成报告",
            completedAt = 2, exitCode = 0)
        BackgroundCommandNotification.show(context, task)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("后台命令已完成", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("生成报告", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.extras.toString().contains("secret-command"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(MainActivity.ACTION_OPEN_TERMINAL, shadowOf(notification.contentIntent).savedIntent.action)
    }
}
