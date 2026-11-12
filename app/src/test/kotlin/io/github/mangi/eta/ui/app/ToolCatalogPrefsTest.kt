package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 从 [ToolCatalogUiTest] 拆出来的：[Prefs] 开关要真的 SharedPreferences，只有 Robolectric 能给。
 *
 * 单独成类是因为本机（Linux aarch64）没有 Robolectric native runtime，
 * app/build.gradle.kts 会按 @RunWith(RobolectricTestRunner) 整类排除；放在一起会把
 * 同类的纯 JVM 断言一起牺牲掉。需要排查时加 -Peta.runRobolectric=true 照常跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogPrefsTest {
    @Test
    fun disabledToolsAreOmittedFromModelCatalogAndPersistInPrefs() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), false)
        assertFalse(Prefs.isToolEnabled("read_sms_code"))
        assertFalse(Prefs.isToolEnabled("tap"))
        assertFalse(Prefs.isToolEnabled("tap_area"))
        assertTrue(Prefs.isToolEnabled("list_alarms"))

        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            disabledTools = Prefs.disabledTools(),
        )
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertFalse("read_sms_code" in names)
        assertFalse("tap" in names)
        assertFalse("tap_area" in names)
        assertTrue("list_alarms" in names)
        assertTrue("tap_element" in names)

        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), true)
        assertTrue(Prefs.isToolEnabled("read_sms_code"))
        assertTrue(Prefs.isToolEnabled("tap"))
    }
}
