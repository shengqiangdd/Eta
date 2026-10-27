package io.github.mangi.eta.agent.acp

import org.json.JSONObject

/**
 * ACP 请求应答的构造（纯逻辑，可单测）。
 *
 * 为什么单独抽出来：代理发来的 request 只有两类应答形态 ——
 * 权限请求回 `result`，**客户端不支持的能力必须回 JSON-RPC error**。
 *
 * 旧实现给所有方法都回 `{"result":{"outcome":{}}}`：
 * 对 `session/request_permission` 合法，对 `fs/read_text_file` 却是畸形响应
 * （缺 `content` 字段）。代理侧解析失败后既不会降级、也不会结束回合 ——
 * 真机现象就是「acp_delegate 一直卡着到超时」，而日志里没有任何错误。
 *
 * ACP 规范里客户端能力是可选的：回 -32601（Method not found）代理就知道
 * 这条路不可用，会改用自己进程内的实现。所以「不支持」必须说出来，而不是假装成功。
 */
internal object AcpProtocol {

    /** JSON-RPC 2.0 的「方法不存在」错误码。 */
    const val METHOD_NOT_SUPPORTED = -32601

    /** 唯一有明确应答结构的客户端方法。 */
    const val PERMISSION_METHOD = "session/request_permission"

    /**
     * id 原样回声；缺失时就写 JSON null（规范要求应答必须带 id）。
     *
     * 不能统一当数字：真机实测 codex-acp 的 `session/request_permission` 用 UUID 字符串当 id，
     * 旧代码 optInt 得到 -1，回了个 id:-1 的应答 —— 代理匹配不上，永远等不到权限结果，
     * 于是「说了一句『我先执行…』之后再也不动」，直到委派超时。
     */
    private fun idValue(replyId: Any?): Any = replyId ?: JSONObject.NULL

    fun permissionReply(replyId: Any?, outcome: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idValue(replyId))
            .put("result", JSONObject().put("outcome", outcome))

    fun unsupportedReply(replyId: Any?, method: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idValue(replyId))
            .put(
                "error",
                JSONObject()
                    .put("code", METHOD_NOT_SUPPORTED)
                    .put("message", "eta: method not supported: $method"),
            )

    /**
     * 统一应答入口。
     *
     * @param permissionOutcome 只在 [PERMISSION_METHOD] 时使用；为 null 时回空对象，
     *   让代理按「取消」处理而不是收到 null 崩掉。
     * @param replyId 代理请求里的 id，原样传回（可能是数字，也可能是 UUID 字符串）。
     */
    fun reply(replyId: Any?, method: String, permissionOutcome: JSONObject?): JSONObject =
        if (method == PERMISSION_METHOD) {
            permissionReply(replyId, permissionOutcome ?: JSONObject())
        } else {
            unsupportedReply(replyId, method)
        }
}
