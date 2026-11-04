package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.acp.AcpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页的代理选项与内置代理清单必须对得上。
 *
 * 两边各写一份是必然的（一边要可执行路径，一边要展示文案），但**漏改**的后果是
 * 用户可见的：界面里能选一个不存在的代理，或者装了新代理却在设置页找不到它。
 * 这里把「两份清单一致」钉成断言，加代理时忘了同步会直接变红。
 */
class AcpDelegationAgentsTest {

    @Test
    fun uiListMatchesBuiltinAgentIds() {
        val mismatched = AcpDelegationAgents.mismatchedWithBuiltin(
            AcpClient.BUILTIN_AGENTS.map { it.id },
        )
        assertEquals("界面选项与内置代理清单不一致：$mismatched", emptyList<String>(), mismatched)
    }

    @Test
    fun everyEntryIsSelectableAndDistinct() {
        val ids = AcpDelegationAgents.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertTrue("代理 id 不能为空", it.isNotBlank()) }
    }

    @Test
    fun mismatchDetectorActuallyDetects() {
        // 反向验证：这个探查函数本身不能总是返回空 —— 否则上面那条断言永不失败。
        assertEquals(
            emptyList<String>(),
            AcpDelegationAgents.mismatchedWithBuiltin(listOf("codex", "claude")),
        )
        assertEquals(
            listOf("codex"),
            AcpDelegationAgents.mismatchedWithBuiltin(listOf("claude")),
        )
    }
}
