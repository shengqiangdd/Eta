package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩期间产生的 token 用量必须能被记账。
 *
 * 真机证据：`usage-2026-10.jsonl` 里 20,635 条记录 `purpose` 全是 `chat` ——
 * 压缩是**嵌套**的独立模型调用，不走主循环的 `AgentEvent.UsageReceived`，
 * 于是一直没进过账本。这两条用例把「上报」和「先记账再判形状」钉住。
 */
class AgentContextCompactionUsageTest {

    @Test
    fun compactionReportsUsageToSink() {
        val usages = mutableListOf<AgentTokenUsage>()
        AgentContextCompactor(
            config,
            provider { _, emit ->
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 1200, outputTokens = 300)))
                response("已完成此前的任务。")
            },
            AgentRunController(),
            onUsage = usages::add,
        ).compact(jsonHistory(), 1, emptySet())

        assertEquals("单批压缩应上报一次用量", 1, usages.size)
        assertEquals(1200, usages.first().inputTokens)
        assertEquals(300, usages.first().outputTokens)
    }

    @Test
    fun usageIsReportedEvenWhenAttemptViolatesShape() {
        val usages = mutableListOf<AgentTokenUsage>()
        runCatching {
            AgentContextCompactor(
                config,
                provider { _, emit ->
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 900)))
                    emit(ProviderEvent.BlockStart(kind = AssistantBlockKind.TOOL_CALL, index = 0))
                    response("已完成此前的任务。")
                },
                AgentRunController(),
                onUsage = usages::add,
            ).compact(jsonHistory(), 1, emptySet())
        }
        // 形状不合规的这一请求也已经花掉 token，所以必须先记账、再判失败。
        assertTrue("被判定为工具调用的尝试也要记账：$usages", usages.isNotEmpty())
        assertEquals(900, usages.first().inputTokens)
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "fixture",
        model = "fixture",
        systemPrompt = "固定约束",
        contextWindow = 128_000,
    )

    private fun jsonHistory() = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "固定规则"))
        .also { array ->
            repeat(3) { index ->
                array.put(AgentConversationCodec.userTextMessage("问题 $index"))
                array.put(JSONObject().put("role", "assistant").put("content", "事实 ".repeat(1000)))
            }
        }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = block(request, onEvent)
        }
}
