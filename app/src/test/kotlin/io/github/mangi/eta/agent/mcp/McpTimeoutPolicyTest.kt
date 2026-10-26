package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTimeoutPolicyTest {

    @Test
    fun mcpClientCarriesAWholeCallDeadline() {
        // 只设 read timeout 挡不住「每隔几十秒吐一个字节」的服务器。
        assertEquals(AgentHttpClient.MCP_CALL_TIMEOUT_MS, AgentHttpClient.mcpClient.callTimeoutMillis.toLong())
    }

    @Test
    fun socketAndCallTimeoutsCountAsTimeouts() {
        assertTrue(SocketTimeoutException("timeout").isMcpCallTimeout())
        assertTrue(InterruptedIOException("timeout").isMcpCallTimeout())
    }

    @Test
    fun wrappedTimeoutsStillCount() {
        assertTrue(IllegalStateException("wrapped", SocketTimeoutException("timeout")).isMcpCallTimeout())
    }

    @Test
    fun ordinaryFailuresAreNotTimeouts() {
        assertFalse(IOException("MCP 响应正文为空").isMcpCallTimeout())
        assertFalse(IllegalStateException("MCP 客户端已关闭").isMcpCallTimeout())
    }
}
