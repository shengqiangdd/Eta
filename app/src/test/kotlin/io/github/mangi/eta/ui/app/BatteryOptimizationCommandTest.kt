package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationCommandTest {

    @Test
    fun commandCarriesTheCallerPackageName() {
        val cmd = BatteryOptimizationCommand.powerControlCommand("io.github.mangi.eta.canary")
        assertTrue("命令里必须带上调用方包名", cmd.contains("--es pkgName io.github.mangi.eta.canary"))
    }

    @Test
    fun commandWorksForTheReleasePackageName() {
        val cmd = BatteryOptimizationCommand.powerControlCommand("io.github.mangi.eta")
        assertTrue(cmd.contains("--es pkgName io.github.mangi.eta"))
        assertTrue("包名后必须是空格或结尾，不能粘上下一个参数", cmd.contains("pkgName io.github.mangi.eta --es drainType APP"))
    }

    @Test
    fun commandTargetsTheOplusPowerControlActivity() {
        val cmd = BatteryOptimizationCommand.powerControlCommand("com.example.app")
        assertEquals(
            "am start --user current -n " +
                "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                "--es title Eta --es pkgName com.example.app --es drainType APP",
            cmd,
        )
    }

    @Test
    fun blankPackageNameIsRejected() {
        val failure = runCatching { BatteryOptimizationCommand.powerControlCommand("  ") }.exceptionOrNull()
        assertTrue("空包名必须拒绝，否则会拼出一条打不开页面的命令", failure is IllegalArgumentException)
    }

    @Test
    fun packageNameWithShellMetacharactersIsRejected() {
        // 包名一旦能拼进 root shell 命令，这里的字符就是注入面。
        for (bad in listOf("com.example.app;rm -rf /", "com.example.app\$(id)", "com.example.app`id`")) {
            val failure = runCatching { BatteryOptimizationCommand.powerControlCommand(bad) }.exceptionOrNull()
            assertTrue("含 shell 元字符的包名必须拒绝：$bad", failure is IllegalArgumentException)
        }
    }
}
