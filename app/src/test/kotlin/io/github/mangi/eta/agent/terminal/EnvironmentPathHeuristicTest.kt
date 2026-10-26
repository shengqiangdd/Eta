package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境自动纠正的判定边界。
 *
 * 这个功能会**改变**工具实际执行的环境，判错的代价是「在错误的一侧执行」，
 * 所以每条规则都要有测试守着：尤其是「两侧都有路径时不猜」这一条。
 */
class EnvironmentPathHeuristicTest {

    private fun resolve(requested: String, vararg paths: String) =
        EnvironmentPathHeuristic.resolve(requested, paths.toList())

    @Test fun linuxPathUnderAndroidSwitchesToLinux() {
        val result = resolve("android", "/workspace/eta-v2/src/Main.kt")
        assertEquals("linux", result.environment)
        assertEquals("android", result.switchedFrom)
        assertTrue(result.switched)
    }

    @Test fun linuxPathWithoutExplicitEnvironmentSwitchesToo() {
        // 默认值就是 android：模型省略 environment 是最常见的写法，也必须纠正。
        val result = resolve("", "/workspace/README.md")
        assertEquals("linux", result.environment)
        assertEquals("android", result.switchedFrom)
    }

    @Test fun androidPathUnderLinuxSwitchesBack() {
        val result = resolve("linux", "/data/local/tmp/eta/app.apk")
        assertEquals("android", result.environment)
        assertEquals("linux", result.switchedFrom)
    }

    @Test fun sdcardAndUserStorageCountAsAndroid() {
        assertEquals("android", resolve("linux", "/sdcard/Download/a.txt").environment)
        assertEquals("android", resolve("linux", "/storage/emulated/0/a.txt").environment)
        assertEquals("android", resolve("linux", "/data/user/0/io.github.mangi.eta/files/x").environment)
    }

    @Test fun alreadyCorrectEnvironmentIsNotReportedAsSwitch() {
        val linux = resolve("linux", "/workspace/a")
        assertEquals("linux", linux.environment)
        assertNull(linux.switchedFrom)
        assertNull(linux.note)
        assertFalse(linux.switched)

        val android = resolve("android", "/data/local/tmp/a")
        assertEquals("android", android.environment)
        assertNull(android.switchedFrom)
    }

    @Test fun pathsFromBothSidesLeaveEnvironmentUntouched() {
        // `adb pull /data/local/tmp/x /workspace/x` 这类跨环境命令：
        // 切哪边都会让另一半失败，只能保持原样。
        val result = resolve("android", "/data/local/tmp/a.apk", "/workspace/mounts/b.apk")
        assertEquals("android", result.environment)
        assertNull(result.switchedFrom)
    }

    @Test fun unknownPathsNeverTriggerASwitch() {
        val result = resolve("android", "/etc/hostname", "/proc/cpuinfo")
        assertEquals("android", result.environment)
        assertNull(result.switchedFrom)
    }

    @Test fun relativeAndBlankPathsAreIgnored() {
        val result = resolve("linux", "src/main.kt", "   ", "")
        assertEquals("linux", result.environment)
        assertNull(result.switchedFrom)
    }

    @Test fun rootPrefixNeedsAPathBoundary() {
        // `/root` 是 Linux 家目录，`/rootfs` 不是；不能用 startsWith 一把梭。
        assertEquals("linux", resolve("android", "/root").environment)
        assertEquals("linux", resolve("android", "/root/.bashrc").environment)
        assertEquals("android", resolve("android", "/rootfs/image.img").environment)
    }

    @Test fun commandPathsAreExtractedForJudging() {
        val paths = EnvironmentPathHeuristic.pathsInCommand(
            "cd /workspace/eta-v2 && ./gradlew test 2>/dev/null; ls /data/local/tmp",
        )
        assertTrue(paths.contains("/workspace/eta-v2"))
        assertTrue(paths.contains("/data/local/tmp"))
    }

    @Test fun commandWithOnlyLinuxPathsSwitches() {
        val command = "cd /workspace/eta-v2 && ./gradlew :app:testDebugUnitTest"
        val result = EnvironmentPathHeuristic.resolve(
            requested = "android",
            paths = EnvironmentPathHeuristic.pathsInCommand(command),
        )
        assertEquals("linux", result.environment)
        assertTrue(result.note!!.contains("environment=linux"))
    }

    @Test fun noteExplainsHowToGoBack() {
        val note = resolve("android", "/workspace/a").note!!
        assertTrue(note.contains("environment=linux"))
        assertTrue(note.contains("environment=android"))
    }
}
