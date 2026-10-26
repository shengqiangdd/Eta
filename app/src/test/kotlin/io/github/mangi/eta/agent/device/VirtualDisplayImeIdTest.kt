package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 静默 IME 的系统 ID 折算规则。
 *
 * 为什么值得单测：IME 的组件名在 IMMS 里会被规范化（类名带包名前缀时可省略），
 * 用错格式 `ime enable` 只回一句 `Unknown input method`。
 * 实测正式包 `io.github.mangi.eta` 用全路径会失败、canary 包名多一段反而成功 ——
 * 这种「只有正式包坏」的差异不能只靠真机碰运气发现。
 */
class VirtualDisplayImeIdTest {
    private val className = "io.github.mangi.eta.agent.ime.EtaSilentImeService"

    @Test
    fun `正式包的类名带包名前缀时折成短格式`() {
        assertEquals(
            "io.github.mangi.eta/.agent.ime.EtaSilentImeService",
            canonicalImeId("io.github.mangi.eta", className),
        )
    }

    @Test
    fun `canary 包的类名不带包名前缀时保留全路径`() {
        assertEquals(
            "io.github.mangi.eta.canary/io.github.mangi.eta.agent.ime.EtaSilentImeService",
            canonicalImeId("io.github.mangi.eta.canary", className),
        )
    }

    @Test
    fun `完全无关的包名保留全路径`() {
        assertEquals(
            "com.example.app/io.github.mangi.eta.agent.ime.EtaSilentImeService",
            canonicalImeId("com.example.app", className),
        )
    }

    @Test
    fun `只按包名前缀匹配不误伤相似包名`() {
        // io.github.mangi.eta 是 io.github.mangi.etax 的前缀，但类名不属于它，不能简写。
        assertEquals(
            "io.github.mangi.etax/io.github.mangi.eta.agent.ime.EtaSilentImeService",
            canonicalImeId("io.github.mangi.etax", className),
        )
    }
}
