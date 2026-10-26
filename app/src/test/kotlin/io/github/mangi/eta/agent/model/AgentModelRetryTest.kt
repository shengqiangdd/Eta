package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        // 抖动比例 20%：测试必须注入确定随机数，否则断言精确毫秒会偶发失败。
        val retry = AgentModelRetry(
            policy = AgentRetryPolicy(maxRetries = 3),
            random = { 0.5 },
        ) { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun retriesTlsRecordFailureWithFreshConnection() {
        val delays = mutableListOf<Long>()
        var calls = 0
        val result = complete(AgentModelRetry(random = { 0.5 }) { _, delay -> delays += delay }, provider { _, _ ->
            if (calls++ == 0) {
                throw SSLException("Read error: ssl=0x7f: Failure in SSL library; OPENSSL_internal:BAD_DECRYPT")
            }
            response()
        })
        assertEquals(2, result.round)
        assertEquals(2, calls)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun cloudflareGatewayErrorsUseTheFiveSecondFloorAndRecover() {
        val delays = mutableListOf<Long>()
        var calls = 0
        val result = complete(
            AgentModelRetry(random = { 0.5 }) { _, delay -> delays += delay },
            provider { _, _ ->
                if (calls++ < 2) throw AgentModelFailure.http(522, "")
                response()
            },
        )
        assertEquals(3, result.round)
        assertEquals(3, calls)
        assertEquals(listOf(5_000L, 5_000L), delays)
    }

    @Test
    fun retryAfterModelSwitchUsesTheNewModelAndProvider() {
        val calls = mutableListOf<String>()
        // 首次尝试用“旧模型”失败；第二家用“新模型”成功。
        val oldProvider = provider { request, _ ->
            calls += "old:${request.effectiveConfig.model}"
            throw SocketTimeoutException("timeout")
        }
        val newProvider = provider { request, _ ->
            calls += "new:${request.effectiveConfig.model}"
            response()
        }
        val result = AgentModelRetry(
            policy = AgentRetryPolicy(maxRetries = 3),
            random = { 0.5 },
        ) { _, _ -> }
            .complete(
                initialRound = 1,
                request = ProviderRequest(config("old-model"), JSONArray(), JSONArray()),
                provider = oldProvider,
                controller = AgentRunController(),
                onEvent = {},
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
                nextAttempt = { attemptRound ->
                    assertEquals(2, attemptRound)
                    AgentModelRetry.Attempt(
                        request = ProviderRequest(config("new-model"), JSONArray(), JSONArray()),
                        provider = newProvider,
                    )
                },
            )

        assertEquals(2, result.round)
        // 关键：第 2 次请求换了模型和适配器，而不是拿旧配置重试。
        assertEquals(listOf("old:old-model", "new:new-model"), calls)
    }

    @Test
    fun withoutNextAttemptTheSameRequestIsReused() {
        var calls = 0
        val result = complete(
            AgentModelRetry { _, _ -> },
            provider { _, _ ->
                if (calls++ == 0) throw SocketTimeoutException()
                response()
            },
        )
        assertEquals(2, result.round)
        assertEquals(2, calls)
    }

    private fun config(model: String) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        model = model,
        systemPrompt = "",
    )

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 520, 521, 522, 523, 529, 530)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404, 525, 526)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        // Conscrypt 的连接级 TLS 失败（BAD_DECRYPT / SSL_PROTOCOL_ERROR）必须可重试，
        // 否则一条失效会话会让整轮对话直接失败。
        val tls = AgentModelFailure.transport(
            SSLException("Read error: ssl=0x7f: Failure in SSL library; OPENSSL_internal:BAD_DECRYPT")
        )
        assertTrue(tls!!.retryable)
        assertEquals(AgentModelFailure.CODE_TLS_INTERRUPTED, tls.code)
        val protocol = AgentModelFailure.transport(java.net.ProtocolException("unexpected end of stream"))
        assertTrue(protocol!!.retryable)
        assertEquals(AgentModelFailure.CODE_PROTOCOL_INTERRUPTED, protocol.code)
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = {},
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
