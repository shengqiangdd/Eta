package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController
import org.json.JSONObject

/**
 * 节点动作失败后「要不要自动重试、重试哪一个节点」的判定。
 *
 * 抽成独立对象有两个理由：
 * 1. 判定错了会**点错位置**，比失败更糟，所以每条规则都要有测试守着；
 * 2. 这两条规则与无障碍实现无关，是纯逻辑，没必要挂在巨大的 AgentLocalTools 上。
 */
internal object NodeActionRetryPolicy {

    /**
     * 快照过期类失败：重新观察一次有意义。
     *
     * 其余失败码（NODE_NOT_ACTIONABLE、INVALID_NODE_INDEX、参数错）重试只会把
     * 同一个错误再说一遍，白白多一次点击尝试。
     */
    private val RETRYABLE = setOf(
        "STALE_CONTENT",
        "STALE_WINDOW",
        "STALE_NODE",
        "IDENTITY_CHANGED",
        "SERVICE_RECONNECTED",
    )

    /**
     * 判定「是不是原来那个节点」所需的最小字段集。
     *
     * 刻意不用 [RootShellDeviceController.UiNode]：它的 `bounds` 是 `android.graphics.Rect`，
     * 直接拿它做参数会让这条最容易点错的判定**只能靠 Robolectric 测**——
     * 而本机（aarch64）加载不了 Robolectric 的 native runtime，6 个用例长期只是「能编译」。
     * 换成纯数据后，同一批用例在任何环境都真的会跑（2026-10-25）。
     */
    data class NodeSignature(
        val index: Int,
        val nodeId: String,
        val className: String,
        val text: String,
        val desc: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** 把无障碍节点收敛成可比较的签名。 */
    fun signatureOf(node: RootShellDeviceController.UiNode): NodeSignature = NodeSignature(
        index = node.index,
        nodeId = node.nodeId,
        className = node.className,
        text = node.text,
        desc = node.desc,
        left = node.bounds.left,
        top = node.bounds.top,
        right = node.bounds.right,
        bottom = node.bounds.bottom,
    )

    /** 失败结果里的 code；成功、不是过期类失败、或不是合法 JSON 时返回 null。 */
    fun staleFailureCode(result: String): String? = runCatching { JSONObject(result) }
        .getOrNull()
        ?.takeIf { !it.optBoolean("ok", false) }
        ?.optString("code")
        ?.trim()
        ?.takeIf { it in RETRYABLE }

    /**
     * 在新快照里找唯一对应的节点（纯逻辑入口，测试直接用它）。
     *
     * `node_id` 是系统分配的稳定标识（同一个控件两次观察不会变），优先用它；
     * 拿不到（uiautomator 回退路径为空）时退回「类名 + 文本 + 描述 + bounds」精确匹配。
     *
     * 找不到或不唯一一律返回 null：宁可不点，也不能点错位置。
     */
    fun matchUniqueNode(nodes: List<NodeSignature>, target: NodeSignature): NodeSignature? {
        if (target.nodeId.isNotEmpty()) {
            nodes.filter { it.nodeId == target.nodeId }.singleOrNull()?.let { return it }
        }
        return nodes.filter { candidate ->
            candidate.className == target.className &&
                candidate.text == target.text &&
                candidate.desc == target.desc &&
                candidate.left == target.left &&
                candidate.top == target.top &&
                candidate.right == target.right &&
                candidate.bottom == target.bottom
        }.singleOrNull()
    }

    /** 供调用方直接拿回原来的 `UiNode`：判定仍走 [matchUniqueNode] 的纯数据实现。 */
    fun matchUniqueNode(
        nodes: List<RootShellDeviceController.UiNode>,
        target: RootShellDeviceController.UiNode,
    ): RootShellDeviceController.UiNode? {
        val signatures = nodes.map(::signatureOf)
        val matched = matchUniqueNode(signatures, signatureOf(target)) ?: return null
        val position = signatures.indexOfFirst { it === matched }
        return if (position >= 0) nodes[position] else null
    }
}
