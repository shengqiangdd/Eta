package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AssistantStopReason
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.selectedOrFirstModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务商「真正发一条消息」的验证。
 *
 * 模型目录能拉取只说明鉴权头能过、模型列表接口通；聊天走的是另一个端点、
 * 另一套请求体与流式协议，所以目录成功不代表能发送。这里发一条最小请求，
 * 把「能聊」和「能列模型」分开验证。
 */
internal object ProviderSendProbe {
    data class Result(
        val model: String,
        val reply: String,
        val latencyMs: Long,
    )

    suspend fun send(provider: ProviderSetting): Result {
        require(provider.id.isNotBlank()) { "请先保存服务商，再验证发送" }
        val model = provider.selectedOrFirstModel(null)
            ?: throw IllegalStateException("该服务商没有已启用的模型，先在模型列表里添加或启用一个")
        // 只验证聊天通道：不注入系统提示词、关闭联网 / 自定义请求体，
        // 避免把「服务商支持这些实验特性」误当成发送能力的一部分。
        //
        // 思考档位**不能固定写 OFF**：真机（2026-11-09，apigoto/deepseek-flash）
        // 就是这样失败在「当前模型不支持 Off thinking effort」—— 这类模型强制思考，
        // OFF 不在可选集合里，本地校验直接抛错，请求根本没发出去。
        // 复用摘要请求的取档规则：能关就关，否则取可选的最低显式档，
        // 既保持探测请求足够便宜，又保证是模型真正接受的档位。
        val baseConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val effort = ProviderRequest.summaryReasoningEffort(baseConfig)
        val config = baseConfig.copy(
            systemPrompt = "",
            hostedWebSearchEnabled = false,
            extraBodyJson = "",
            customBody = emptyList(),
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            modelMaxRetries = 0,
        )
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", "这是一次连接验证。不要调用任何工具，只回复两个字母：OK"),
        )
        val request = ProviderRequest(
            config = config,
            messages = messages,
            tools = JSONArray(),
            purpose = ProviderRequestPurpose.CHAT,
        )
        val client: AgentProviderClient = ProviderClientFactory.getClient(config)
        val startedAt = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val response = try {
                client.complete(request, AgentRunController())
            } catch (failure: AgentModelFailure) {
                throw failure
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                throw IllegalStateException(
                    throwable.message ?: throwable.javaClass.simpleName,
                    throwable,
                )
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val reply = response.assistantMessage.optString("content").trim()
            check(reply.isNotBlank()) { "模型返回了空内容（HTTP 已成功，但没有文本）" }
            check(
                response.stopReason != AssistantStopReason.CONTENT_FILTER,
            ) { "模型返回被内容过滤；换一个模型再试" }
            Result(model = model.modelId, reply = reply, latencyMs = elapsed)
        }
    }
}