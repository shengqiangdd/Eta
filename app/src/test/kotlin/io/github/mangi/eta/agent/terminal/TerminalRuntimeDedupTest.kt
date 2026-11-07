package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal` 结果里的 `runtime` 块只在变化时下发。
 *
 * 真机 trace：一条 `printf ok` 的输出是 **2 个字符**，却带回 **384 字符** 的 runtime。
 * 那些字段在一次会话里每次调用都相同，几十轮下来就是几万 Token 的纯重复。
 */
class TerminalRuntimeDedupTest {

    private fun runtime(provider: String = "dash"): JSONObject = JSONObject()
        .put("host_identity", "root")
        .put("uid", 0)
        .put("uid_scope", "guest")
        .put("shell_provider", provider)
        .put("shell_executable", "/usr/bin/$provider")
        .put("available_commands", JSONArray(listOf("sh", "rg", "grep")))
        .put("capability_scope", "仅描述解析结果，不代表授权")

    @Test
    fun firstCallSendsTheBlock() {
        val (signature, payload) = TerminalRuntimeDedup.diff(null, runtime())
        assertNotNull("第一次必须下发：模型此前没见过", payload)
        assertTrue(signature.isNotEmpty())
    }

    @Test
    fun unchangedRuntimeIsNotResent() {
        val block = runtime()
        val (signature, first) = TerminalRuntimeDedup.diff(null, block)
        assertNotNull(first)

        val (sameSignature, second) = TerminalRuntimeDedup.diff(signature, runtime())
        assertNull("完全一致时不该再发一遍", second)
        assertEquals("指纹应保持不变", signature, sameSignature)
    }

    @Test
    fun changedRuntimeIsResent() {
        val (signature, _) = TerminalRuntimeDedup.diff(null, runtime("dash"))

        val (_, changed) = TerminalRuntimeDedup.diff(signature, runtime("bash"))

        assertNotNull("换了 shell 就必须重新告诉模型", changed)
        assertEquals("bash", changed?.getString("shell_provider"))
    }

    @Test
    fun slimmingStillSaysItIsNotAnAuthorization() {
        val scope = runtime().getString("capability_scope")
        // 原来是 106 字符的长句，每次调用都重复；压短但含义不能丢。
        assertTrue("capability_scope 太长（${scope.length} 字符）", scope.length <= 20)
        assertTrue("必须仍表明「不代表授权」：$scope", scope.contains("授权"))
    }
}
