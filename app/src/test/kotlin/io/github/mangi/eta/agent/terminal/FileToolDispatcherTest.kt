package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolDispatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun backend(root: File = temporary.root) = LocalFileToolBackend(root)

    @Test fun editsArrayPassesThroughTheJsonBoundary() {
        val file = temporary.newFile("batch.txt").apply { writeText("alpha\nbeta\n") }
        val args = JSONObject()
            .put("path", file.name)
            .put(
                "edits",
                JSONArray()
                    .put(JSONObject().put("old_text", "alpha").put("new_text", "ALPHA"))
                    .put(JSONObject().put("old_text", "beta").put("new_text", "BETA")),
            )
        val result = JSONObject(FileToolDispatcher.execute(backend(), "edit_file", args))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("edits_applied"))
        assertEquals("ALPHA\nBETA\n", file.readText())
    }

    @Test fun projectInfoRunsThroughTheDispatcher() {
        File(temporary.root, "package.json").writeText("""{"scripts":{"test":"vitest"}}""")
        val result = JSONObject(
            FileToolDispatcher.execute(backend(), "project_info", JSONObject().put("path", temporary.root.path)),
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("project_info", result.getString("tool"))
        assertEquals("node", result.getJSONArray("projects").getJSONObject(0).getString("type"))
    }

    @Test fun editFilesPassesThroughTheJsonBoundary() {
        val first = temporary.newFile("first.txt").apply { writeText("one\n") }
        val second = temporary.newFile("second.txt").apply { writeText("alpha\n") }
        val files = JSONArray()
            .put(
                JSONObject().put("path", first.name).put(
                    "edits",
                    JSONArray().put(JSONObject().put("old_text", "one").put("new_text", "ONE")),
                ),
            )
            .put(
                JSONObject().put("path", second.name).put(
                    "edits",
                    JSONArray().put(JSONObject().put("old_text", "alpha").put("new_text", "ALPHA")),
                ),
            )
        val result = JSONObject(FileToolDispatcher.execute(backend(), "edit_files", JSONObject().put("files", files)))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("file_count"))
        assertEquals("ONE\n", first.readText())
        assertEquals("ALPHA\n", second.readText())
    }

    @Test fun readGuardAndDryRunPassThroughTheDispatcher() {
        val file = temporary.newFile("guard.txt").apply { writeText("one") }
        val ledger = FileAccessLedger()
        val backend = backend()
        val denied = JSONObject(
            FileToolDispatcher.execute(
                backend,
                "edit_file",
                JSONObject().put("path", file.name).put("old_text", "one").put("new_text", "two"),
                ledger,
            ),
        )
        assertFalse(denied.getBoolean("ok"))
        assertEquals("READ_REQUIRED", denied.getString("code"))

        assertTrue(JSONObject(FileToolDispatcher.execute(backend, "read_file", JSONObject().put("path", file.name), ledger)).getBoolean("ok"))
        val preview = JSONObject(
            FileToolDispatcher.execute(
                backend,
                "edit_file",
                JSONObject().put("path", file.name).put("old_text", "one").put("new_text", "two").put("dry_run", true),
                ledger,
            ),
        )
        assertTrue(preview.toString(), preview.getBoolean("ok"))
        assertTrue(preview.getBoolean("dry_run"))
        assertEquals("one", file.readText())
    }

    @Test fun backupToolsReportUnavailableWithoutAStore() {
        val file = temporary.newFile("no-store.txt").apply { writeText("one") }
        val list = JSONObject(FileToolDispatcher.execute(backend(), "list_file_backups", JSONObject().put("path", file.name)))
        assertTrue(list.getBoolean("ok"))
        assertFalse(list.getBoolean("available"))
        assertEquals(0, list.getInt("count"))
        val restore = JSONObject(FileToolDispatcher.execute(backend(), "restore_file", JSONObject().put("path", file.name)))
        assertFalse(restore.getBoolean("ok"))
        assertEquals("BACKUP_UNAVAILABLE", restore.getString("code"))
    }

    @Test fun grepFilesNamesTheRightFieldWhenPatternIsUsed() {
        // `pattern` 是 glob_files 的参数；传错时只报「query 缺失」不足以让模型改对。
        val failure = try {
            FileToolDispatcher.execute(
                backend(),
                "grep_files",
                JSONObject().put("path", temporary.root.path).put("pattern", "**/*.kt"),
            )
            null
        } catch (error: FileToolException) {
            error
        }
        assertTrue("传错字段名应被拒绝并说明分工", failure != null)
        assertEquals("INVALID_ARGUMENT", failure!!.code)
        assertTrue(failure.message!!, failure.message!!.contains("query"))
        assertTrue(failure.message!!, failure.message!!.contains("glob_files"))
    }

    @Test fun grepFilesStillWorksWithQuery() {
        val file = temporary.newFile("hit.txt").apply { writeText("needle\n") }
        val result = JSONObject(
            FileToolDispatcher.execute(
                backend(),
                "grep_files",
                JSONObject().put("path", temporary.root.path).put("query", "needle"),
            ),
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.toString(), result.toString().contains(file.name))
    }
}
