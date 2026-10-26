package io.github.mangi.eta.ui.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 三语资源的一致性护栏（纯 JVM，不依赖 Robolectric）。
 *
 * 背景：`values/` 是英文默认资源，另外两份是 zh-CN / zh-TW。之前出现过两类真实问题：
 * 1. 英文界面里仍显示中文（有人直接把中文写进 `values/`，或漏翻译新键）；
 * 2. 繁体缺键时静默回退到英文，界面中英混排。
 *
 * 这里只校验**原始 XML 数据**：不验证 aapt 编译后的资源解析，也不验证界面排版。
 */
class LocaleResourceParityTest {
    private val resRoot: File = findResRoot()

    private val locales = mapOf(
        "en" to "values",
        "zh-CN" to "values-b+zh+Hans",
        "zh-TW" to "values-b+zh+Hant",
    )

    @Test
    fun englishResourcesContainNoChineseText() {
        val offenders = mutableListOf<String>()
        englishEntries().forEach { (key, value) ->
            if (CJK.containsMatchIn(value)) {
                offenders += "$key = ${value.take(80)}"
            }
        }
        assertTrue(
            "英文默认资源里仍有中文，英文界面会直接显示这些文字：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun allLocalesDefineTheSameKeys() {
        val enKeys = keysOf("values")
        val enOnly = untranslatableKeys()
        locales.filterKeys { it != "en" }.forEach { (label, dir) ->
            val keys = keysOf(dir)
            val missing = ((enKeys - enOnly) - keys).sorted()
            val extra = (keys - enKeys).sorted()
            assertTrue("$label 缺少 ${missing.size} 个键：$missing", missing.isEmpty())
            assertTrue("$label 多出 ${extra.size} 个键：$extra", extra.isEmpty())
        }
    }

    /**
     * `translatable="false"` 的键只应存在于英文默认资源：
     * 翻译它们会触发 lint 的 `Untranslatable`，且这类键通常是品牌名/术语，本就不该译。
     */
    @Test
    fun untranslatableKeysAreNotTranslated() {
        val enOnly = untranslatableKeys()
        val offenders = locales.filterKeys { it != "en" }.flatMap { (label, dir) ->
            val keys = keysOf(dir)
            enOnly.filter { it in keys }.sorted().map { "$label/$it" }
        }
        assertTrue(
            "这些键标记了 translatable=\"false\"，不应出现在翻译里：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun formatPlaceholdersMatchAcrossLocales() {
        val en = entriesOf("values")
        val mismatches = mutableListOf<String>()
        locales.filterKeys { it != "en" }.forEach { (label, dir) ->
            val localized = entriesOf(dir)
            en.forEach { (key, value) ->
                val other = localized[key] ?: return@forEach
                val expected = placeholderIndexes(value)
                val actual = placeholderIndexes(other)
                if (expected != actual) {
                    mismatches += "$label/$key: 期望 $expected，实际 $actual"
                }
            }
        }
        assertTrue("占位符不一致会导致运行时格式化崩溃：\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    private fun englishEntries(): Map<String, String> = entriesOf("values")

    private fun keysOf(dir: String): Set<String> = entriesOf(dir).keys

    /** 同一目录下可能拆成多个 xml，按 key 合并。 */
    private fun entriesOf(dir: String): Map<String, String> {
        val folder = File(resRoot, dir)
        assertTrue("找不到资源目录：$folder", folder.isDirectory)
        val out = linkedMapOf<String, String>()
        folder.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = document.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
                    when (element.tagName) {
                        "string" -> out[name] = element.textContent
                        "plurals" -> {
                            val items = element.getElementsByTagName("item")
                            val texts = (0 until items.length).map { items.item(it).textContent }
                            out[name] = texts.joinToString("\u0001")
                        }
                    }
                }
            }
        return out
    }

    private fun placeholderIndexes(value: String): Set<String> =
        PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toSet()

    private fun untranslatableKeys(): Set<String> {
        val folder = File(resRoot, "values")
        assertTrue("找不到资源目录：$folder", folder.isDirectory)
        val out = linkedSetOf<String>()
        folder.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = document.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    if (element.tagName != "string") continue
                    if (element.getAttribute("translatable") != "false") continue
                    element.getAttribute("name").takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
        return out
    }

    private fun findResRoot(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("无法定位 app/src/main/res，当前目录：${File(".").absolutePath}")
    }

    private companion object {
        val CJK = Regex("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]")
        val PLACEHOLDER = Regex("%(\\d+)\\$[a-zA-Z]")
    }
}
