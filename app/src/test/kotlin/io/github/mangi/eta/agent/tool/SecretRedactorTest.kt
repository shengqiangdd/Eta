package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun redactsAssignmentsAndBearerTokens() {
        val input = """
            API_KEY=sk-abcdefghijklmnopqrstuvwxyz123456
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature
            password: hunter2hunter2
        """.trimIndent()

        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("sk-abcdefghijklmnopqrstuvwxyz123456"))
        assertFalse(output.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(output.contains("hunter2hunter2"))
        assertTrue(output.contains("API_KEY=<已隐藏>"))
        assertTrue(output.contains("Bearer <已隐藏>"))
        assertTrue(output.contains("password: <已隐藏>"))
    }

    @Test
    fun redactsJsonCredentialsWithoutBreakingJson() {
        val input = """{"api_key":"sk-1234567890abcdefghij","model":"x","password":"hunter2hunter2"}"""

        val output = SecretRedactor.redact(input)
        val json = JSONObject(output)

        assertEquals("<已隐藏>", json.getString("api_key"))
        assertEquals("<已隐藏>", json.getString("password"))
        assertEquals("x", json.getString("model"))
    }

    @Test
    fun redactsKeyGluedAfterUrl() {
        val input = "https://api.example.com/v1sk-vnnE3ftFwpwIhXhi5JuNFEyOIz44Kd9OG8DKvM2NVK1rHOcb"

        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("sk-vnnE3ft"))
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
}
