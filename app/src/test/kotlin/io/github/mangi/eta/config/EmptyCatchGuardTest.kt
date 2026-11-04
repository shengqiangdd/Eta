package io.github.mangi.eta.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空 catch 守卫：`catch (...) {}` 什么也不做时，异常的原因就彻底消失了 —— 既没有日志也没有
 * 调用点，出问题时只能靠猜。虚拟屏的音频焦点恢复就栽在这里：整段恢复循环套在一个空 catch 里，
 * 第一个包恢复失败后后面的包全部留在静音状态，而日志里一个字都没有，用户只会觉得「某个应用
 * 一直没声音」。
 *
 * 所以规则只有一条：catch 块里至少留一句注释说明为什么可以忽略。忽略本身常常是对的
 * （ROM 差异、可选的 DOM 查询），但「为什么」必须写在代码里。
 *
 * 注：对注入浏览器的 JS 字符串里的 `catch (_) {}` 同样适用，加一句注释即可通过。
 */
class EmptyCatchGuardTest {

    @Test
    fun noCatchBlockIsSilentlyEmpty() {
        val root = findMainKotlin()
        val offenders = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanned++
                val text = file.readText()
                EMPTY_CATCH.findAll(text).forEach { match ->
                    val line = text.take(match.range.first).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(root).invariantSeparatorsPath}:$line"
                }
            }
        // 路径解析失败时不要静悄悄地「通过」。
        assertTrue("只扫描到 $scanned 个 Kotlin 文件，源码路径解析可能失效", scanned > 100)
        assertTrue(
            "这些 catch 块既没有处理也没有注释，异常会被彻底吞掉（至少写一句为什么可以忽略）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun findMainKotlin(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            File(current, "app/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            File(current, "eta-v2/app/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            dir = current.parentFile
        }
        error("找不到 app/src/main/kotlin（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        /** `catch (xxx) {` 与 `}` 之间只有空白（含换行与纯空格）。 */
        val EMPTY_CATCH = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
    }
}