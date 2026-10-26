package io.github.mangi.eta.agent.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PluginManifest 的纯逻辑校验：不涉及 Android 框架，也不需要 Robolectric。 */
class PluginManifestTest {

    private fun manifest(body: JSONObject.() -> Unit): String =
        JSONObject().apply(body).toString()

    @Test
    fun minimalPromptOnlyManifestIsAccepted() {
        val result = PluginManifest.parse(
            manifest {
                put("id", "dsh-codex")
                put("version", "1.0.0")
                put("prompt", "你可以用 Codex 做编码委派。")
            },
        )
        assertTrue("expected success but was $result", result is PluginManifest.ParseResult.Success)
        val plugin = (result as PluginManifest.ParseResult.Success).plugin
        assertEquals("dsh-codex", plugin.id)
        assertEquals(1, plugin.tools.size + plugin.mcp.size + plugin.acp.size + 1)
    }

    @Test
    fun invalidIdsAndVersionsAreRejectedWithStableCodes() {
        val badId = PluginManifest.parse(
            manifest { put("id", "Bad_Id"); put("version", "1.0.0"); put("prompt", "x") },
        )
        assertEquals("MANIFEST_BAD_ID", (badId as PluginManifest.ParseResult.Failure).code)

        val badVersion = PluginManifest.parse(
            manifest { put("id", "good-id"); put("version", "1.0"); put("prompt", "x") },
        )
        assertEquals("MANIFEST_BAD_VERSION", (badVersion as PluginManifest.ParseResult.Failure).code)

        val prerelease = PluginManifest.parse(
            manifest { put("id", "good-id"); put("version", "2.3.1-rc.1"); put("prompt", "x") },
        )
        assertTrue(prerelease is PluginManifest.ParseResult.Success)
    }

    @Test
    fun malformedJsonAndOversizedManifestFail() {
        assertEquals(
            "MANIFEST_INVALID_JSON",
            (PluginManifest.parse("{not json") as PluginManifest.ParseResult.Failure).code,
        )
        val huge = JSONArray().let { _ ->
            JSONObject().put("id", "big").put("version", "1.0.0")
                .put("prompt", "x".repeat(PluginManifest.MAX_MANIFEST_BYTES + 10)).toString()
        }
        assertEquals(
            "MANIFEST_TOO_LARGE",
            (PluginManifest.parse(huge) as PluginManifest.ParseResult.Failure).code,
        )
    }

    @Test
    fun emptyManifestFailsBecauseNothingIsDeclared() {
        val result = PluginManifest.parse(manifest { put("id", "nothing"); put("version", "1.0.0") })
        assertEquals("MANIFEST_EMPTY", (result as PluginManifest.ParseResult.Failure).code)
    }

    @Test
    fun mcpEntriesRequireHttpTransportWithUrl() {
        fun mcp(build: JSONObject.() -> Unit): PluginManifest.ParseResult =
            PluginManifest.parse(
                manifest {
                    put("id", "mcp-demo"); put("version", "1.0.0")
                    put("mcp", JSONArray().put(JSONObject().apply(build)))
                },
            )

        assertEquals(
            "MANIFEST_BAD_MCP_NAME",
            (mcp { put("name", "1bad"); put("transport", "http"); put("url", "https://x.test") }
                as PluginManifest.ParseResult.Failure).code,
        )
        // stdio 暂未实现：必须明确拒绝，而不是注册一个连不上的服务器。
        assertEquals(
            "MANIFEST_MCP_HTTP_ONLY",
            (mcp { put("name", "ok"); put("transport", "stdio"); put("command", "/bin/true") }
                as PluginManifest.ParseResult.Failure).code,
        )
        assertEquals(
            "MANIFEST_MCP_HTTP_ONLY",
            (mcp { put("name", "ok") } as PluginManifest.ParseResult.Failure).code,
        )
        assertEquals(
            "MANIFEST_BAD_MCP_URL",
            (mcp { put("name", "ok"); put("transport", "http"); put("url", "ftp://x") }
                as PluginManifest.ParseResult.Failure).code,
        )
        assertTrue(
            mcp { put("name", "ok"); put("transport", "http"); put("url", "https://example.test") }
                is PluginManifest.ParseResult.Success,
        )
    }

    @Test
    fun acpExecutableMustBeAbsoluteAndIdWellFormed() {
        fun acp(build: JSONObject.() -> Unit): PluginManifest.ParseResult =
            PluginManifest.parse(
                manifest {
                    put("id", "acp-demo"); put("version", "1.0.0")
                    put("acp", JSONArray().put(JSONObject().apply(build)))
                },
            )

        assertEquals(
            "MANIFEST_BAD_ACP_ID",
            (acp { put("id", "Bad"); put("executable", "/opt/x") }
                as PluginManifest.ParseResult.Failure).code,
        )
        assertEquals(
            "MANIFEST_BAD_ACP_EXECUTABLE",
            (acp { put("id", "codex"); put("executable", "opt/eta/node/bin/codex-acp") }
                as PluginManifest.ParseResult.Failure).code,
        )
        val ok = acp {
            put("id", "codex"); put("title", "Codex"); put("executable", "/opt/eta/node/bin/codex-acp")
            put("credential_env_keys", JSONArray().put("CODEX_API_KEY").put("OPENAI_API_KEY"))
        }
        assertTrue(ok is PluginManifest.ParseResult.Success)
        val agent = (ok as PluginManifest.ParseResult.Success).plugin.acp.single()
        assertEquals("Codex", agent.title)
        assertEquals(listOf("CODEX_API_KEY", "OPENAI_API_KEY"), agent.credentialEnvKeys)
    }

    @Test
    fun toolNamesMustMatchAgentToolNaming() {
        val bad = PluginManifest.parse(
            manifest {
                put("id", "tools-demo"); put("version", "1.0.0")
                put("tools", JSONArray().put(JSONObject().put("name", "Bad-Name").put("description", "d")))
            },
        )
        assertEquals("MANIFEST_BAD_TOOL", (bad as PluginManifest.ParseResult.Failure).code)

        val ok = PluginManifest.parse(
            manifest {
                put("id", "tools-demo"); put("version", "1.0.0")
                put("tools", JSONArray().put(JSONObject().put("name", "dsh_codex_run").put("description", "d")))
            },
        )
        assertTrue(ok is PluginManifest.ParseResult.Success)
    }

    @Test
    fun envUpperBoundIsActuallyEnforced() {
        val env = JSONObject()
        for (i in 0 until 80) env.put("K$i", "v")
        val result = PluginManifest.parse(
            manifest {
                put("id", "env-demo"); put("version", "1.0.0")
                put("mcp", JSONArray().put(JSONObject()
                    .put("name", "ok").put("transport", "stdio").put("command", "/bin/true")
                    .put("env", env)))
            },
        )
        // 上限必须真实生效：超限要被拒，而不是静默保留一堆 env。
        assertTrue("expected rejection but was $result", result is PluginManifest.ParseResult.Failure)
    }

    @Test
    fun summarizeReportsEveryDeclaredCapability() {        val plugin = PluginManifest.parse(
            manifest {
                put("id", "kitchen-sink"); put("version", "1.2.3")
                put("name", "Kitchen Sink"); put("description", "什么都加一点")
                put("prompt", "扩展说明")
                put("tools", JSONArray().put(JSONObject().put("name", "extra_tool").put("description", "d")))
                put("mcp", JSONArray().put(JSONObject().put("name", "m").put("transport", "http").put("url", "https://x.test")))
                put("acp", JSONArray().put(JSONObject().put("id", "codex").put("executable", "/opt/x")))
            },
        )
        val summary = PluginManifest.summarize((plugin as PluginManifest.ParseResult.Success).plugin)
        assertTrue(summary.contains("Kitchen Sink v1.2.3"))
        assertTrue(summary.contains("什么都加一点"))
        assertTrue(summary.contains("1 个说明性工具"))
        assertTrue(summary.contains("1 个 MCP 服务器"))
        assertTrue(summary.contains("1 个 ACP 代理"))
        assertTrue(summary.contains("提示词扩展"))
    }

    @Test
    fun acpAgentIdUsesPluginNamespaceAndRoundTrips() {
        assertEquals("dsh-codex:codex", PluginManifest.acpAgentId("dsh-codex", "codex"))
        assertEquals(
            "dsh-codex" to "codex",
            PluginManifest.parseAcpAgentId("dsh-codex:codex"),
        )
    }

    @Test
    fun acpAgentIdRejectsMalformedNamespaces() {
        // 没有冒号：不是插件命名空间，应判为内置 id 而非插件代理。
        assertEquals(null, PluginManifest.parseAcpAgentId("codex"))
        // 冒号在头部或尾部：两侧都为空。
        assertEquals(null, PluginManifest.parseAcpAgentId(":codex"))
        assertEquals(null, PluginManifest.parseAcpAgentId("dsh-codex:"))
        // 多个冒号：无法确定归属。
        assertEquals(null, PluginManifest.parseAcpAgentId("a:b:c"))
        // 任一侧不合法。
        assertEquals(null, PluginManifest.parseAcpAgentId("Bad-Plugin:codex"))
        assertEquals(null, PluginManifest.parseAcpAgentId("dsh-codex:Bad-Agent"))
    }
    @Test
    fun mcpServerIdsAreNamespacedPerPlugin() {
        assertEquals("plugin:demo:fetch", PluginManifest.mcpServerId("demo", "fetch"))
        assertEquals("plugin:demo:search", PluginManifest.mcpServerId("demo", "search"))
        assertTrue(PluginManifest.mcpServerId("a", "b") != PluginManifest.mcpServerId("a", "c"))
    }

}
