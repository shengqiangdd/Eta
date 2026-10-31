package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodeOutlineToolTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    private fun write(directory: File, name: String, text: String): File =
        File(directory, name).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    private fun tree(): File {
        val root = temporary.newFolder("src")
        write(root, "User.kt", "package demo\n\ndata class User(val id: Int)\nfun load(): User = User(1)\n")
        write(root, "Repo.go", "package demo\n\nfunc Load() {}\n")
        write(root, "notes.txt", "class NotCode\n")
        write(root, "nested/Deep.kt", "class Deep\n")
        write(root, ".hidden/Skip.kt", "class Skip\n")
        return root
    }

    private fun names(result: JSONObject): List<String> =
        (0 until result.getJSONArray("symbols").length()).map {
            result.getJSONArray("symbols").getJSONObject(it).getString("name")
        }.sorted()

    @Test
    fun singleFileReturnsSymbolsAndLineNumbers() {
        tree()
        val result = JSONObject(operations().codeOutline("src/User.kt"))
        assertEquals(true, result.getBoolean("ok"))
        assertEquals("file", result.getString("mode"))
        assertEquals("kotlin", result.getString("language"))
        assertEquals(1, result.getInt("files_scanned"))
        assertEquals(0, result.getInt("files_skipped"))
        assertEquals(2, result.getInt("count"))
        val symbols = result.getJSONArray("symbols")
        assertEquals("User", symbols.getJSONObject(0).getString("name"))
        assertEquals("class", symbols.getJSONObject(0).getString("kind"))
        assertEquals(3, symbols.getJSONObject(0).getInt("line"))
        assertEquals("User.kt", symbols.getJSONObject(0).getString("file"))
        assertEquals("load", symbols.getJSONObject(1).getString("name"))
        assertEquals(4, symbols.getJSONObject(1).getInt("line"))
    }

    @Test
    fun directorySkipsUnsupportedHiddenAndTooDeepFiles() {
        tree()
        val result = JSONObject(operations().codeOutline("src", depth = 1))
        assertEquals("directory", result.getString("mode"))
        assertEquals("mixed", result.getString("language"))
        assertEquals(2, result.getInt("files_scanned"))
        // nested/Deep.kt 超过 depth=1；.hidden 与 notes.txt 不计入 skipped。
        assertEquals(1, result.getInt("files_skipped"))
        // count 是符号总数：User.kt 的 User + load，Repo.go 的 Load。
        assertEquals(3, result.getInt("count"))
        val files = (0 until result.getJSONArray("symbols").length()).map {
            result.getJSONArray("symbols").getJSONObject(it).getString("file")
        }.distinct().sorted()
        assertEquals(listOf("Repo.go", "User.kt"), files)
    }

    @Test
    fun filtersByKindAndQuery() {
        tree()
        val operations = operations()
        // depth=1 只看 src 本层，先把 kind / query 过滤本身单独验干净。
        val byKind = JSONObject(operations.codeOutline("src", kind = "class", depth = 1))
        assertEquals(1, byKind.getInt("count"))
        assertEquals(listOf("User"), names(byKind))

        val byQuery = JSONObject(operations.codeOutline("src", query = "load", depth = 1))
        // query 不区分大小写：User.kt 的 load 与 Repo.go 的 Load 都算命中。
        assertEquals(2, byQuery.getInt("count"))
        assertEquals(listOf("Load", "load"), names(byQuery))

        val none = JSONObject(operations.codeOutline("src", query = "no-such-symbol"))
        assertEquals(0, none.getInt("count"))
        assertTrue(none.getString("hint").contains("grep_files"))

        // 默认 depth=4 必须扫进子目录：否则真实项目上「目录大纲」等于只看一层。
        val deep = JSONObject(operations.codeOutline("src", kind = "class"))
        assertEquals(listOf("Deep", "User"), names(deep))
    }

    @Test
    fun unknownKindListsAllowedValues() {
        tree()
        val result = JSONObject(operations().codeOutline("src", kind = "method"))
        assertEquals(false, result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertTrue(result.getString("message").contains("function"))
    }

    @Test
    fun unsupportedLanguageSaysSoInsteadOfReturningEmpty() {
        tree()
        val result = JSONObject(operations().codeOutline("src/notes.txt"))
        assertEquals(false, result.getBoolean("ok"))
        assertEquals("UNSUPPORTED_LANGUAGE", result.getString("code"))
        assertTrue(result.getString("message").contains(".kt"))
    }

    @Test
    fun missingPathIsReportedAsNotFound() {
        val result = JSONObject(operations().codeOutline("src/missing.kt"))
        assertEquals(false, result.getBoolean("ok"))
        assertEquals("FILE_NOT_FOUND", result.getString("code"))
    }

    @Test
    fun limitCapsReturnedSymbols() {
        val root = temporary.newFolder("many")
        write(root, "Many.kt", (1..10).joinToString("\n") { "fun f$it() {}" })
        val result = JSONObject(operations().codeOutline("many/Many.kt", limit = 3))
        assertEquals(3, result.getInt("count"))
        assertEquals(true, result.getBoolean("symbols_truncated"))
    }
}
