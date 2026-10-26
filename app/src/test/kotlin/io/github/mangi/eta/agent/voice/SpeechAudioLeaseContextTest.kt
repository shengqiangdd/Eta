package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 静态字段 `current` 会间接持有租约，所以租约内部的 Context 必须是 Application Context：
 * 否则从 Compose 页面（Activity Context）发起的录音 / 朗读会把整个界面留住直到 close。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechAudioLeaseContextTest {

    private class RecordingAppContext(base: Context) : ContextWrapper(base) {
        var registeredReceivers = 0
            private set
        var unregisteredReceivers = 0
            private set

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int,
        ): Intent? {
            registeredReceivers++
            return null
        }

        // 与上面成对重写：不接管 unregister 的话，close() 会走真实实现并报「Receiver not registered」。
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            unregisteredReceivers++
        }
    }

    private class SpyActivityContext(
        base: Context,
        private val application: Context,
    ) : ContextWrapper(base) {
        var registeredReceivers = 0
            private set

        override fun getApplicationContext(): Context = application

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int,
        ): Intent? {
            registeredReceivers++
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            registeredReceivers--
        }
    }

    @Test
    fun leaseUsesApplicationContextInsteadOfActivity() {
        val app = RecordingAppContext(RuntimeEnvironment.getApplication())
        val activity = SpyActivityContext(RuntimeEnvironment.getApplication(), app)

        val lease = SpeechAudioLease(activity) {}
        lease.acquire(playback = false)
        lease.close()

        assertEquals("广播必须注册在 Application Context 上", 1, app.registeredReceivers)
        assertEquals("close 也必须落在 Application Context 上", 1, app.unregisteredReceivers)
        assertEquals("不能注册在 Activity Context 上", 0, activity.registeredReceivers)
    }
}
