package io.github.mangi.eta.agent.acp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 委派时的模型选择：默认 → 用代理当前模型；指定了 → 本地校验后再切换。
 *
 * 真机事实（PKX110，2026-10-27）：codex-acp 的 `session/new` 会回
 * `models.currentModelId = "gpt-5.6-sol/low"` 与 41 个 `availableModels`；
 * `session/set_model` 接受任意字符串且不报错（`no-such-model-xyz` 也返回 `{}`），
 * 所以「名字写错」必须由 Eta 自己拦住。
 */
class AcpModelsTest {

    private fun sessionNew(
        current: String?,
        available: List<String>,
    ): JSONObject = JSONObject().put(
        "models",
        JSONObject()
            .put("currentModelId", current ?: JSONObject.NULL)
            .put(
                "availableModels",
                JSONArray().also { array ->
                    available.forEach { id -> array.put(JSONObject().put("modelId", id).put("name", id)) }
                },
            ),
    )

    @Test
    fun `parses current and available models`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-6.1-sol/high", "gpt-6.1-sol/low")))
        assertEquals("gpt-5.6-sol/low", info.currentModelId)
        assertEquals(listOf("gpt-6.1-sol/high", "gpt-6.1-sol/low"), info.available)
    }

    @Test
    fun `missing models block yields empty info`() {
        val info = AcpModels.parse(JSONObject())
        assertTrue(info.isEmpty)
        assertNull(info.currentModelId)
    }

    @Test
    fun `no requested model means keep agent default`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-5.6-sol/low")))
        assertNull(AcpModels.choose(null, info))
        assertNull(AcpModels.choose("   ", info))
    }

    @Test
    fun `requested model in list is accepted and verified`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-6.1-sol/high", "gpt-5.6-sol/low")))
        val choice = AcpModels.choose("gpt-6.1-sol/high", info)
        assertEquals(AcpModels.Choice.Use("gpt-6.1-sol/high", verified = true), choice)
    }

    @Test
    fun `unknown model is rejected with the available list`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-6.1-sol/high", "gpt-5.6-sol/low")))
        val choice = AcpModels.choose("no-such-model-xyz", info)
        assertTrue(choice is AcpModels.Choice.Reject)
        val message = (choice as AcpModels.Choice.Reject).message
        assertTrue(message.contains("no-such-model-xyz"))
        assertTrue(message.contains("gpt-6.1-sol/high"))
    }

    @Test
    fun `agent without model list still allows the request to pass through`() {
        val choice = AcpModels.choose("custom-model", AcpModels.Info())
        assertEquals(AcpModels.Choice.Use("custom-model", verified = false), choice)
    }

    @Test
    fun `over long model id is rejected early`() {
        val info = AcpModels.parse(sessionNew("m", listOf("m")))
        val choice = AcpModels.choose("x".repeat(AcpModels.MAX_MODEL_ID_CHARS + 1), info)
        assertTrue(choice is AcpModels.Choice.Reject)
    }

    @Test
    fun `available json is capped`() {
        val many = (1..40).map { "model-$it" }
        val info = AcpModels.parse(sessionNew("model-1", many))
        val json = AcpModels.availableJson(info)
        assertEquals(AcpModels.MAX_LISTED_MODELS, json.length())
        assertEquals("model-1", json.optString(0))
    }

    @Test
    fun `config model equal to request needs no switch`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-5.6-sol/low")))
        assertNull(AcpModels.chooseWithConfig("gpt-5.6-sol", "gpt-5.6-sol", info))
    }

    @Test
    fun `request missing from list keeps config model instead of failing`() {
        val info = AcpModels.parse(sessionNew("gpt-5.6-sol/low", listOf("gpt-5.6-sol/low")))
        assertNull(AcpModels.chooseWithConfig("gpt-9-nope", "gpt-5.6-sol", info))
    }

    @Test
    fun `request only present with tier suffix is not switched`() {
        val info = AcpModels.parse(
            sessionNew("gpt-5.6-sol/low", listOf("gpt-5.6-sol/low", "gpt-6.1-sol/high")),
        )
        // 清单里只有带档位的 `gpt-6.1-sol/high`：切过去会 Internal error，所以保持 config。
        assertNull(AcpModels.chooseWithConfig("gpt-6.1-sol", "gpt-5.6-sol", info))
    }

    @Test
    fun `request listed and different from config is switched`() {
        val info = AcpModels.parse(
            sessionNew("gpt-5.6-sol/low", listOf("gpt-5.6-sol/low", "gpt-6.1-sol/high")),
        )
        assertEquals(
            AcpModels.Choice.Use("gpt-6.1-sol/high", verified = true),
            AcpModels.chooseWithConfig("gpt-6.1-sol/high", "gpt-5.6-sol", info),
        )
    }

    @Test
    fun `without config the request is checked as before`() {
        val info = AcpModels.parse(sessionNew("m", listOf("m")))
        assertTrue(AcpModels.chooseWithConfig("nope", null, info) is AcpModels.Choice.Reject)
    }

    @Test
    fun `describe carries count even when list is capped`() {
        val many = (1..40).map { "model-$it" }
        val described = AcpModels.describe(AcpModels.parse(sessionNew("model-1", many)))
        assertEquals(40, described.optInt("available_count"))
        assertEquals(AcpModels.MAX_LISTED_MODELS, described.optJSONArray("available_models")?.length())
    }

    @Test
    fun onlyCodexWritesTheModelIntoAConfigFile() {
        // Claude 没有配置文件这一层：ANTHROPIC_MODEL 是 Claude Code（CLI）自己的变量，
        // claude-code-acp 适配器不读。判成「已写进配置」就会跳过 session/set_model，
        // 结果模型从来没被设置过 —— 2026-11-04 实测就是这样去请求了内置的
        // claude-sonnet-4-5-20250929，然后 Internal error。
        assertTrue(AcpModels.modelComesFromConfig("codex"))
        assertFalse(AcpModels.modelComesFromConfig("claude"))
    }

    @Test
    fun unknownAgentDefaultsToNeedingExplicitModelSwitch() {
        // 未来新增的代理：默认它**不能**靠配置文件带上模型。
        // 方向选错（默认 true）会让新代理静默用错模型；选 false 最多多发一次
        // session/set_model —— 代价小得多。
        assertFalse(AcpModels.modelComesFromConfig("gemini"))
    }

    @Test
    fun claudeModelIsSwitchedViaProtocolNotSkipped() {
        // 端到端含义：Claude 走 set_model 路径时，请求的模型必须真的被发出去。
        val info = AcpModels.Info(
            currentModelId = "default",
            available = listOf("default", "claude-sonnet-5"),
        )
        // configModel 为 null（Claude 的正确取值）→ 必须给出 Use，而不是 null。
        val choice = AcpModels.chooseWithConfig("claude-sonnet-5", null, info)
        assertTrue("Claude 的模型必须走 set_model，不能被跳过", choice is AcpModels.Choice.Use)
        assertEquals("claude-sonnet-5", (choice as AcpModels.Choice.Use).modelId)
        // 反过来：如果把 configModel 也设成同一个值（旧 bug），就会返回 null → 不切。
        assertNull(
            "旧行为（configModel 与请求值相同）会跳过切换 —— 这正是缺陷本身",
            AcpModels.chooseWithConfig("claude-sonnet-5", "claude-sonnet-5", info),
        )
    }
}
