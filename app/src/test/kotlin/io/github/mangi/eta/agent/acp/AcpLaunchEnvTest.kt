package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.agent.terminal.TerminalEnvEntry
import io.github.mangi.eta.agent.terminal.TerminalEnvRules
import io.github.mangi.eta.agent.terminal.TerminalEnvInjection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 启动环境的口径：**凭据不能以 `export KEY='value'` 进命令行**。
 *
 * 之前这串是直接拼进 `su -c` 的 argv 的，`ps` 就能读到 —— 与终端 env.conf 同一个缺陷
 * （见 docs §32.22）。这里守住「机密只走脚本、标记才内联」。
 */
class AcpLaunchEnvTest {
    private fun provider(apiKey: String) = AcpProvider.Config(
        id = "p1",
        name = "中转",
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        wireApi = "responses",
    )

    private fun tempDir(): File = File.createTempFile("eta-acp-env", "").apply { delete(); mkdirs() }

    @Test
    fun providerKeyIsSecretWhileSessionMarkerIsNot() {
        val env = acpLaunchEnv(
            sessionId = "acp123",
            provider = provider("sk-super-secret"),
            credentialEnvKeys = emptyList(),
        )
        assertEquals("ETA_ACP_SESSION", env.marker.key)
        assertEquals("acp123", env.marker.value)
        assertEquals(listOf(AcpProvider.ENV_KEY), env.secrets.map { it.key })
        assertEquals("sk-super-secret", env.secrets.single().value)
    }

    @Test
    fun credentialEnvKeysComeFromTheProcessAndBlanksAreSkipped() {
        val env = acpLaunchEnv(
            sessionId = "acp9",
            provider = null,
            credentialEnvKeys = listOf("OPENAI_API_KEY", "EMPTY_KEY", "MISSING_KEY"),
            env = { key ->
                when (key) {
                    "OPENAI_API_KEY" -> "sk-from-env"
                    "EMPTY_KEY" -> "   "
                    else -> null
                }
            },
        )
        assertTrue(env.secrets.isEmpty() == false)
        assertEquals(listOf("OPENAI_API_KEY"), env.secrets.map { it.key })
        assertEquals("sk-from-env", env.secrets.single().value)
    }

    @Test
    fun prefixOnlyCarriesTheScriptPathNeverTheValue() {
        val dir = tempDir()
        try {
            val secrets = listOf(TerminalEnvEntry("OPENAI_API_KEY", "sk-super-secret"))
            val script = requireNotNull(
                // 与 Linux 落点同构：guest 内 /tmp，目录不归我们独占。
                TerminalEnvInjection(dir, "/tmp", ownsDirectory = false).script(secrets),
            )
            val prefix = acpEnvPrefix(TerminalEnvEntry("ETA_ACP_SESSION", "acp1"), script)
            assertFalse(prefix, prefix.contains("sk-super-secret"))
            assertFalse(prefix, prefix.contains("OPENAI_API_KEY"))
            assertTrue(prefix, prefix.contains("/tmp/.eta-env-"))
            assertTrue(prefix, prefix.contains("ETA_ACP_SESSION"))
            assertFalse("前缀必须是单行", prefix.contains('\n'))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noSecretsMeansNoScriptFragment() {
        val prefix = acpEnvPrefix(TerminalEnvEntry("ETA_ACP_SESSION", "acp1"), null)
        assertEquals("export ETA_ACP_SESSION='acp1'; ", prefix)
    }

    @Test
    fun duplicateKeysKeepTheProviderValue() {
        // 脚本是按行 export 的，同名键后写会覆盖前写：provider 里刚改的值必须赢过环境里的旧值。
        val env = acpLaunchEnv(
            sessionId = "acp7",
            provider = provider("sk-from-settings"),
            credentialEnvKeys = listOf(AcpProvider.ENV_KEY, AcpProvider.ENV_KEY),
            env = { "sk-from-environment" },
        )
        assertEquals(1, env.secrets.size)
        assertEquals("sk-from-settings", env.secrets.single().value)
    }

    @Test
    fun unsafeValueStaysVisibleSoTheCallerCanRefuse() {
        // 不在这里静默过滤：否则「值含换行」会表现为「代理说没凭证」，查不到真正原因。
        // AcpClient 用 isInjectable 判定并报出具体哪一项，这里守住这个前提。
        val env = acpLaunchEnv(
            sessionId = "acp8",
            provider = provider("sk-bad\nnext"),
            credentialEnvKeys = emptyList(),
        )
        assertEquals("sk-bad\nnext", env.secrets.single().value)
        assertFalse(TerminalEnvRules.isInjectable(env.secrets.single()))
    }
}
