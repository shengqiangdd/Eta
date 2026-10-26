package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolGuardTest {

    private fun call(name: String, args: String) = AgentModelClient.ToolCall(id = "c1", name = name, argumentsJson = args)

    @Test
    fun repeatedIdenticalCallsAreRemindedAtThresholdsOnly() {
        val guard = AgentRepeatGuard()
        val args = """{"path":"/sdcard/a.txt"}"""
        assertNull(guard.observe(call("read_file", args)))
        assertNull(guard.observe(call("read_file", args)))
        val third = guard.observe(call("read_file", args))
        assertEquals(3, third?.count)
        assertEquals(AgentRepeatGuard.GENTLE, third?.text)
        assertNull(guard.observe(call("read_file", args)))
        val fifth = guard.observe(call("read_file", args))
        assertEquals(5, fifth?.count)
        assertTrue(fifth?.text.orEmpty().contains("连续次数：5"))
    }

    @Test
    fun differentArgumentsResetTheChain() {
        val guard = AgentRepeatGuard()
        val a = call("read_file", """{"path":"a"}""")
        val b = call("read_file", """{"path":"b"}""")
        assertNull(guard.observe(a))
        assertNull(guard.observe(a))
        assertNull(guard.observe(b))
        assertNull(guard.observe(a))
        assertNull(guard.observe(a))
        assertNotNull(guard.observe(a))
    }

    @Test
    fun keyOrderDoesNotFakeANewCall() {
        val guard = AgentRepeatGuard()
        assertNull(guard.observe(call("terminal", """{"command":"ls","cwd":"/"}""")))
        assertNull(guard.observe(call("terminal", """{"cwd":"/","command":"ls"}""")))
        assertNotNull(guard.observe(call("terminal", """{"command":"ls","cwd":"/"}""")))
    }

    @Test
    fun malformedArgumentsFallBackToTrimmedRawText() {
        assertEquals("not json", AgentRepeatGuard.canonicalArguments("  not json  "))
        val guard = AgentRepeatGuard()
        assertNull(guard.observe(call("x", "raw")))
        assertNull(guard.observe(call("x", " raw ")))
        assertNotNull(guard.observe(call("x", "raw")))
    }

    @Test
    fun longArgumentsArePreviewedWithLength() {
        val guard = AgentRepeatGuard(argumentsPreviewChars = 10)
        val longArgs = """{"text":"${"a".repeat(100)}"}"""
        // 第 3 次是简短提醒，第 5 次才带参数预览。
        repeat(4) { guard.observe(call("write_file", longArgs)) }
        val reminder = guard.observe(call("write_file", longArgs))
        assertEquals(5, reminder?.count)
        assertTrue(reminder?.text.orEmpty().contains("…（共"))
    }

    @Test
    fun repeatedFailureCodeIsReminded() {
        val guard = AgentRepeatedFailureGuard()
        val failure = """{"ok":false,"code":"ACCESSIBILITY_UNAVAILABLE","message":"no"}"""
        assertNull(guard.observe("tap", failure))
        val second = guard.observe("tap", failure)
        assertEquals(2, second?.count)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", second?.code)
        assertNull(guard.observe("tap", failure))
        assertNotNull(guard.observe("tap", failure))
    }

    @Test
    fun successOrDifferentCodeResetsFailureChain() {
        val guard = AgentRepeatedFailureGuard()
        val a = """{"ok":false,"code":"A"}"""
        val b = """{"ok":false,"code":"B"}"""
        assertNull(guard.observe("t", a))
        assertNull(guard.observe("t", """{"ok":true}"""))
        assertNull(guard.observe("t", a))
        assertNull(guard.observe("t", b))
        // 换错误码后重新计数：第二次就该提醒。
        assertEquals(2, guard.observe("t", b)?.count)
        assertNull(guard.observe("t", b))
    }

    @Test
    fun missingCodeUsesUnknownAndNonJsonResets() {
        val guard = AgentRepeatedFailureGuard()
        val failure = """{"ok":false}"""
        assertNull(guard.observe("t", failure))
        assertEquals("UNKNOWN", guard.observe("t", failure)?.code)
        assertNull(guard.observe("t", "plain text"))
        assertNull(guard.observe("t", failure))
    }

    @Test
    fun attachKeepsJsonParseableAndMergesNotes() {
        val content = """{"ok":true,"count":2}"""
        val once = JSONObject(AgentToolGuard.attach(content, "第一条"))
        assertEquals(2, once.getInt("count"))
        assertEquals("第一条", once.getString("guard_note"))
        val twice = JSONObject(AgentToolGuard.attach(once.toString(), "第二条"))
        assertEquals("第一条\n第二条", twice.getString("guard_note"))
    }

    @Test
    fun attachAppendsPlainTextResults() {
        assertEquals("done\n\n提醒", AgentToolGuard.attach("done", "提醒"))
    }
}
