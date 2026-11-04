package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「代理实际可用吗」的判定。
 *
 * 回归一次真实缺陷（2026-11-04）：本机装了 claude-code-acp、配好了 sq6 提供商
 * （含 claude-sonnet-5 等模型），`acp_agent_list` 却报 `credential_present:false`
 * 与「缺少供应商凭证」。原因是可用性判定只在 codex 分支上考虑了提供商，
 * Claude 被漏掉 —— 而启动路径里 Claude 明明会把 base_url/key 塞进 `ANTHROPIC_*`。
 *
 * 后果不是「显示不好看」：模型看到 `credential_present:false` 会按提示放弃委派
 * 并告诉用户「缺凭据」，这个能力就等于对用户关闭了。
 */
class AcpCredentialUsableTest {

    @Test
    fun codexCanBorrowTheProvider() {
        assertTrue(
            acpCredentialUsable("codex", ownCredentialPresent = false, providerReady = true),
        )
    }

    @Test
    fun claudeCanBorrowTheProviderToo() {
        // 就是这条曾经错：Claude 与 codex 接法不同（环境变量 vs config.toml），
        // 但「能不能用提供商」这件事是一样的。
        assertTrue(
            acpCredentialUsable("claude", ownCredentialPresent = false, providerReady = true),
        )
    }

    @Test
    fun ownCredentialWinsEvenWithoutProvider() {
        assertTrue(
            acpCredentialUsable("claude", ownCredentialPresent = true, providerReady = false),
        )
    }

    @Test
    fun providerDoesNotMakeAnUnknownAgentUsable() {
        // 未来加了第三个代理（比如 gemini）：没为它实现提供商注入之前，
        // 不能因为「有别的提供商可用」就把它报成可用 —— 那会让委派必然失败。
        assertFalse(
            acpCredentialUsable("gemini", ownCredentialPresent = false, providerReady = true),
        )
    }

    @Test
    fun neitherCredentialNorProviderMeansUnusable() {
        listOf("codex", "claude").forEach { id ->
            assertFalse(
                "$id 既无凭据也无提供商时不能报可用",
                acpCredentialUsable(id, ownCredentialPresent = false, providerReady = false),
            )
        }
    }
}
