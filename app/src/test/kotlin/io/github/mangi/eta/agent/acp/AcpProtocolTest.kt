package io.github.mangi.eta.agent.acp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 请求应答的回归点。
 *
 * 核心不变量：**不支持的能力必须回 JSON-RPC error，绝不能伪造 result**。
 * 伪造 result 会让代理既不降级也不结束回合 —— 用户看到的是委派永久卡住。
 */
class AcpProtocolTest {

    @Test
    fun permissionRequestGetsResultWithOutcome() {
        val outcome = JSONObject().put("outcome", "selected").put("optionId", "allow-1")
        val reply = AcpProtocol.reply(7, AcpProtocol.PERMISSION_METHOD, outcome)

        assertEquals("2.0", reply.getString("jsonrpc"))
        assertEquals(7, reply.getInt("id"))
        assertFalse("权限应答不该带 error", reply.has("error"))
        val result = reply.getJSONObject("result")
        assertEquals("selected", result.getJSONObject("outcome").getString("outcome"))
    }

    @Test
    fun missingPermissionOutcomeStillProducesResultNotEmptyError() {
        // 代理没给选项时也不能抛：回空 outcome，相当于「取消」。
        val reply = AcpProtocol.reply(1, AcpProtocol.PERMISSION_METHOD, null)
        assertTrue(reply.has("result"))
        assertFalse(reply.has("error"))
    }

    @Test
    fun fileReadRequestIsRejectedWithMethodNotFound() {
        val reply = AcpProtocol.reply(3, "fs/read_text_file", JSONObject())

        assertFalse("绝不能给不支持的方法伪造 result", reply.has("result"))
        val error = reply.getJSONObject("error")
        assertEquals(AcpProtocol.METHOD_NOT_SUPPORTED, error.getInt("code"))
        assertTrue(error.getString("message").contains("fs/read_text_file"))
    }

    @Test
    fun terminalRequestIsAlsoRejected() {
        val reply = AcpProtocol.reply(4, "terminal/create", null)
        assertEquals(AcpProtocol.METHOD_NOT_SUPPORTED, reply.getJSONObject("error").getInt("code"))
    }

    /**
     * 真机实测（2026-10-27）：codex-acp 的权限请求 id 是 UUID 字符串，
     * 旧实现 optInt 取成 -1 就回了个 id:-1 的应答，代理匹配不上、永远等不到结论，
     * 表现是「说了一句『我先执行…』之后就再也不动」直到超时。
     */
    @Test
    fun permissionReplyEchoesStringIdVerbatim() {
        val uuid = "3e8676c5-73af-4504-b507-96166672a30f"
        val outcome = JSONObject().put("outcome", "selected").put("optionId", "approved")
        val reply = AcpProtocol.reply(uuid, AcpProtocol.PERMISSION_METHOD, outcome)

        assertEquals(uuid, reply.getString("id"))
        assertFalse("字符串 id 不能变成别的类型", reply.get("id") is Number)
        assertEquals(
            "approved",
            reply.getJSONObject("result").getJSONObject("outcome").getString("optionId"),
        )
    }

    @Test
    fun unsupportedReplyAlsoEchoesTheSameId() {
        val reply = AcpProtocol.reply("req-9", "terminal/create", null)
        assertEquals("req-9", reply.getString("id"))
        assertEquals(AcpProtocol.METHOD_NOT_SUPPORTED, reply.getJSONObject("error").getInt("code"))
    }
}
