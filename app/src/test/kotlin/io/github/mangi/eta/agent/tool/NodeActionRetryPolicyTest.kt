package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动重试的「该不该重试」判定。
 *
 * 这里判宽了会去点一个已经变了的界面（点错位置比失败更糟），判窄了白丢一次自救机会，
 * 所以每个失败码都单独立一条。
 */
class NodeActionRetryPolicyTest {

    @Test fun staleFailuresAreRetried() {
        listOf(
            "STALE_CONTENT",
            "STALE_WINDOW",
            "STALE_NODE",
            "IDENTITY_CHANGED",
            "SERVICE_RECONNECTED",
        ).forEach { code ->
            assertEquals(code, code, NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":"$code"}"""))
        }
    }

    @Test fun nodeNotActionableIsNotRetried() {
        // 节点确实不可见/不可用：重新观察一遍还是同一个结果，重试只会多一次无用的点击尝试。
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":"NODE_NOT_ACTIONABLE"}"""))
    }

    @Test fun badIndexAndArgumentErrorsAreNotRetried() {
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":"INVALID_NODE_INDEX"}"""))
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":"INVALID_ARGUMENT"}"""))
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":"ACCESSIBILITY_UNAVAILABLE"}"""))
    }

    @Test fun successNeverTriggersARetry() {
        // 即使 code 字段恰好是过期类（理论上不该出现），ok=true 也必须原样返回。
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":true,"code":"STALE_CONTENT"}"""))
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":true}"""))
    }

    @Test fun nonJsonAndEmptyPayloadsAreIgnored() {
        assertNull(NodeActionRetryPolicy.staleFailureCode("not json"))
        assertNull(NodeActionRetryPolicy.staleFailureCode(""))
        assertNull(NodeActionRetryPolicy.staleFailureCode("""{"ok":false}"""))
    }

    @Test fun codeIsTrimmedBeforeMatching() {
        assertEquals(
            "STALE_CONTENT",
            NodeActionRetryPolicy.staleFailureCode("""{"ok":false,"code":" STALE_CONTENT "}"""),
        )
    }
}
