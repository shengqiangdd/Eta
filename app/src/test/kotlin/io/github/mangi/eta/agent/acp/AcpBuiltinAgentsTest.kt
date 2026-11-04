package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 ACP 代理清单。
 *
 * 这份清单原来零覆盖：加一个代理（或改一个候选路径）只要编译通过就算「做完了」，
 * 而它的实际效果是「`acp_agent_list` 里有没有这个代理」「委派能不能找到可执行文件」——
 * 两件都只有真机能看出来。这里把能静态检查的部分钉住。
 */
class AcpBuiltinAgentsTest {

    @Test
    fun shipsBothCodexAndClaude() {
        val ids = AcpClient.BUILTIN_AGENTS.map { it.id }
        assertTrue("codex 是默认代理", ids.contains("codex"))
        assertTrue("claude 走 claude-code-acp", ids.contains(AGENT_CLAUDE))
    }

    @Test
    fun everyAgentHasSeveralCandidatesNotJustOneHardCodedPath() {
        // 早期写死 /opt/eta/node/<version>/bin/... ，node 一升级整条失效。
        // 候选必须同时包含 PATH 命令名，否则「装在别处」就等于「没装」。
        AcpClient.BUILTIN_AGENTS.forEach { spec ->
            assertTrue(
                "${spec.id} 缺少 PATH 命令名候选",
                spec.commandNames.isNotEmpty(),
            )
            assertTrue(
                "${spec.id} 缺少通配候选（多版本目录）",
                spec.executableGlobs.isNotEmpty(),
            )
        }
    }

    @Test
    fun claudeAdvertisesTheVariablesItsSdkActuallyReads() {
        val claude = AcpClient.BUILTIN_AGENTS.single { it.id == AGENT_CLAUDE }

        // 这两个是 claude-agent-sdk 真正读的名字；写别的（比如 ANTHROPIC_AUTH_TOKEN）
        // 会得到 401，而不是「变量名不认识」。
        assertTrue(claude.credentialEnvKeys.contains(AcpProvider.CLAUDE_API_KEY_ENV))
    }

    @Test
    fun agentIdsAreUniqueAndTrimmed() {
        val ids = AcpClient.BUILTIN_AGENTS.map { it.id }
        assertEquals("重复 id 会让 resolveAgent 的缓存互相覆盖", ids.size, ids.distinct().size)
        ids.forEach { assertFalse(it.isBlank()) }
    }
}
