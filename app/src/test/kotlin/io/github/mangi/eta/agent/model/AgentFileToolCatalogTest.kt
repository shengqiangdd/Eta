package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun fileToolDescriptionsReflectAvailableIdentity() {
        val tools = AgentToolCatalog.build(true, false, capabilities = AgentToolCapabilities(rootAvailable = false))
        val fileTools = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .filter { it.getString("name") in AgentFileToolCatalog.names }
        assertEquals(AgentFileToolCatalog.names, fileTools.map { it.getString("name") }.toSet())
        fileTools.forEach { tool ->
            val identities = tool.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("identity").getJSONArray("enum")
            assertEquals(1, identities.length())
            assertEquals("user", identities.getString(0))
        }
    }

    @Test fun editFileAcceptsBatchEditsAndProjectInfoIsDeclared() {
        val tools = AgentToolCatalog.build(true, false)
        val functions = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
        val edit = functions.single { it.getString("name") == "edit_file" }
        val schema = edit.getJSONObject("parameters")
        assertEquals(1, schema.getJSONArray("required").length())
        assertEquals("path", schema.getJSONArray("required").getString(0))
        assertTrue(schema.getJSONObject("properties").has("edits"))
        val projectInfo = functions.single { it.getString("name") == "project_info" }
        assertEquals("object", projectInfo.getJSONObject("parameters").getString("type"))
    }

    @Test fun editFilesRequiresFilesArrayAndKeepsEditsNested() {
        val tools = AgentToolCatalog.build(true, false)
        val functions = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
        val editFiles = functions.single { it.getString("name") == "edit_files" }
        val schema = editFiles.getJSONObject("parameters")
        assertEquals("files", schema.getJSONArray("required").getString(0))
        val files = schema.getJSONObject("properties").getJSONObject("files")
        assertEquals(20, files.getInt("maxItems"))
        val item = files.getJSONObject("items")
        assertEquals("edits", item.getJSONArray("required").getString(1))
        assertTrue(item.getJSONObject("properties").getJSONObject("edits").has("maxItems"))
    }

    @Test fun guardBackupAndPreviewControlsAreDeclared() {
        val tools = AgentToolCatalog.build(true, false)
        val functions = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
        val edit = functions.single { it.getString("name") == "edit_file" }
            .getJSONObject("parameters").getJSONObject("properties")
        assertTrue(edit.has("dry_run"))
        val editFiles = functions.single { it.getString("name") == "edit_files" }
            .getJSONObject("parameters").getJSONObject("properties")
        assertTrue(editFiles.has("dry_run"))
        val list = functions.single { it.getString("name") == "list_file_backups" }
        assertEquals("path", list.getJSONObject("parameters").getJSONArray("required").getString(0))
        val restore = functions.single { it.getString("name") == "restore_file" }
        assertTrue(restore.getJSONObject("parameters").getJSONObject("properties").has("backup_id"))
    }
}
