package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/**
 * 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。
 *
 * 等待时长由 [AgentRetryPolicy] 决定：指数退避 + 抖动，并在服务端给出
 * `Retry-After` 时以服务端要求为下限。
 *
 * 运行中热切换模型时，**尚未发出的重试**会立刻改用新配置（新请求体 + 新协议适配器）；
 * 已在飞行中的那次请求不被取消 —— 取消网络会丢掉已生成的 token。
 */
internal class AgentModelRetry(
    private val policy: AgentRetryPolicy = AgentRetryPolicy.fromSettings(null),
    private val random: () -> Double = Math::random,
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    /**
     * 一次待发请求的完整参数。
     *
     * 模型 / provider / 请求体必须是同一组：跨 provider 切换时只换模型名会把请求打到旧协议的
     * 端点上，所以重试时整组一起换。
     */
    data class Attempt(val request: ProviderRequest, val provider: AgentProviderClient)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
        /**
         * 重试前重新解析一次请求参数（模型已热切换时给出新的；没切换则与上次一致）。
         * 为 null 时沿用传入的 request / provider（纯单测路径）。
         */
        nextAttempt: ((Int) -> Attempt)? = null,
    ): Result {
        var round = initialRound
        var retries = 0
        var current = Attempt(request, provider)
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, current.request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            var retryAfterSeconds: Long? = null
            try {
                val response = current.provider.complete(current.request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    if (event is ProviderEvent.ResponseHeaders) retryAfterSeconds = event.retryAfterSeconds
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified,
                )
                if (!classified.retryable) throw classified
                if (!policy.canRetry(retries)) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 ${policy.maxRetries} 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val minimumDelayMs = if (classified.code in ORIGIN_GATEWAY_CODES) {
                    ORIGIN_GATEWAY_MIN_DELAY_MS
                } else {
                    0L
                }
                val delayMs = policy.delayMs(
                    retries,
                    retryAfterSeconds,
                    random(),
                    minimumDelayMs = minimumDelayMs,
                )
                onEvent(
                    AgentEvent.ModelRetryScheduled(
                        round = round,
                        attempt = retries,
                        maxAttempts = policy.maxRetries,
                        delayMs = delayMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        reasonCode = classified.code,
                    ),
                )
                if (classified.code == AgentModelFailure.CODE_TLS_INTERRUPTED ||
                    classified.code == AgentModelFailure.CODE_PROTOCOL_INTERRUPTED
                ) {
                    // 连接级损坏：换新连接再试，否则会一直在坏会话上重试。
                    AgentHttpClient.evictPooledConnections()
                }
                // 等待退避；若期间模型被热切换，这里会被提前唤醒（无需把剩余退避等完），
                // 下一次请求由下面的 nextAttempt 重新取最新配置来发。
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
                // 重试前重新解析一次：切换过就整组换成新模型，没切换则与上次一致。
                nextAttempt?.invoke(round)?.let { attempt ->
                    current = attempt
                }
            }
        }
    }

    private companion object {
        /** Cloudflare 回源错误：给源站更长的恢复窗口，避免 2 秒后立刻重撞。 */
        val ORIGIN_GATEWAY_CODES = setOf(
            "HTTP_520", "HTTP_521", "HTTP_522", "HTTP_523", "HTTP_524", "HTTP_530",
        )
        const val ORIGIN_GATEWAY_MIN_DELAY_MS = 5_000L
    }
}
