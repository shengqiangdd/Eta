package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runtime 服务的绑定目标必须跟随构建的 applicationId。
 *
 * 回归背景：这里曾写死发布包名 io.github.mangi.eta，Canary 构建（applicationId
 * io.github.mangi.eta.canary）在界面上点发送后，请求被投递到**另一个应用**的服务，
 * 本应用的服务从未创建，表现为"没有回答、没有报错、界面最终回到空闲"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeWireServiceIntentTest {
    @Test
    fun serviceIntentTargetsOwnPackage() {
        val context = RuntimeEnvironment.getApplication()
        val intent = AgentRuntimeWire.serviceIntent(context)
        val component = requireNotNull(intent.component) { "serviceIntent 必须指定显式组件" }
        assertEquals(context.packageName, component.packageName)
        assertEquals(AgentRuntimeWire.ACTION_BIND, intent.action)
        assertTrue(component.className.endsWith("AgentRuntimeService"))
    }

    @Test
    fun serviceIntentIsNotHardcodedToAnotherPackage() {
        val context = RuntimeEnvironment.getApplication()
        val component = requireNotNull(AgentRuntimeWire.serviceIntent(context).component)
        // 只要构建的 applicationId 与发布包名不同（例如 Canary），就必须指向自己。
        if (context.packageName != "io.github.mangi.eta") {
            assertNotEquals("io.github.mangi.eta", component.packageName)
        }
    }
}
