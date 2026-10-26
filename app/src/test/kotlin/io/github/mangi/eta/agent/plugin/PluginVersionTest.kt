package io.github.mangi.eta.agent.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件版本比较。
 *
 * 这些用例守的是「检查更新」最容易骗人的几种情况：
 * 1. 字符串比较把 `1.10.0` 判成比 `1.9.0` 旧（版本号过 10 才会暴露）；
 * 2. 来源被回退时，把"远端更旧"说成"已是最新"，用户永远不知道该重装来源；
 * 3. 解析不了的版本格式，硬判成"有更新"或"已是最新"都是编造结论。
 */
class PluginVersionTest {

    private fun newer(remote: String, current: String) =
        assertEquals(PluginVersion.Relation.NEWER, PluginVersion.relate(remote, current))

    private fun older(remote: String, current: String) =
        assertEquals(PluginVersion.Relation.OLDER, PluginVersion.relate(remote, current))

    private fun same(remote: String, current: String) =
        assertEquals(PluginVersion.Relation.SAME, PluginVersion.relate(remote, current))

    @Test
    fun `minor above nine is newer not older`() {
        newer("1.10.0", "1.9.0")
        older("1.9.0", "1.10.0")
        newer("10.0.0", "9.9.9")
    }

    @Test
    fun `missing segments count as zero`() {
        same("1.3", "1.3.0")
        same("2", "2.0.0")
        newer("2.0.1", "2")
    }

    @Test
    fun `leading v and build metadata are ignored`() {
        same("v1.2.0", "1.2.0")
        same("V1.2.0", "v1.2.0")
        same("1.3.0+build7", "1.3.0")
        equalCompare("1.3.0+aaa", "1.3.0+bbb")
    }

    @Test
    fun `prerelease is older than the release`() {
        older("1.0.0-rc1", "1.0.0")
        newer("1.0.0", "1.0.0-rc1")
        newer("1.0.0-rc2", "1.0.0-rc1")
        older("1.0.0-beta", "1.0.0-rc1")
    }

    @Test
    fun `unparseable versions are reported as different rather than guessed`() {
        assertEquals(PluginVersion.Relation.DIFFERENT, PluginVersion.relate("1.2.x", "1.2.0"))
        assertEquals(PluginVersion.Relation.DIFFERENT, PluginVersion.relate("", "1.2.0"))
        assertEquals(PluginVersion.Relation.SAME, PluginVersion.relate("nightly", "nightly"))
        assertNull(PluginVersion.compare("1.2.x", "1.2.0"))
        assertNull(PluginVersion.compare("999999999999", "1.0.0"))
    }

    @Test
    fun `update availability never calls a downgrade an update`() {
        assertTrue(PluginVersion.isUpdateAvailable("1.3.0", "1.2.0"))
        assertFalse(PluginVersion.isUpdateAvailable("1.2.0", "1.2.0"))
        assertFalse(PluginVersion.isUpdateAvailable("1.2.0", "1.3.0"))
        // 格式读不出来但确实不同：让用户看到"有变化"比静默更安全。
        assertTrue(PluginVersion.isUpdateAvailable("1.2.x", "1.2.0"))
    }

    @Test
    fun `compare returns a sign not a magnitude contract`() {
        assertTrue(PluginVersion.compare("1.3.0", "1.2.9")!! > 0)
        assertTrue(PluginVersion.compare("1.2.9", "1.3.0")!! < 0)
        assertEquals(0, PluginVersion.compare("1.3.0", "1.3.0") ?: Int.MIN_VALUE)
        assertEquals(0, PluginVersion.compare("v1.3.0", "1.3") ?: Int.MIN_VALUE)
    }

    private fun equalCompare(left: String, right: String) =
        assertEquals(0, PluginVersion.compare(left, right) ?: Int.MIN_VALUE)
}
