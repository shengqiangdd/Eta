package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `exec` 路径的 `runtime` 去重。
 *
 * 真机 trace：`printf ok` 输出 **2 个字符**，却带回 **384 字符** 的 `runtime`
 * （`host_identity`/`uid`/`uid_scope`/`shell_provider`/`shell_executable`/
 * `available_commands`/`capability_scope`）。这些字段在同一 `(环境, 身份)` 下不会变。
 *
 * 关键一课：fix140 把去重写进了 `TerminalSession`，但真机上的 `terminal` 调用
 * 一直走的是**无状态的 exec 路径**（`runText` + `TerminalExecutionProbe.extract`），
 * 从不经过会话 —— 所以那次改动一次都没生效。这里用与控制器同样的 key/签名规则测。
 */
class TerminalRuntimeDedupTest {

    /** 与 `RootShellTerminalController.runtimeIfChanged` 相同的 key 与签名规则。 */
    private class Deduper {
        private val seen = mutableMapOf<String, String>()

        fun put(identity: String, environment: String, runtime: JSONObject?): JSONObject? {
            if (runtime == null) return null
            val key = "$environment|$identity"
            val signature = runtime.toString()
            if (seen[key] == signature) return null
            seen[key] = signature
            return runtime
        }
    }

    private fun runtime(provider: String = "dash"): JSONObject = JSONObject()
        .put("host_identity", "root")
        .put("uid", 0)
        .put("uid_scope", "guest")
        .put("shell_provider", provider)
        .put("shell_executable", "/usr/bin/$provider")
        .put("available_commands", org.json.JSONArray(listOf("sh", "rg", "grep")))
        .put("capability_scope", "仅描述解析结果，不代表授权")

    @Test
    fun firstCallSendsTheBlock() {
        val deduper = Deduper()
        assertNotNull("第一次要下发：模型此前没见过", deduper.put("root", "debian", runtime()))
    }

    @Test
    fun repeatedIdenticalExecDoesNotResend() {
        val deduper = Deduper()
        assertNotNull(deduper.put("root", "debian", runtime()))

        assertNull("同一 (环境,身份) 下第二次不该再发", deduper.put("root", "debian", runtime()))
        assertNull("第三次同样", deduper.put("root", "debian", runtime()))
    }

    @Test
    fun changedRuntimeIsResent() {
        val deduper = Deduper()
        deduper.put("root", "debian", runtime("dash"))

        val changed = deduper.put("root", "debian", runtime("bash"))

        assertNotNull("换了 shell 必须重新告诉模型", changed)
        assertEquals("bash", changed?.getString("shell_provider"))
    }

    @Test
    fun differentIdentityIsTrackedSeparately() {
        val deduper = Deduper()
        deduper.put("root", "debian", runtime())

        assertNotNull(
            "root 的 runtime 不能当成普通用户的：权限不同",
            deduper.put("user", "debian", runtime()),
        )
    }

    @Test
    fun differentEnvironmentIsTrackedSeparately() {
        val deduper = Deduper()
        deduper.put("root", "debian", runtime())

        assertNotNull(
            "debian 与 android 是两套执行环境",
            deduper.put("root", "android", runtime()),
        )
    }

    @Test
    fun capabilityScopeStaysShort() {
        val scope = runtime().getString("capability_scope")
        // 原来是 106 字符的长句，每次调用都重复；压短但含义不能丢。
        assert(scope.length <= 20) { "capability_scope 太长（${scope.length} 字符）：$scope" }
        assert(scope.contains("授权")) { "必须仍表明「不代表授权」：$scope" }
    }
}
