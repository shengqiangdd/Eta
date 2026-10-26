package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolSearchRegexTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test
    fun `regex matches digit groups and reports the matched line`() {
        temporary.newFile("codes.txt").writeText("order 123\norder 45\ncode 987\n")
        val result = JSONObject(operations().grepFiles("", "\\d{3}", regex = true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("regex"))
        assertEquals(2, result.getInt("count"))
        val lines = result.getJSONArray("matches")
        assertEquals(1, lines.getJSONObject(0).getInt("line"))
        assertEquals(3, lines.getJSONObject(1).getInt("line"))
    }

    @Test
    fun `regex respects case sensitivity switch`() {
        temporary.newFile("words.txt").writeText("Hello world\n")
        assertEquals(0, JSONObject(operations().grepFiles("", "hello", regex = true)).getInt("count"))
        assertEquals(1, JSONObject(operations().grepFiles("", "hello", regex = true, caseSensitive = false)).getInt("count"))
    }

    @Test
    fun `invalid regex is rejected instead of silently matching nothing`() {
        temporary.newFile("words.txt").writeText("hello\n")
        val result = JSONObject(operations().grepFiles("", "a(b", regex = true))
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_QUERY", result.getString("code"))
    }

    @Test
    fun `inline mode flags are rejected to keep scans bounded`() {
        temporary.newFile("words.txt").writeText("hello\n")
        assertEquals("INVALID_QUERY", JSONObject(operations().grepFiles("", "(?s)hello", regex = true)).getString("code"))
    }

    @Test
    fun `files only returns one entry per file with hit counts`() {
        temporary.newFile("a.txt").writeText("hit\nhit\nhit\n")
        temporary.newFile("b.txt").writeText("nothing here\n")
        temporary.newFile("c.txt").writeText("one hit\n")
        val result = JSONObject(operations().grepFiles("", "hit", filesOnly = true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("files_only"))
        assertEquals(2, result.getInt("count"))
        val byRelative = (0 until result.getJSONArray("matches").length()).map {
            result.getJSONArray("matches").getJSONObject(it)
        }.associateBy { it.getString("relative_path") }
        assertEquals(3, byRelative.getValue("a.txt").getInt("matches"))
        assertEquals(1, byRelative.getValue("c.txt").getInt("matches"))
        assertFalse(byRelative.getValue("a.txt").has("line"))
    }

    @Test
    fun `files only respects the file limit and can continue with a cursor`() {
        repeat(3) { index -> temporary.newFile("f$index.txt").writeText("needle\n") }
        val first = JSONObject(operations().grepFiles("", "needle", limit = 2, filesOnly = true))
        assertEquals(2, first.getInt("count"))
        assertTrue(first.getBoolean("truncated"))
        val second = JSONObject(
            operations().grepFiles("", "needle", limit = 2, filesOnly = true, cursor = first.getString("next_cursor")),
        )
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertTrue(second.getInt("count") >= 1)
    }

    @Test
    fun `literal search still works for paths with regex metacharacters`() {
        temporary.newFile("meta.txt").writeText("a.b(c)\n")
        assertEquals(1, JSONObject(operations().grepFiles("", "a.b(c)")).getInt("count"))
        // 同一串作为正则时，括号未闭合组会被拒绝（而不是被当成字面量）。
        assertFalse(JSONObject(operations().grepFiles("", "a.b(c", regex = true)).getBoolean("ok"))
    }

    @Test
    fun `file level edits still block ambiguous matches with locations`() {
        val file = temporary.newFile("dup.kt")
        file.writeText("val x = 1\nval x = 1\nval x = 2\n")
        val result = JSONObject(operations().editFile("dup.kt", "val x = 1", "val x = 3"))
        assertFalse(result.getBoolean("ok"))
        assertEquals("AMBIGUOUS_MATCH", result.getString("code"))
        val lines = result.getJSONObject("hint").getJSONArray("candidates")
        assertEquals(2, lines.length())
        assertEquals(1, lines.getJSONObject(0).getInt("line"))
        assertEquals(2, lines.getJSONObject(1).getInt("line"))
        assertTrue(File(temporary.root, "dup.kt").readText().startsWith("val x = 1"))
    }
}
