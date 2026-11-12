package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 委派记录与凭据口径。
 *
 * 两件事必须钉住：
 * 1. 结果里的正文是有上限的，**完整原文必须落到文件**，且文件头要写明被截断了多少。
 *    否则用户看到一截断尾，唯一的选择是重新委派一遍。
 * 2. Claude 与 codex 的凭据变量名完全不同。混用的后果是一个 401，
 *    而代理不会告诉你「你把 key 塞进了错误的变量」。
 */
class AcpTranscriptTest {

    private val sample = AcpTranscript(
        sessionId = "acp1234567890abcdef",
        agentId = "codex",
        model = "gpt-6-sol",
        task = "把 build 修好",
        startedAtMs = 1_700_000_000_000,
        elapsedMs = 90_500,
        text = "修好了。",
        tools = "→ 读取 build.gradle\n",
        stderrTail = "",
        stopReason = "end_turn",
        ok = true,
        textTruncatedInResult = false,
    )

    @Test
    fun transcriptCarriesWhatTheTruncatedResultCannot() {
        val rendered = AcpTranscriptStore.render(sample)

        assertTrue("正文必须原样在里面", rendered.contains("修好了。"))
        assertTrue("任务原文要留着，否则回看时不知道让它干了什么", rendered.contains("把 build 修好"))
        assertTrue("工具流水要留着", rendered.contains("读取 build.gradle"))
        assertTrue(rendered.contains("gpt-6-sol"))
    }

    @Test
    fun truncatedResultIsCalledOutInTheFileHeader() {
        // 结果卡上只有一截尾巴时，最容易的误判是「代理就回了这么点」。
        val rendered = AcpTranscriptStore.render(sample.copy(textTruncatedInResult = true))

        assertTrue(rendered.contains("被截断了"))
        assertTrue("要给出省下的字数依据，让人知道全文在文件里", rendered.contains("12,000"))
    }

    @Test
    fun untruncatedResultDoesNotClaimToBeTruncated() {
        val rendered = AcpTranscriptStore.render(sample)

        assertFalse("没截断却写「被截断」会让用户去找一个不存在的全文", rendered.contains("被截断了"))
    }

    @Test
    fun claudeAndCodexUseDifferentCredentialVariables() {
        // 这条不是形式主义：写错变量得到的是 401，而不是「你配错了」。
        val claude = acpLaunchEnv(
            sessionId = "s1",
            provider = AcpProvider.Config(
                id = "p",
                name = "P",
                baseUrl = "https://example.test/v1",
                apiKey = "sk-test",
                wireApi = AcpProvider.CODEX_WIRE_API,
            ),
            credentialEnvKeys = emptyList(),
            env = { null },
            claudeStyle = true,
        )
        val keys = claude.secrets.map { it.key }
        assertTrue(keys.contains(AcpProvider.CLAUDE_API_KEY_ENV))
        assertTrue("Claude 必须同时被告知 base_url，否则会去连官方端点", keys.contains(AcpProvider.CLAUDE_BASE_URL_ENV))
        assertFalse("不能把 codex 的变量名也塞过去", keys.contains(AcpProvider.ENV_KEY))
    }

    @Test
    fun codexStillUsesItsOwnEnvKey() {
        val codex = acpLaunchEnv(
            sessionId = "s2",
            provider = AcpProvider.Config(
                id = "p",
                name = "P",
                baseUrl = "https://example.test/v1",
                apiKey = "sk-test",
                wireApi = AcpProvider.CODEX_WIRE_API,
            ),
            credentialEnvKeys = emptyList(),
            env = { null },
        )
        val keys = codex.secrets.map { it.key }
        assertTrue(keys.contains(AcpProvider.ENV_KEY))
        assertFalse("codex 不看 ANTHROPIC_*，多写只会让人以为「两个都配了才生效」", keys.contains(AcpProvider.CLAUDE_API_KEY_ENV))
    }
}
