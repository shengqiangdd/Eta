package io.github.mangi.eta.agent.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val compactionClient: OkHttpClient by lazy {
        modelClient.newBuilder()
            .callTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 丢弃连接池中的空闲连接。
     *
     * TLS 记录层被中断后（BAD_DECRYPT 等），继续复用同一批连接会不断复现；
     * 重试前清空连接池可以保证下一次请求重新握手。
     */
    fun evictPooledConnections() {
        runCatching { modelClient.connectionPool.evictAll() }
    }

    fun modelClient(purpose: ProviderRequestPurpose): OkHttpClient =
        if (purpose == ProviderRequestPurpose.COMPACTION) compactionClient else modelClient

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * MCP 工具调用的专用客户端：除单次读写等待外再加一个整体调用上限。
     *
     * 只设 read timeout 是不够的 —— 服务器可以每隔几十秒吐一个字节，一次工具调用就会被拖很久，
     * 期间 Agent 只能干等。整体上限保证「服务器不响应」最终以超时结束，而不是把一次 run 拖死。
     * 这个上限只加在 MCP 上：本地工具各自的超时是精确到语义的（terminal 30 秒、浏览器导航等），
     * 在外面再包一层不仅重复，还会让真正需要长时间运行的工具（如后台任务）失去能力。
     */
    val mcpClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(MCP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** MCP 服务器在 2 分钟内没有任何完整响应就判定为卡死：正常工具调用远达不到这个上限。 */
    const val MCP_CALL_TIMEOUT_MS = 120_000L
}
