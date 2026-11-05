package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.agent.acp.AcpClient.Companion.BUILTIN_AGENTS
import io.github.mangi.eta.agent.acp.AcpClient.Companion.credentialMissingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「缺凭据」提示文案。
 *
 * 为什么值得单测：这段话对用户的唯一价值就是「告诉我下一步怎么做」。
 * 曾经的写法对所有代理统一说「完成一次代理登录（例如 codex login）」——
 * 而 guest 里根本没有 claude CLI，Claude 用户照着做只会得到 command not found。
 * 指错路比不给路更糟，所以把「每个代理说自己的话」钉成不变量。
 */
class AcpCredentialMessageTest {
    @Test
    fun codexMentionsItsOwnLoginCommand() {
        val message = credentialMissingMessage(
            envKeys = listOf("CODEX_API_KEY", "OPENAI_API_KEY"),
            loginHint = "跑一次 codex login",
        )

        assertTrue(message.contains("CODEX_API_KEY"))
        assertTrue(message.contains("OPENAI_API_KEY"))
        assertTrue(message.contains("codex login"))
    }

    @Test
    fun claudeDoesNotTellTheUserToRunACliThatIsNotInstalled() {
        // 这是这条规则的核心：claude CLI 在 guest 里不存在，
        // 任何形式的「claude login」都是把用户指向一条走不通的路。
        val message = credentialMissingMessage(
            envKeys = listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"),
            loginHint = "",
        )

        assertTrue(message.contains("ANTHROPIC_API_KEY"))
        assertFalse("不该出现 claude login，guest 里没有 claude CLI", message.contains("claude login"))
        assertFalse("不该把 codex 的做法塞给 claude", message.contains("codex"))
    }

    @Test
    fun builtinClaudeSpecCarriesNoCliLoginHint() {
        // 上一个测试只验证了「传入空 hint 时文案不跑偏」，
        // 真正会退化的是**配置本身**：有人把 claude 的 loginHint 换成 claude login，
        // 纯函数测试照样全绿。所以这里直接钉住 BUILTIN_AGENTS 里的实际值。
        val claude = BUILTIN_AGENTS.first { it.id == "claude" }

        assertTrue(
            "claude CLI 在 guest 里不存在，loginHint 不能提它：${claude.loginHint}",
            claude.loginHint.isBlank(),
        )
    }

    @Test
    fun builtinCodexSpecPointsAtItsRealLoginCommand() {
        val codex = BUILTIN_AGENTS.first { it.id == "codex" }

        // codex CLI 确实在 guest 里（/opt/eta/node/*/bin/codex），`codex login` 可跑。
        assertTrue("codex 该给出它自己的登录命令", codex.loginHint.contains("codex login"))
    }

    @Test
    fun builtinAgentsKeepTheirOwnCredentialPathsSeparate() {
        // 凭据文件不能串用：曾把 Claude 的 /root/.claude.json（普通配置文件）
        // 当成登录态，导致“装了但没登录”被报成可用。
        val codex = BUILTIN_AGENTS.first { it.id == "codex" }
        val claude = BUILTIN_AGENTS.first { it.id == "claude" }

        assertTrue(codex.credentialPaths.any { it.contains(".codex") })
        assertTrue(claude.credentialPaths.any { it.endsWith(".credentials.json") })
        assertFalse(
            "Claude 的凭据路径不该包含普通配置文件 ~/.claude.json",
            claude.credentialPaths.any { it.endsWith("/.claude.json") },
        )
    }

    @Test
    fun emptyHintLeavesNoDanglingSeparator() {
        // 没有登录指引时不能留下「，或」这种断句。
        val message = credentialMissingMessage(envKeys = listOf("SOME_KEY"), loginHint = "")

        assertTrue(message.endsWith("，然后重试"))
        assertFalse(message.contains("或在 Linux 环境里，"))
    }

    @Test
    fun multipleEnvKeysAreJoinedForReading() {
        val message = credentialMissingMessage(
            envKeys = listOf("A_KEY", "B_KEY", "C_KEY"),
            loginHint = "",
        )

        assertTrue(message.contains("A_KEY 或 B_KEY 或 C_KEY"))
    }

    @Test
    fun hintIsAppendedAfterEnvKeys() {
        val message = credentialMissingMessage(
            envKeys = listOf("X_KEY"),
            loginHint = "做点什么",
        )

        val envAt = message.indexOf("X_KEY")
        val hintAt = message.indexOf("做点什么")
        assertTrue("环境变量应排在做法之前", envAt in 0..<hintAt)
        assertEquals("，然后重试", message.substring(message.length - 5))
    }
}
