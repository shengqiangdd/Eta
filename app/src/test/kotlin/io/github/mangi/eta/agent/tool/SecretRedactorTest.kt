package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    /** 打码后的固定标记；单独提出来，避免测试里到处硬编码这一段。 */
    private val mask = SecretRedactor.MASK_FOR_TEST

    @Test
    fun redactsAssignmentsAndBearerTokens() {
        val input = """
            API_KEY=abcdefgh12345
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9abcdefgh
            password: hunter2hunter2
        """.trimIndent()

        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(output.contains("hunter2hunter2"))
        assertTrue(output.contains("API_KEY=$mask"))
        assertTrue(output.contains("Bearer $mask"))
        assertTrue(output.contains("password: $mask"))
    }

    @Test
    fun redactsJsonCredentialsWithoutBreakingJson() {
        val input = """{"api_key":"abcdefgh12345","model":"x","password":"hunter2hunter2"}"""

        val output = SecretRedactor.redact(input)
        val json = JSONObject(output)

        assertEquals(mask, json.getString("api_key"))
        assertEquals(mask, json.getString("password"))
        assertEquals("x", json.getString("model"))
    }

    @Test
    fun redactsKeyGluedAfterUrl() {
        val input = "https://api.example.com/v1?k=sk-vnnE3ftPLACEHOLDER1234"

        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("sk-vnnE3ftPLACEHOLDER1234"))
        assertTrue(output.startsWith("https://api.example.com/v1"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val input = "token bucket 与 password policy 都是普通词；secret sauce 不算凭据。"
        assertEquals(input, SecretRedactor.redact(input))
    }

    @Test
    fun leavesShortAssignmentsAlone() {
        // 太短的值多半是示例或变量名，打码反而让输出不可读。
        assertEquals("api_key=short", SecretRedactor.redact("api_key=short"))
    }

    /**
     * 真机缺陷：命令文本被脱敏改坏，`git config --add safe.directory "$PWD"` 报
     * `safe.directory 'D' not absolute`。
     *
     * 根因：`pwd` 曾被当成密码名，`export PWD=/x/y/abcdefgh` 被改写，
     * 之后的 `$PWD` 就成了一段不是路径的东西。
     */
    @Test
    fun leavesShellWorkingDirectoryVariablesAlone() {
        val cases = listOf(
            "export PWD=/data/local/tmp/eta/eta-v2",
            "JAVA_HOME=\$PWD/../.tooling/jdk17",
            "git config --global --add safe.directory \"\$PWD\"",
            "cd \${PWD} && ls",
            "echo \$PWD/x",
        )
        cases.forEach { input ->
            assertEquals("不该改写：$input", input, SecretRedactor.redact(input))
        }
    }

    @Test
    fun stillRedactsRealPasswordAssignments() {
        assertTrue(SecretRedactor.redact("password=hunter2hunter2").contains(mask))
        assertTrue(SecretRedactor.redact("DB_PASSWD=s3cr3t-value").contains(mask))
        assertTrue(SecretRedactor.redact("client_secret: abcdefghij").contains(mask))
        assertTrue(SecretRedactor.redact("secret_key=abcdefghijkl").contains(mask))
    }

    @Test
    fun redactsQuotedPasswordKeepingQuoteShape() {
        val output = SecretRedactor.redact("password=\"hunter2hunter2\"")
        assertEquals("password=\"$mask\"", output)
    }
}
