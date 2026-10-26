package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolMatchHintTest {
    private val source = """
        class Sample {
            fun greet(name: String): String {
                val prefix = "hello"
                return prefix + name
            }
        }
    """.trimIndent()

    @Test
    fun `whitespace only difference points at the actual lines`() {
        val hint = FileToolMatchHint.whitespaceOnly(source, "val prefix = \"hello\"\nreturn prefix + name")
        assertNotNull("应识别仅空白差异", hint)
        assertEquals(3, hint!!.first().line)
        assertTrue(hint.first().preview.contains("val prefix"))
    }

    @Test
    fun `whitespace check ignores unrelated text`() {
        assertNull(FileToolMatchHint.whitespaceOnly(source, "val missing = 42"))
    }

    @Test
    fun `similar finds the closest real line after a small edit`() {
        val candidates = FileToolMatchHint.similar(source, "val prefix = \"hellp\"")
        assertTrue(candidates.isNotEmpty())
        assertEquals(3, candidates.first().line)
        assertTrue(candidates.first().similarity > 0.8)
        assertTrue(candidates.first().preview.contains("hello"))
    }

    @Test
    fun `similar returns nothing for unrelated text`() {
        assertTrue(FileToolMatchHint.similar(source, "totallyDifferentIdentifierXYZ = 1").isEmpty())
    }

    @Test
    fun `ambiguous lists every occurrence line`() {
        val text = "alpha\nbeta\nrun()\nbeta\nrun()\n"
        val hits = FileToolMatchHint.ambiguous(text, "run()")
        assertEquals(listOf(3, 5), hits.map { it.line })
        assertEquals("run()", hits.first().preview)
    }

    @Test
    fun `json carries reason and candidate details`() {
        val json = FileToolMatchHint.toJson(
            FileToolMatchHint.SIMILAR_FOUND,
            listOf(FileToolMatchHint.Candidate(line = 7, similarity = 0.876, preview = "val x = 1")),
        )
        assertEquals(FileToolMatchHint.SIMILAR_FOUND, json.getString("reason"))
        val candidate = json.getJSONArray("candidates").getJSONObject(0)
        assertEquals(7, candidate.getInt("line"))
        assertEquals(0.87, candidate.getDouble("similarity"), 0.001)
        assertEquals("val x = 1", candidate.getString("actual"))
    }

    @Test
    fun `summarize explains ambiguity by line numbers`() {
        val text = FileToolMatchHint.summarize(
            FileToolMatchHint.AMBIGUOUS,
            listOf(FileToolMatchHint.Candidate(3, 1.0, "run()"), FileToolMatchHint.Candidate(5, 1.0, "run()")),
        )
        assertTrue(text.contains("第 3 行"))
        assertTrue(text.contains("第 5 行"))
    }

    @Test
    fun `edit failure exposes hint in tool result`() {
        val temporary = java.nio.file.Files.createTempDirectory("eta-hint").toFile()
        try {
            val file = java.io.File(temporary, "Sample.kt")
            file.writeText(source)
            val operations = AgentFileOperations(LocalFileToolBackend(temporary))
            val result = JSONObject(
                operations.editFile("Sample.kt", "val prefix = \"hellp\"", "val prefix = \"hi\""),
            )
            assertEquals(false, result.getBoolean("ok"))
            assertEquals("MATCH_NOT_FOUND", result.getString("code"))
            assertTrue("失败结果应带结构化提示", result.has("hint"))
            val hint = result.getJSONObject("hint")
            assertEquals(FileToolMatchHint.SIMILAR_FOUND, hint.getString("reason"))
            assertEquals(3, hint.getJSONArray("candidates").getJSONObject(0).getInt("line"))
        } finally {
            temporary.deleteRecursively()
        }
    }
}
