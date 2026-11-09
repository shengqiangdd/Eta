package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    /**
     * 这次请求**不能重放**：已经开始的托管工具（服务端搜索等）可能已产生副作用 /
     * 已经计费，再发一遍就是重复执行。由 AgentModelRetry 在归类时标出。
     */
    val replayUnsafe: Boolean = false,
) : IllegalStateException(message, cause) {
    /**
     * 端点明确回报上下文容量不足：**唯一**一种「压缩之后可以原地重试」的失败。
     *
     * 其余失败重试只会重复犯错，所以只有这一类会回到循环顶部重建请求
     * （见 AgentLoop 的溢出救援分支）。
     */
    fun isContextOverflow(): Boolean = code == CODE_CONTEXT_OVERFLOW

    companion object {
        const val CODE_CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"
        const val CODE_TLS_INTERRUPTED = "MODEL_TLS_INTERRUPTED"
        const val CODE_PROTOCOL_INTERRUPTED = "MODEL_PROTOCOL_INTERRUPTED"

        private val transientStatus = setOf(
            408, 429, 500, 502, 503, 504,
            // Cloudflare 回源错误：520 未知错误、521 源站不可用、522 回源超时、523 源站不可达、
            // 524 回源超时（官方）、530 源站 DNS 失败。它们是暂时性的，重试通常有效。
            520, 521, 522, 523, 524, 530, 529,
        )
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
        )

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun http(status: Int, body: String, includeParameterDetails: Boolean = false): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CODE_CONTEXT_OVERFLOW, false, "模型上下文超过容量限制。",
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "模型请求参数无效（HTTP 400；$details），请检查模型配置。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    520, 521, 522, 523, 524, 530 ->
                        "模型服务商网关暂时不可用（HTTP $status，常见于 Cloudflare 回源超时/源站不可达）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CODE_CONTEXT_OVERFLOW, false, "模型上下文超过容量限制。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            // 证书/握手失败属于配置问题，重试也不会成功：保持原样抛出。
            is SSLHandshakeException, is SSLPeerUnverifiedException -> null
            // TLS 记录层失败（Conscrypt 报 OPENSSL_internal:BAD_DECRYPT、SSL_PROTOCOL_ERROR 等）
            // 几乎都是连接池里复用了已被对端或中间设备断开的会话：换一条新连接即可成功。
            is SSLException -> AgentModelFailure(
                CODE_TLS_INTERRUPTED, true,
                "与模型服务的加密连接被中断（TLS 会话已失效，常见于代理/VPN 或服务端空闲断连），正在重试。",
                failure,
            )
            // 连接被中途重置（HTTP/2 流复位、响应截断）同理，属于可重试的传输问题。
            is ProtocolException -> AgentModelFailure(
                CODE_PROTOCOL_INTERRUPTED, true,
                "与模型服务的连接被中途重置，正在重试。",
                failure,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds") ||
                message.contains("input token count") && message.contains("exceeds the maximum")
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "模型接口认证失败，请检查 API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
