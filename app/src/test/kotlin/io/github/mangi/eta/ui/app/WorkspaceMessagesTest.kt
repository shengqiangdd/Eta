package io.github.mangi.eta.ui.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区两条「专用失败文案」的资源约束。
 *
 * 为什么不用 Robolectric：本机（Linux/aarch64）跑不了 Robolectric native runtime，
 * 而「新加的 key 只加了一种语言」「文案退化成通用失败」这两类问题恰恰最需要门禁。
 * 这里直接读模块里的 XML，纯 JVM 就能跑，代价是只校验字面量、不校验运行期解析。
 */
class WorkspaceMessagesTest {

    private val resDirectories = listOf(
        "values" to "en",
        "values-b+zh+Hans" to "zh-Hans",
        "values-b+zh+Hant" to "zh-Hant",
    )

    @Test
    fun dedicatedWorkspaceFailuresExistInEveryLocaleAndDifferFromTheGenericOne() {
        val generic = strings("values")["capability_workspace_failed"]
        assertTrue("找不到通用失败文案，资源结构变了？", !generic.isNullOrBlank())
        for (key in listOf("capability_workspace_import_not_file", "capability_workspace_export_name_busy")) {
            for ((directory, locale) in resDirectories) {
                val text = strings(directory)[key]
                assertTrue("$locale 缺少 $key", !text.isNullOrBlank())
                assertTrue(
                    "$locale 的 $key 与通用失败文案相同，等于没有区分：$text",
                    text != generic,
                )
            }
        }
    }

    @Test
    fun importLimitAndNotFileMessagesDoNotContradictEachOther() {
        // 同一类操作的两条提示不能互相矛盾：太大 ≠ 不是文件。三语各自守一遍。
        for ((directory, locale) in resDirectories) {
            val map = strings(directory)
            val tooLarge = map["capability_workspace_import_too_large"].orEmpty()
            val notFile = map["capability_workspace_import_not_file"].orEmpty()
            assertTrue("$locale 的「太大」文案丢了 MiB：$tooLarge", tooLarge.contains("32 MiB"))
            assertTrue("$locale 的两条导入提示被写成同一句：$tooLarge", tooLarge != notFile)
        }
    }

    private fun strings(directory: String): Map<String, String> {
        val file = resourceFile(directory)
        assertTrue("找不到资源文件：$file", file.isFile)
        val text = file.readText()
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    private fun resourceFile(directory: String): File {
        val name = "$directory/root_capabilities.xml"
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = current ?: return@repeat
            val moduleRelative = File(base, "app/src/main/res/$name")
            if (moduleRelative.isFile) return moduleRelative
            val projectRelative = File(base, "src/main/res/$name")
            if (projectRelative.isFile) return projectRelative
            current = base.parentFile
        }
        return File("app/src/main/res/$name")
    }

    @Test
    fun everyWorkspaceKeyIsPresentInEveryLocale() {
        // 反向自测：如果解析本身坏了（比如正则没命中），上面两条会「全缺失」而不是假绿，
        // 这里再守一次解析出的条目数，避免正则写错后整张表是空 Map。
        val english = strings("values")
        assertTrue("解析出的字符串太少，正则可能失效：${english.size}", english.size > 100)
        for ((directory, locale) in resDirectories) {
            val other = strings(directory)
            assertEquals("$locale 的条目数与英文不一致", english.size, other.size)
        }
    }
}
