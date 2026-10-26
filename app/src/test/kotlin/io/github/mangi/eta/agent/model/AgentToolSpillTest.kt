package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolSpillTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun spill(maxChars: Int = 100, preview: Int = 20) =
        AgentToolSpill(temporaryFolder.root, maxChars = maxChars, previewChars = preview, clock = { 1_700_000_000_000 })

    @Test
    fun smallResultsStayInContext() {
        assertNull(spill().maybeSpill("terminal", "scope", "call", "short"))
    }

    @Test
    fun largeResultsBecomeALocatorWithPreview() {
        val content = "x".repeat(500)
        val spilled = spill().maybeSpill("terminal", "scope-1", "call-1", content)!!
        assertEquals(500, spilled.chars)
        assertTrue(spilled.path.endsWith(".txt"))
        assertEquals(content, java.io.File(spilled.path).readText())
        val envelope = JSONObject(spilled.content)
        assertTrue(envelope.getBoolean("spilled"))
        val locator = envelope.getJSONObject("spill")
        assertEquals(500, locator.getInt("chars"))
        assertEquals("x".repeat(20), locator.getString("preview"))
        assertTrue(locator.getString("hint").contains("read_file"))
        assertTrue(envelope.optBoolean("ok"))
        assertTrue(spilled.fields.isEmpty())
    }

    @Test
    fun failedToolResultsKeepTheirOkFlagAndErrorCode() {
        val content = JSONObject().put("ok", false).put("code", "TOOL_TIMEOUT").put("blob", "y".repeat(300)).toString()
        val envelope = JSONObject(spill().maybeSpill("logcat", "s", "c", content)!!.content)
        assertFalse(envelope.getBoolean("ok"))
        // 旧实现整段替换，错误码一起被吃掉，模型只能看到「结果很大」。
        assertEquals("TOOL_TIMEOUT", envelope.getString("code"))
        val fields = envelope.getJSONObject("spill").getJSONArray("fields")
        assertEquals(1, fields.length())
        assertEquals("blob", fields.getString(0))

    }

    @Test
    fun pagedReadersAreNeverSpilled() {
        // read_file 的一页最多 16,000 字节，转义后约 20,000 字；旧实现必然把它溢写掉，
        // 于是「读回溢写文件」又得到新的溢写，全文永远进不了上下文。
        val page = JSONObject()
            .put("ok", true)
            .put("next_offset_bytes", 16_000)
            .put("revision", "abc")
            .put("content", "z".repeat(40_000))
            .toString()
        for (tool in AgentToolSpill.SELF_BOUNDED_TOOLS) {
            assertNull(tool, spill().maybeSpill(tool, "scope", "call", page))
        }
        assertTrue(temporaryFolder.root.walk().none { it.isFile })
    }

    @Test
    fun spilledJsonKeepsPagingAndEditingMetadata() {
        val content = JSONObject()
            .put("ok", true)
            .put("path", "/sdcard/big.log")
            .put("next_offset_bytes", 16_000)
            .put("revision", "rev-1")
            .put("content", "line\n".repeat(1_000))
            .toString()
        val spilled = spill(maxChars = 1_000).maybeSpill("terminal", "scope", "call", content)!!
        val envelope = JSONObject(spilled.content)
        // 上下文里必须还留着重试/续读所需的一切。
        assertEquals(16_000, envelope.getInt("next_offset_bytes"))
        assertEquals("rev-1", envelope.getString("revision"))
        assertEquals("/sdcard/big.log", envelope.getString("path"))
        assertTrue(envelope.getBoolean("spilled"))
        assertFalse(envelope.has("content"))
        assertEquals(listOf("content"), spilled.fields)
        // 磁盘上仍是完整原文，读回不丢信息。
        assertEquals(content, java.io.File(spilled.path).readText())
    }

    @Test
    fun arrayFieldsAreSpilledWithoutLosingTheCursor() {
        val matches = org.json.JSONArray().also { array -> repeat(200) { array.put("匹配行 $it") } }
        val content = JSONObject()
            .put("ok", true)
            .put("next_cursor", "cursor-2")
            .put("has_more", true)
            .put("matches", matches)
            .toString()
        val envelope = JSONObject(spill(maxChars = 200).maybeSpill("grep_mcp", "scope", "call", content)!!.content)
        assertEquals("cursor-2", envelope.getString("next_cursor"))
        assertTrue(envelope.getBoolean("has_more"))
        assertFalse(envelope.has("matches"))
    }

    @Test
    fun escapingCountsTowardTheBudget() {
        // 100 个引号原始长度只有 100，但序列化后是 200：按原长度判定就会漏。
        val quoted = "\"".repeat(100)
        assertEquals(200, AgentToolSpill.contextCost(quoted))
        assertTrue(spill(maxChars = 150).maybeSpill("terminal", "scope", "call", quoted) != null)
        assertNull(spill(maxChars = 250).maybeSpill("terminal", "scope", "call", quoted))
    }

    @Test
    fun previewNeverSplitsSurrogatePairs() {
        val content = "\uD83D\uDE00" + "x".repeat(500)
        val envelope = JSONObject(spill(preview = 1).maybeSpill("terminal", "scope", "call", content)!!.content)
        val preview = envelope.getJSONObject("spill").getString("preview")
        assertEquals("\uD83D\uDE00", preview)
        assertFalse(Character.isHighSurrogate(preview.last()))
    }

    @Test
    fun locatorCarriesAnExecutableReadBackContract() {
        val content = "y".repeat(5_000)
        val spilled = spill().maybeSpill("terminal", "scope", "call", content)!!
        val envelope = JSONObject(spilled.content)
        val read = envelope.getJSONObject("spill").getJSONObject("read")
        assertEquals(spilled.path, read.getString("path"))
        assertEquals(0, read.getInt("offset_bytes"))
        assertEquals(AgentToolSpill.READ_BACK_BYTES, read.getInt("max_bytes"))
        assertEquals(5_000, read.getInt("total_chars"))
        // 读回参数必须是 read_file 真能接受的：超过它的上限就成了「看起来能读、实际读不到」。
        assertTrue(read.getInt("max_bytes") in 4..262_144)
        val retention = envelope.getJSONObject("spill").getJSONObject("retention")
        assertEquals("scope", retention.getString("scope"))
        assertEquals(AgentToolSpill.MAX_FILES, retention.getInt("max_files"))
    }

    @Test
    fun blankScopeIsNotSpilled() {
        assertNull(spill().maybeSpill("terminal", "  ", "call", "z".repeat(500)))
    }

    @Test
    fun unsafeNamesNeverEscapeTheRootDirectory() {
        val spilled = spill().maybeSpill("../../evil", "../scope", "../call", "z".repeat(500))!!
        val root = temporaryFolder.root.canonicalPath
        assertTrue(java.io.File(spilled.path).canonicalPath.startsWith(root))
        assertEquals(1, java.io.File(spilled.path).parentFile!!.listFiles()!!.size)
    }

    @Test
    fun oldSpillsArePrunedToTheNewestFiles() {
        var tick = 0L
        val spill = AgentToolSpill(temporaryFolder.root, maxChars = 10, previewChars = 5, clock = { ++tick })
        repeat(AgentToolSpill.MAX_FILES + 5) { index ->
            spill.maybeSpill("terminal", "scope", "call-$index", "z".repeat(100))
        }
        val dir = java.io.File(temporaryFolder.root, "scope")
        assertEquals(AgentToolSpill.MAX_FILES, dir.listFiles()!!.size)
    }
}
